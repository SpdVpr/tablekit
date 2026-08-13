package com.kitworks.tablekit.data

import com.kitworks.tablekit.data.xlsx.CellKind
import com.kitworks.tablekit.data.xlsx.CellValue
import com.kitworks.tablekit.data.xlsx.XlsxWorkbook
import java.nio.file.Path
import java.sql.Connection

/**
 * Loads one worksheet into the query engine so that the grid, sorting and
 * filtering work the same way they do for a Parquet file.
 *
 * A spreadsheet has no schema, so the sheet is read twice: once to learn its
 * width and what each column actually holds, and once to load it. That costs a
 * second parse and keeps memory flat, which is the right trade for a file
 * format that tops out at a million rows.
 *
 * Values are loaded as text and the typed view is built afterwards. Because the
 * first pass has seen every value, the casts cannot fail - a column is only
 * declared a number when nothing in it is anything else.
 */
object ExcelImporter {

    private const val STAGING_TABLE = "tablekit_excel"

    /** Sheet names in workbook order, for the sheet selector. */
    fun sheetNames(path: Path): List<String> =
        XlsxWorkbook.open(path).use { workbook -> workbook.sheets.map { it.name } }

    /**
     * Loads [sheetIndex] into a temp table and returns the relation to select
     * from, with columns cast to the types the data turned out to have.
     */
    fun load(connection: Connection, path: Path, sheetIndex: Int): String {
        XlsxWorkbook.open(path).use { workbook ->
            val layout = scan(workbook, sheetIndex)
            createStagingTable(connection, layout.columnCount)
            copyRows(connection, workbook, sheetIndex, layout)
            return typedSelect(layout)
        }
    }

    // --- pass one -----------------------------------------------------------

    private class Layout(val names: List<String>, val kinds: List<CellKind?>, val headerRow: Boolean) {
        val columnCount: Int get() = names.size
    }

    private fun scan(workbook: XlsxWorkbook, sheetIndex: Int): Layout {
        var firstRow: List<CellValue?>? = null
        var width = 0
        val kinds = mutableListOf<CellKind?>()
        val mixed = mutableSetOf<Int>()
        var rowIndex = 0

        workbook.readSheet(sheetIndex) { cells ->
            if (rowIndex == 0) {
                firstRow = cells
            } else {
                width = maxOf(width, cells.size)
                cells.forEachIndexed { column, cell ->
                    if (cell == null) return@forEachIndexed
                    while (kinds.size <= column) kinds.add(null)
                    val known = kinds[column]
                    kinds[column] = when {
                        column in mixed -> CellKind.TEXT
                        known == null -> cell.kind
                        known == cell.kind -> known
                        known.isNumeric && cell.kind.isNumeric -> CellKind.DECIMAL
                        known.isTemporal && cell.kind.isTemporal -> CellKind.TIMESTAMP
                        else -> {
                            mixed += column
                            CellKind.TEXT
                        }
                    }
                }
            }
            rowIndex++
        }

        val header = firstRow.orEmpty()
        // A first row of nothing but text is a header; anything else is data.
        val headerRow = header.isNotEmpty() && header.any { it != null } &&
            header.all { it == null || it.kind == CellKind.TEXT }

        width = maxOf(width, header.size)
        while (kinds.size < width) kinds.add(null)

        if (!headerRow) {
            // The first row is data too, so it has to count towards the types.
            header.forEachIndexed { column, cell ->
                if (cell != null && kinds[column] == null) kinds[column] = cell.kind
            }
        }

        return Layout(columnNames(header, width, headerRow), kinds.take(width), headerRow)
    }

    private fun columnNames(header: List<CellValue?>, width: Int, headerRow: Boolean): List<String> {
        val used = mutableSetOf<String>()
        return (0 until width).map { column ->
            val proposed = (if (headerRow) header.getOrNull(column)?.text?.trim() else null)
                ?.takeIf { it.isNotEmpty() }
                ?: spreadsheetColumnName(column)

            var name = proposed
            var suffix = 2
            while (!used.add(name)) {
                name = "${proposed}_${suffix++}"
            }
            name
        }
    }

    /** 0 -> A, 25 -> Z, 26 -> AA: the name the user sees in Excel. */
    private fun spreadsheetColumnName(column: Int): String {
        var remaining = column
        val name = StringBuilder()
        while (true) {
            name.insert(0, ('A' + remaining % 26))
            remaining = remaining / 26 - 1
            if (remaining < 0) break
        }
        return name.toString()
    }

    // --- pass two -----------------------------------------------------------

    private fun createStagingTable(connection: Connection, columnCount: Int) {
        val columns = (0 until columnCount).joinToString(", ") { "${stagingColumn(it)} VARCHAR" }
        connection.createStatement().use { statement ->
            statement.execute("CREATE OR REPLACE TEMP TABLE $STAGING_TABLE ($columns)")
        }
    }

    private fun copyRows(connection: Connection, workbook: XlsxWorkbook, sheetIndex: Int, layout: Layout) {
        val placeholders = (0 until layout.columnCount).joinToString(", ") { "?" }
        connection.prepareStatement("INSERT INTO $STAGING_TABLE VALUES ($placeholders)").use { statement ->
            var rowIndex = 0
            var batched = 0

            workbook.readSheet(sheetIndex) { cells ->
                val skip = rowIndex == 0 && layout.headerRow
                rowIndex++
                if (!skip) {
                    for (column in 0 until layout.columnCount) {
                        statement.setString(column + 1, cells.getOrNull(column)?.text)
                    }
                    statement.addBatch()
                    if (++batched >= BATCH_SIZE) {
                        statement.executeBatch()
                        batched = 0
                    }
                }
            }
            if (batched > 0) statement.executeBatch()
        }
    }

    private fun typedSelect(layout: Layout): String {
        val projection = layout.names.mapIndexed { column, name ->
            val source = stagingColumn(column)
            val expression = when (layout.kinds.getOrNull(column)) {
                CellKind.INTEGER -> "TRY_CAST($source AS BIGINT)"
                CellKind.DECIMAL -> "TRY_CAST($source AS DOUBLE)"
                CellKind.BOOLEAN -> "TRY_CAST($source AS BOOLEAN)"
                CellKind.DATE -> "TRY_CAST($source AS DATE)"
                CellKind.TIME -> "TRY_CAST($source AS TIME)"
                CellKind.TIMESTAMP -> "TRY_CAST($source AS TIMESTAMP)"
                else -> source
            }
            "$expression AS ${Sql.identifier(name)}"
        }.joinToString(", ")
        return "(SELECT $projection FROM $STAGING_TABLE)"
    }

    private fun stagingColumn(index: Int): String = "c$index"

    private const val BATCH_SIZE = 2_000

    private val CellKind.isNumeric: Boolean
        get() = this == CellKind.INTEGER || this == CellKind.DECIMAL

    private val CellKind.isTemporal: Boolean
        get() = this == CellKind.DATE || this == CellKind.TIME || this == CellKind.TIMESTAMP
}
