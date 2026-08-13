package com.kitworks.tablekit.data

import com.kitworks.tablekit.format.TabularFormat
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.measureTimeMillis

/**
 * Parquet is the format we measured first because it is the headline one, but
 * a viewer is judged by its worst format. CSV is re-parsed on every query and a
 * workbook is loaded whole, so both deserve their own numbers.
 *
 * Skipped by default; run with:
 *
 *     ./gradlew test -Ptablekit.perf=true --tests '*FormatPerformanceTest'
 */
class FormatPerformanceTest {

    @Test
    fun `a gigabyte of csv opens and scrolls`() {
        val file = bigCsv()

        var source: TableSource? = null
        val open = measureTimeMillis { source = TableSource.open(file, TabularFormat.CSV) }
        source.use { opened ->
            report("csv open (sniff + row count)", open)
            assertEquals(CSV_ROWS, opened!!.rowCount)

            val first = measureTimeMillis { opened.fetchPage(Query(), 0, PAGE) }
            report("csv first page", first)

            val last = measureTimeMillis { opened.fetchPage(Query(), opened.rowCount - PAGE, PAGE) }
            report("csv page at the end", last)

            val filtered = Query().filteredBy(ColumnFilter.Contains("token", "abc"))
            val count = measureTimeMillis { opened.countRows(filtered) }
            report("csv filtered count", count)

            // The engine re-reads the file for every one of these, so the budget
            // is a whole scan rather than a page fetch.
            assertTrue("opening took ${open}ms", open < CSV_OPEN_BUDGET_MS)
            assertTrue("the last page took ${last}ms", last < CSV_SCAN_BUDGET_MS)
        }
    }

    @Test
    fun `a large workbook loads once and is then instant`() {
        val file = bigXlsx()
        report("xlsx size", Files.size(file) / 1024 / 1024, unit = "MB")

        var source: TableSource? = null
        val open = measureTimeMillis { source = TableSource.open(file, TabularFormat.EXCEL) }
        source.use { opened ->
            report("xlsx open (two passes + load)", open)
            assertEquals(XLSX_ROWS.toLong(), opened!!.rowCount)

            val first = measureTimeMillis { opened.fetchPage(Query(), 0, PAGE) }
            report("xlsx first page", first)

            val sorted = measureTimeMillis { opened.fetchPage(Query().sortedBy("token", true), 0, PAGE) }
            report("xlsx sorted page", sorted)

            assertTrue("opening took ${open}ms", open < XLSX_OPEN_BUDGET_MS)
            assertTrue("a page took ${first}ms", first < PAGE_BUDGET_MS)
            assertTrue("a sorted page took ${sorted}ms", sorted < PAGE_BUDGET_MS)
        }
    }

    private fun report(what: String, value: Long, unit: String = "ms") {
        println("[perf] %-32s %6d %s".format(what, value, unit))
    }

    companion object {
        private const val PAGE = 200
        private const val CSV_ROWS = 12_000_000L
        private const val XLSX_ROWS = 200_000
        private const val CSV_OPEN_BUDGET_MS = 20_000
        private const val CSV_SCAN_BUDGET_MS = 20_000
        private const val XLSX_OPEN_BUDGET_MS = 6_000
        private const val PAGE_BUDGET_MS = 1_000

        @BeforeClass
        @JvmStatic
        fun requireOptIn() {
            assumeTrue("set -Ptablekit.perf=true to run performance tests", System.getProperty("tablekit.perf") != null)
        }

        private fun directory(): Path = Paths.get("testData", "generated").toAbsolutePath()
            .also(Files::createDirectories)

        private fun bigCsv(): Path {
            val file = directory().resolve("big-$CSV_ROWS.csv")
            if (!Files.exists(file)) {
                println("[perf] generating $file ...")
                val millis = measureTimeMillis {
                    DuckDb.connect().use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute(
                                """
                                COPY (
                                    SELECT
                                        i AS id,
                                        'user_' || (i % 100000) AS user_name,
                                        ((i * 7919) % 100000) / 7.0 AS score,
                                        md5(CAST(i AS VARCHAR)) AS token
                                    FROM range(0, $CSV_ROWS) t(i)
                                ) TO ${Sql.literal(file.toString())} (FORMAT CSV, HEADER)
                                """.trimIndent(),
                            )
                        }
                    }
                }
                println("[perf] generated ${Files.size(file) / 1024 / 1024} MB in ${millis}ms")
            }
            println("[perf] csv size: ${Files.size(file) / 1024 / 1024} MB")
            return file
        }

        private fun bigXlsx(): Path {
            val file = directory().resolve("big-$XLSX_ROWS.xlsx")
            if (!Files.exists(file)) {
                println("[perf] generating $file ...")
                val millis = measureTimeMillis {
                    // Streaming writer: a workbook this size does not fit in POI's
                    // in-memory model, and it is a test fixture, not our code.
                    SXSSFWorkbook(100).use { book ->
                        val sheet = book.createSheet("data")
                        val dateStyle = book.createCellStyle().apply {
                            dataFormat = book.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
                        }
                        sheet.createRow(0).let { header ->
                            listOf("id", "user_name", "score", "issued", "token", "paid")
                                .forEachIndexed { index, name -> header.createCell(index).setCellValue(name) }
                        }
                        for (index in 1..XLSX_ROWS) {
                            val row = sheet.createRow(index)
                            row.createCell(0).setCellValue(index.toDouble())
                            row.createCell(1).setCellValue("user_" + index % 5000)
                            row.createCell(2).setCellValue((index * 7919 % 100000) / 7.0)
                            row.createCell(3).apply {
                                setCellValue(java.time.LocalDate.of(2020, 1, 1).plusDays((index % 2000).toLong()).atStartOfDay())
                                cellStyle = dateStyle
                            }
                            row.createCell(4).setCellValue(Integer.toHexString(index * 2654435761L.toInt()))
                            row.createCell(5).setCellValue(index % 3 != 0)
                        }
                        Files.newOutputStream(file).use(book::write)
                        book.dispose()
                    }
                }
                println("[perf] generated ${Files.size(file) / 1024 / 1024} MB in ${millis}ms")
            }
            return file
        }
    }
}
