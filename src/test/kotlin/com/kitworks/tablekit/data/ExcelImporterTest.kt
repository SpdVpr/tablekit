package com.kitworks.tablekit.data

import com.kitworks.tablekit.format.TabularFormat
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The .xlsx reader is ours, so it is checked against workbooks written by
 * Apache POI - a real Excel writer, not our own idea of the format.
 */
class ExcelImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a header row names the columns and the values keep their types`() {
        val file = workbook { sheet ->
            sheet.header("id", "name", "score", "active")
            sheet.row(1) { row ->
                row.createCell(0).setCellValue(1.0)
                row.createCell(1).setCellValue("Ada")
                row.createCell(2).setCellValue(9.5)
                row.createCell(3).setCellValue(true)
            }
            sheet.row(2) { row ->
                row.createCell(0).setCellValue(2.0)
                row.createCell(1).setCellValue("Linus")
                row.createCell(2).setCellValue(7.25)
                row.createCell(3).setCellValue(false)
            }
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            assertEquals(listOf("id", "name", "score", "active"), source.columns.map { it.name })
            assertEquals("BIGINT", source.columns[0].typeName)
            assertEquals("VARCHAR", source.columns[1].typeName)
            assertEquals("DOUBLE", source.columns[2].typeName)
            assertEquals("BOOLEAN", source.columns[3].typeName)
            assertEquals(2L, source.rowCount)

            val rows = source.fetchPage(Query(), 0, 10).rows
            assertEquals(listOf("1", "Ada", "9.5", "true"), rows[0].toList())
            assertEquals(listOf("2", "Linus", "7.25", "false"), rows[1].toList())
        }
    }

    @Test
    fun `dates are read as timestamps, not as the numbers Excel stores`() {
        val file = workbook { sheet ->
            sheet.header("when")
            val dateStyle = sheet.workbook.createCellStyle().apply {
                dataFormat = sheet.workbook.creationHelper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss")
            }
            sheet.row(1) { row ->
                row.createCell(0).apply {
                    setCellValue(LocalDateTime.of(2026, 8, 13, 14, 30, 0))
                    cellStyle = dateStyle
                }
            }
            sheet.row(2) { row ->
                row.createCell(0).apply {
                    setCellValue(LocalDate.of(1969, 7, 20).atStartOfDay())
                    cellStyle = dateStyle
                }
            }
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            assertEquals("TIMESTAMP", source.columns[0].typeName)
            val rows = source.fetchPage(Query(), 0, 10).rows
            assertEquals("2026-08-13 14:30:00", rows[0][0])
            assertEquals("1969-07-20 00:00:00", rows[1][0])
        }
    }

    /** Excel's 1900 calendar contains a day that never existed; dates before it must still be right. */
    @Test
    fun `dates around the 1900 leap year bug are correct`() {
        val file = workbook { sheet ->
            sheet.header("when")
            val dateStyle = sheet.workbook.createCellStyle().apply {
                dataFormat = sheet.workbook.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
            }
            listOf(LocalDate.of(1900, 1, 1), LocalDate.of(1900, 2, 28), LocalDate.of(1900, 3, 1))
                .forEachIndexed { index, date ->
                    sheet.row(index + 1) { row ->
                        row.createCell(0).apply {
                            setCellValue(date.atStartOfDay())
                            cellStyle = dateStyle
                        }
                    }
                }
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            val rows = source.fetchPage(Query(), 0, 10).rows.map { it[0] }
            assertEquals(listOf("1900-01-01 00:00:00", "1900-02-28 00:00:00", "1900-03-01 00:00:00"), rows)
        }
    }

    @Test
    fun `a column holding both numbers and text stays text`() {
        val file = workbook { sheet ->
            sheet.header("value")
            sheet.row(1) { it.createCell(0).setCellValue(42.0) }
            sheet.row(2) { it.createCell(0).setCellValue("n/a") }
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            assertEquals("VARCHAR", source.columns[0].typeName)
            assertEquals(listOf("42", "n/a"), source.fetchPage(Query(), 0, 10).rows.map { it[0] })
        }
    }

    @Test
    fun `a sheet without a header row gets spreadsheet column names`() {
        val file = workbook { sheet ->
            sheet.row(0) { row ->
                row.createCell(0).setCellValue(1.0)
                row.createCell(1).setCellValue(2.0)
            }
            sheet.row(1) { row ->
                row.createCell(0).setCellValue(3.0)
                row.createCell(1).setCellValue(4.0)
            }
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            assertEquals(listOf("A", "B"), source.columns.map { it.name })
            assertEquals("no row may be swallowed as a header", 2L, source.rowCount)
        }
    }

    @Test
    fun `repeated and blank header cells still produce usable column names`() {
        val file = workbook { sheet ->
            sheet.header("name", "name", "")
            sheet.row(1) { row ->
                row.createCell(0).setCellValue("a")
                row.createCell(1).setCellValue("b")
                row.createCell(2).setCellValue("c")
            }
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            assertEquals(listOf("name", "name_2", "C"), source.columns.map { it.name })
        }
    }

    @Test
    fun `gaps in a sheet become nulls`() {
        val file = workbook { sheet ->
            sheet.header("a", "b", "c")
            sheet.row(1) { row ->
                row.createCell(0).setCellValue("x")
                row.createCell(2).setCellValue("z")
            }
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            val row = source.fetchPage(Query(), 0, 10).rows.single()
            assertEquals("x", row[0])
            assertNull(row[1])
            assertEquals("z", row[2])
        }
    }

    @Test
    fun `a formula contributes its computed value`() {
        val file = workbook { sheet ->
            sheet.header("a", "b", "total")
            sheet.row(1) { row ->
                row.createCell(0).setCellValue(2.0)
                row.createCell(1).setCellValue(3.0)
                row.createCell(2).cellFormula = "A2+B2"
            }
        }
        // POI stores formulas without a cached result until they are evaluated.
        XSSFWorkbook(Files.newInputStream(file)).use { book ->
            book.creationHelper.createFormulaEvaluator().evaluateAll()
            Files.newOutputStream(file).use(book::write)
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            assertEquals("5", source.fetchPage(Query(), 0, 10).rows.single()[2])
        }
    }

    @Test
    fun `every sheet is listed and any of them can be opened`() {
        val file = temp.newFile("multi.xlsx").toPath()
        XSSFWorkbook().use { book ->
            listOf("Summary", "Data 2026", "Notes").forEachIndexed { index, name ->
                val sheet = book.createSheet(name)
                sheet.createRow(0).createCell(0).setCellValue("sheet")
                sheet.createRow(1).createCell(0).setCellValue(name + "-" + index)
            }
            Files.newOutputStream(file).use(book::write)
        }

        TableSource.open(file, TabularFormat.EXCEL, sheetIndex = 1).use { source ->
            assertEquals(listOf("Summary", "Data 2026", "Notes"), source.sheets)
            assertEquals(1, source.sheetIndex)
            assertEquals("Data 2026-1", source.fetchPage(Query(), 0, 10).rows.single()[0])
        }
    }

    @Test
    fun `a file that is not a workbook fails with a readable message`() {
        val file = temp.newFile("broken.xlsx").toPath()
        Files.write(file, "PK not really".toByteArray())

        try {
            TableSource.open(file, TabularFormat.EXCEL).close()
            fail("expected the open to fail")
        } catch (e: TableSourceException) {
            assertTrue(e.message.orEmpty().isNotBlank())
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains("xlsx", ignoreCase = true))
        }
    }

    @Test
    fun `sorting and filtering work on a workbook like on any other file`() {
        val file = workbook { sheet ->
            sheet.header("city", "population")
            listOf("Prague" to 1300000.0, "Brno" to 380000.0, "Ostrava" to 285000.0)
                .forEachIndexed { index, (city, population) ->
                    sheet.row(index + 1) { row ->
                        row.createCell(0).setCellValue(city)
                        row.createCell(1).setCellValue(population)
                    }
                }
        }

        TableSource.open(file, TabularFormat.EXCEL).use { source ->
            val sorted = source.fetchPage(Query().sortedBy("population", descending = true), 0, 10)
            assertEquals(listOf("Prague", "Brno", "Ostrava"), sorted.rows.map { it[0] })

            val filtered = Query().filteredBy(ColumnFilter.Contains("city", "ra"))
            assertEquals(2L, source.countRows(filtered))
            assertEquals(listOf("Prague", "Ostrava"), source.fetchPage(filtered, 0, 10).rows.map { it[0] })
        }
    }

    // --- fixtures -----------------------------------------------------------

    private fun workbook(build: (Sheet) -> Unit): Path {
        val file = temp.newFile("book-${counter++}.xlsx").toPath()
        XSSFWorkbook().use { book ->
            build(book.createSheet("Sheet1"))
            Files.newOutputStream(file).use(book::write)
        }
        return file
    }

    private fun Sheet.header(vararg names: String) = row(0) { row ->
        names.forEachIndexed { index, name -> row.createCell(index).setCellValue(name) }
    }

    private fun Sheet.row(index: Int, build: (Row) -> Unit) = build(createRow(index))

    private val Sheet.workbook: XSSFWorkbook get() = getWorkbook() as XSSFWorkbook

    private companion object {
        var counter = 0
        val UNUSED: CellStyle? = null
    }
}
