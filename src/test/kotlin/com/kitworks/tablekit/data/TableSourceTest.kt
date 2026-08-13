package com.kitworks.tablekit.data

import com.kitworks.tablekit.format.TabularFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TableSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `parquet file exposes its schema and row count`() {
        val file = write("people.parquet", "PARQUET", "SELECT * FROM (VALUES (1, 'Ada'), (2, 'Linus'), (3, NULL)) t(id, name)")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            assertEquals(3L, source.rowCount)
            assertEquals(listOf("id", "name"), source.columns.map { it.name })
            assertEquals("INTEGER", source.columns[0].typeName)
            assertEquals("VARCHAR", source.columns[1].typeName)
            assertFalse(source.columns[0].nested)
        }
    }

    @Test
    fun `pages are windows over the file, not copies of it`() {
        val file = write("numbers.parquet", "PARQUET", "SELECT i AS n FROM range(0, 1000) t(i)")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            assertEquals(1000L, source.rowCount)

            val first = source.fetchPage(Query(), offset = 0, limit = 3)
            assertEquals(listOf("0", "1", "2"), first.rows.map { it[0] })

            val deep = source.fetchPage(Query(), offset = 997, limit = 100)
            assertEquals(3, deep.size)
            assertEquals(listOf("997", "998", "999"), deep.rows.map { it[0] })
            assertTrue(999L in deep)
            assertFalse(996L in deep)
            assertEquals("998", deep.cell(998L, 0))
        }
    }

    @Test
    fun `sorting is done by the engine`() {
        val file = write("people.parquet", "PARQUET", "SELECT * FROM (VALUES (2, 'b'), (1, 'a'), (3, 'c')) t(id, name)")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            val descending = source.fetchPage(Query().sortedBy("id", descending = true), 0, 10)
            assertEquals(listOf("3", "2", "1"), descending.rows.map { it[0] })

            val ascending = source.fetchPage(Query().sortedBy("name", descending = false), 0, 10)
            assertEquals(listOf("a", "b", "c"), ascending.rows.map { it[1] })
        }
    }

    @Test
    fun `nested values are rendered as json instead of driver objects`() {
        val file = write(
            "nested.parquet",
            "PARQUET",
            "SELECT {'city': 'Prague', 'zip': 11000} AS address, [1, 2, 3] AS scores",
        )

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            assertTrue(source.columns.all { it.nested })

            val row = source.fetchPage(Query(), 0, 1).rows.single()
            assertEquals("""{"city":"Prague","zip":11000}""", row[0])
            assertEquals("[1,2,3]", row[1])
        }
    }

    @Test
    fun `sql nulls stay null and are not confused with empty text`() {
        val file = write("nulls.parquet", "PARQUET", "SELECT * FROM (VALUES ('', 1), (NULL, 2)) t(text, id)")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            val rows = source.fetchPage(Query().sortedBy("id", descending = false), 0, 10).rows
            assertEquals("", rows[0][0])
            assertNull(rows[1][0])
        }
    }

    @Test
    fun `column names that need quoting survive the round trip`() {
        val file = write("quoted.parquet", "PARQUET", """SELECT 1 AS "we""ird", 2 AS "with space"""")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            assertEquals(listOf("we\"ird", "with space"), source.columns.map { it.name })
            val sorted = source.fetchPage(Query().sortedBy("we\"ird", descending = true), 0, 10)
            assertEquals("1", sorted.rows.single()[0])
        }
    }

    @Test
    fun `csv is read with type detection`() {
        val file = temp.newFile("data.csv").toPath()
        Files.writeString(file, "id,name\n1,Ada\n2,Linus\n")

        TableSource.open(file, TabularFormat.CSV).use { source ->
            assertEquals(2L, source.rowCount)
            assertEquals(listOf("id", "name"), source.columns.map { it.name })
            assertEquals("BIGINT", source.columns[0].typeName)
            assertEquals(listOf("Ada", "Linus"), source.fetchPage(Query(), 0, 10).rows.map { it[1] })
        }
    }

    @Test
    fun `tsv is read with the tab delimiter`() {
        val file = temp.newFile("data.tsv").toPath()
        Files.writeString(file, "id\tname\n1\tAda, Countess\n")

        TableSource.open(file, TabularFormat.TSV).use { source ->
            assertEquals(listOf("id", "name"), source.columns.map { it.name })
            assertEquals("Ada, Countess", source.fetchPage(Query(), 0, 10).rows.single()[1])
        }
    }

    @Test
    fun `json lines are read one object per line`() {
        val file = temp.newFile("events.jsonl").toPath()
        Files.writeString(file, """{"id":1,"tag":"a"}""" + "\n" + """{"id":2,"tag":"b"}""" + "\n")

        TableSource.open(file, TabularFormat.JSONL).use { source ->
            assertEquals(2L, source.rowCount)
            assertEquals(listOf("id", "tag"), source.columns.map { it.name })
            assertEquals(listOf("a", "b"), source.fetchPage(Query(), 0, 10).rows.map { it[1] })
        }
    }

    /** A broken file must produce a sentence, never an exception balloon. */
    @Test
    fun `a corrupt file fails with a readable message`() {
        val file = temp.newFile("broken.parquet").toPath()
        Files.write(file, "this is definitely not parquet".toByteArray())

        try {
            TableSource.open(file, TabularFormat.PARQUET).close()
            fail("expected the open to fail")
        } catch (e: TableSourceException) {
            val message = e.message.orEmpty()
            assertTrue("message was: $message", message.isNotBlank())
            assertFalse("message leaks a stack trace: $message", message.contains("\n"))
        }
    }

    @Test
    fun `formats that are not implemented yet say so`() {
        for (format in listOf(TabularFormat.AVRO, TabularFormat.ORC)) {
            try {
                TableSource.relationOf(temp.newFile("x.$format").toPath(), format)
                fail("expected $format to be rejected")
            } catch (e: TableSourceException) {
                assertTrue(e.message.orEmpty().contains(format.displayName))
            }
        }
    }

    /** Values are rendered as text; the order they are sorted in must not be. */
    @Test
    fun `numbers sort as numbers`() {
        val file = write("nums.parquet", "PARQUET", "SELECT * FROM (VALUES (9), (10), (100)) t(n)")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            val ascending = source.fetchPage(Query().sortedBy("n", descending = false), 0, 10)
            assertEquals(listOf("9", "10", "100"), ascending.rows.map { it[0] })
        }
    }

    @Test
    fun `timestamps are rendered without the driver's trailing zeros`() {
        val file = write("times.parquet", "PARQUET", "SELECT TIMESTAMP '2026-08-13 14:30:00' AS at, DATE '2026-08-13' AS on_day")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            val row = source.fetchPage(Query(), 0, 1).rows.single()
            assertEquals("2026-08-13 14:30:00", row[0])
            assertEquals("2026-08-13", row[1])
        }
    }

    @Test
    fun `filters are counted and applied by the engine`() {
        val file = write("cities.parquet", "PARQUET", "SELECT * FROM (VALUES ('Prague', 1300000), ('Brno', 380000), ('Ostrava', NULL)) t(city, population)")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            val contains = Query().filteredBy(ColumnFilter.Contains("city", "ra"))
            assertEquals(2L, source.countRows(contains))
            assertEquals(listOf("Prague", "Ostrava"), source.fetchPage(contains, 0, 10).rows.map { it[0] })

            val nulls = Query().filteredBy(ColumnFilter.IsNull("population"))
            assertEquals(listOf("Ostrava"), source.fetchPage(nulls, 0, 10).rows.map { it[0] })

            val range = Query().filteredBy(ColumnFilter.Range("population", "300000", null))
            assertEquals(listOf("Prague", "Brno"), source.fetchPage(range, 0, 10).rows.map { it[0] })

            val anywhere = Query().filteredBy(ColumnFilter.AnyColumnContains("380", listOf("city", "population")))
            assertEquals(listOf("Brno"), source.fetchPage(anywhere, 0, 10).rows.map { it[0] })

            assertEquals("no filters means no counting query", source.rowCount, source.countRows(Query()))
        }
    }

    /** A percent sign a user types is a percent sign, not a LIKE wildcard. */
    @Test
    fun `filter text is matched literally`() {
        val file = write("codes.parquet", "PARQUET", "SELECT * FROM (VALUES ('100%'), ('100 percent'), ('a_b'), ('axb')) t(code)")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            val percent = Query().filteredBy(ColumnFilter.Contains("code", "100%"))
            assertEquals(listOf("100%"), source.fetchPage(percent, 0, 10).rows.map { it[0] })

            val underscore = Query().filteredBy(ColumnFilter.Contains("code", "a_b"))
            assertEquals(listOf("a_b"), source.fetchPage(underscore, 0, 10).rows.map { it[0] })
        }
    }

    /** Filter values reach SQL as literals, so a quote must not end the statement. */
    @Test
    fun `a quote in a filter value cannot break the query`() {
        val file = write("quotes.parquet", "PARQUET", "SELECT * FROM (VALUES ('it''s here'), ('nothing')) t(text)")

        TableSource.open(file, TabularFormat.PARQUET).use { source ->
            val filter = Query().filteredBy(ColumnFilter.Contains("text", "it's"))
            assertEquals(listOf("it's here"), source.fetchPage(filter, 0, 10).rows.map { it[0] })

            val hostile = Query().filteredBy(ColumnFilter.Equals("text", "'; DROP TABLE x; --"))
            assertEquals(0, source.fetchPage(hostile, 0, 10).size)
        }
    }

    /** Writes a data file with the engine itself, so tests need no binary fixtures. */
    private fun write(name: String, duckDbFormat: String, select: String): Path {
        val file = temp.newFile(name).toPath()
        Files.delete(file)
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY ($select) TO ${Sql.literal(file.toAbsolutePath().toString())} (FORMAT $duckDbFormat)")
            }
        }
        return file
    }
}
