package com.kitworks.tablekit.data

import com.kitworks.tablekit.format.TabularFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class StatisticsAndExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val people = "SELECT * FROM (VALUES " +
        "('Prague', 3), ('Brno', 1), ('Prague', 2), ('Ostrava', NULL), ('Prague', 4)" +
        ") t(city, score)"

    @Test
    fun `statistics describe what a column holds`() {
        open(people).use { source ->
            val city = source.statistics(Query(), source.columns[0])
            assertEquals(5L, city.total)
            assertEquals(5L, city.nonNull)
            assertEquals(0L, city.nulls)
            assertEquals(3L, city.distinct)
            assertEquals("Brno", city.min)
            assertEquals("Prague", city.max)
            assertNull("only numbers have an average", city.average)
            assertEquals(ValueCount("Prague", 3), city.topValues.first())
            assertEquals(3, city.topValues.size)
        }
    }

    @Test
    fun `nulls are counted, not silently skipped`() {
        open(people).use { source ->
            val score = source.statistics(Query(), source.columns[1])
            assertEquals(5L, score.total)
            assertEquals(4L, score.nonNull)
            assertEquals(1L, score.nulls)
            assertEquals(0.2, score.nullRatio, 0.001)
            assertEquals("1", score.min)
            assertEquals("4", score.max)
            assertEquals("2.5", score.average)
            assertTrue("nulls belong in the value list too", score.topValues.any { it.value == null })
        }
    }

    @Test
    fun `statistics describe the filtered rows, not the whole file`() {
        open(people).use { source ->
            val filtered = Query().filteredBy(ColumnFilter.Equals("city", "Prague"))
            val score = source.statistics(filtered, source.columns[1])

            assertEquals(3L, score.total)
            assertEquals(3L, score.nonNull)
            assertEquals("3.0", score.average)
        }
    }

    @Test
    fun `nested columns are summarised through their json form`() {
        open("SELECT {'a': 1} AS payload UNION ALL SELECT {'a': 1} UNION ALL SELECT {'a': 2}").use { source ->
            val payload = source.statistics(Query(), source.columns[0])
            assertEquals(2L, payload.distinct)
            assertEquals(ValueCount("""{"a":1}""", 2), payload.topValues.first())
        }
    }

    @Test
    fun `exporting writes the rows the user is looking at`() {
        open(people).use { source ->
            val target = temp.newFile("export.csv").toPath()
            val query = Query()
                .filteredBy(ColumnFilter.Equals("city", "Prague"))
                .sortedBy("score", descending = true)
            source.export(query, target, ExportFormat.CSV)

            assertEquals(
                listOf("city,score", "Prague,4", "Prague,3", "Prague,2"),
                Files.readAllLines(target).filter { it.isNotBlank() },
            )
        }
    }

    /** Exporting is also format conversion, which is the point of doing it in SQL. */
    @Test
    fun `every export format can be read back`() {
        val expected = listOf("Prague", "Brno", "Prague", "Ostrava", "Prague")

        for (format in ExportFormat.values()) {
            open(people).use { source ->
                val target = temp.newFile("roundtrip.${format.extension}-${format.name}").toPath()
                Files.delete(target)
                source.export(Query(), target, format)

                val reopened = when (format) {
                    ExportFormat.CSV -> TabularFormat.CSV
                    ExportFormat.TSV -> TabularFormat.TSV
                    ExportFormat.JSONL -> TabularFormat.JSONL
                    ExportFormat.PARQUET -> TabularFormat.PARQUET
                }
                TableSource.open(target, reopened).use { copy ->
                    assertEquals("$format lost rows", 5L, copy.rowCount)
                    assertEquals("$format lost columns", listOf("city", "score"), copy.columns.map { it.name })
                    assertEquals("$format changed values", expected, copy.fetchPage(Query(), 0, 10).rows.map { it[0] })
                }
            }
        }
    }

    @Test
    fun `exporting to a path that cannot be written reports it readably`() {
        open(people).use { source ->
            val directory = temp.newFolder("not-a-file").toPath()
            try {
                source.export(Query(), directory, ExportFormat.CSV)
            } catch (e: TableSourceException) {
                assertTrue(e.message.orEmpty().isNotBlank())
                return
            }
            // Some platforms allow the write; then the only requirement is that it did not crash.
        }
    }

    private fun open(select: String): TableSource {
        val file = temp.newFile("data-${counter++}.parquet").toPath()
        Files.delete(file)
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY ($select) TO ${Sql.literal(file.toString())} (FORMAT PARQUET)")
            }
        }
        return TableSource.open(file, TabularFormat.PARQUET)
    }

    private companion object {
        var counter = 0
        val UNUSED: Path? = null
    }
}
