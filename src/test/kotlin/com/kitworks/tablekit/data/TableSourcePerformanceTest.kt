package com.kitworks.tablekit.data

import com.kitworks.tablekit.format.TabularFormat
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
 * The performance targets from CLAUDE.md are requirements, not wishes, so they
 * are asserted rather than eyeballed.
 *
 * Skipped by default because it generates a gigabyte of test data. Run it with:
 *
 *     ./gradlew test -Ptablekit.perf=true --tests '*PerformanceTest'
 *
 * The generated file is kept between runs; delete testData/generated to redo it.
 */
class TableSourcePerformanceTest {

    @Test
    fun `opening a gigabyte of parquet takes under two seconds`() {
        val file = bigParquet()

        var source: TableSource? = null
        val millis = measureTimeMillis { source = TableSource.open(file, TabularFormat.PARQUET) }
        source.use { opened ->
            report("open (schema + row count)", millis)
            assertEquals(ROWS, opened!!.rowCount)
            assertTrue("opening took ${millis}ms, budget is ${OPEN_BUDGET_MS}ms", millis < OPEN_BUDGET_MS)
        }
    }

    @Test
    fun `scrolling stays interactive at any depth`() {
        TableSource.open(bigParquet(), TabularFormat.PARQUET).use { source ->
            val first = measureTimeMillis { source.fetchPage(Query(), 0, PAGE) }
            report("first page", first)
            assertTrue("first page took ${first}ms", first < PAGE_BUDGET_MS)

            val middle = measureTimeMillis { source.fetchPage(Query(), source.rowCount / 2, PAGE) }
            report("page at 50% depth", middle)

            val last = measureTimeMillis { source.fetchPage(Query(), source.rowCount - PAGE, PAGE) }
            report("page at the end", last)
            assertTrue("the last page took ${last}ms", last < DEEP_PAGE_BUDGET_MS)
        }
    }

    @Test
    fun `sorting a gigabyte does not need a gigabyte of heap`() {
        TableSource.open(bigParquet(), TabularFormat.PARQUET).use { source ->
            val sorted = Query().sortedBy("token", descending = true)

            val millis = measureTimeMillis { source.fetchPage(sorted, 0, PAGE) }
            report("first page of a full sort", millis)

            val heapMb = usedHeapMb()
            report("heap after sorting", heapMb.toLong(), unit = "MB")
            assertTrue("heap grew to ${heapMb}MB, budget is ${HEAP_BUDGET_MB}MB", heapMb < HEAP_BUDGET_MB)
        }
    }

    private fun usedHeapMb(): Int {
        System.gc()
        Thread.sleep(200)
        val runtime = Runtime.getRuntime()
        return ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024).toInt()
    }

    private fun report(what: String, value: Long, unit: String = "ms") {
        println("[perf] %-32s %6d %s".format(what, value, unit))
    }

    companion object {
        private const val ROWS = 25_000_000L
        private const val PAGE = 200
        private const val OPEN_BUDGET_MS = 2_000
        private const val PAGE_BUDGET_MS = 250
        private const val DEEP_PAGE_BUDGET_MS = 3_000
        private const val HEAP_BUDGET_MB = 300

        @BeforeClass
        @JvmStatic
        fun requireOptIn() {
            assumeTrue("set -Ptablekit.perf=true to run performance tests", System.getProperty("tablekit.perf") != null)
        }

        /** Generated once and reused; regenerating a gigabyte per test would be silly. */
        private fun bigParquet(): Path {
            val directory = Paths.get("testData", "generated").toAbsolutePath()
            Files.createDirectories(directory)
            val file = directory.resolve("big-${ROWS}.parquet")

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
                                        TIMESTAMP '2020-01-01 00:00:00' + INTERVAL (i % 2592000) SECOND AS created_at,
                                        ['red', 'green', 'blue'][(i % 3) + 1] AS category,
                                        md5(CAST(i AS VARCHAR)) AS token
                                    FROM range(0, $ROWS) t(i)
                                ) TO ${Sql.literal(file.toString())} (FORMAT PARQUET)
                                """.trimIndent(),
                            )
                        }
                    }
                }
                println("[perf] generated ${Files.size(file) / 1024 / 1024} MB in ${millis}ms")
            }
            println("[perf] file size: ${Files.size(file) / 1024 / 1024} MB")
            return file
        }
    }
}
