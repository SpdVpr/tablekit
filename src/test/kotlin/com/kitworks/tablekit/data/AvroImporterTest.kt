package com.kitworks.tablekit.data

import com.kitworks.tablekit.format.TabularFormat
import org.apache.avro.Schema
import org.apache.avro.file.CodecFactory
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Avro reader is ours, so it is checked against files written by Apache
 * Avro itself - the reference implementation, used here as a test fixture and
 * never shipped.
 */
class AvroImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `primitives keep their types`() {
        val file = write(
            """
            {"type":"record","name":"Row","fields":[
              {"name":"id","type":"long"},
              {"name":"name","type":"string"},
              {"name":"score","type":"double"},
              {"name":"active","type":"boolean"},
              {"name":"count","type":"int"}
            ]}
            """,
        ) { schema ->
            listOf(
                record(schema, "id" to 1L, "name" to "Ada", "score" to 9.5, "active" to true, "count" to 3),
                record(schema, "id" to 2L, "name" to "Linus", "score" to 7.25, "active" to false, "count" to 4),
            )
        }

        TableSource.open(file, TabularFormat.AVRO).use { source ->
            assertEquals(listOf("id", "name", "score", "active", "count"), source.columns.map { it.name })
            assertEquals(listOf("BIGINT", "VARCHAR", "DOUBLE", "BOOLEAN", "BIGINT"), source.columns.map { it.typeName })
            assertEquals(2L, source.rowCount)

            val rows = source.fetchPage(Query(), 0, 10).rows
            assertEquals(listOf("1", "Ada", "9.5", "true", "3"), rows[0].toList())
            assertEquals(listOf("2", "Linus", "7.25", "false", "4"), rows[1].toList())
        }
    }

    @Test
    fun `a nullable union is just a nullable column`() {
        val file = write(
            """
            {"type":"record","name":"Row","fields":[
              {"name":"note","type":["null","string"]},
              {"name":"size","type":["null","long"]}
            ]}
            """,
        ) { schema ->
            listOf(
                record(schema, "note" to "here", "size" to 10L),
                record(schema, "note" to null, "size" to null),
            )
        }

        TableSource.open(file, TabularFormat.AVRO).use { source ->
            assertEquals(listOf("VARCHAR", "BIGINT"), source.columns.map { it.typeName })
            val rows = source.fetchPage(Query(), 0, 10).rows
            assertEquals("here", rows[0][0])
            assertNull(rows[1][0])
            assertNull(rows[1][1])
        }
    }

    @Test
    fun `dates, timestamps and decimals arrive as themselves`() {
        val file = write(
            """
            {"type":"record","name":"Row","fields":[
              {"name":"day","type":{"type":"int","logicalType":"date"}},
              {"name":"at","type":{"type":"long","logicalType":"timestamp-millis"}},
              {"name":"amount","type":{"type":"bytes","logicalType":"decimal","precision":10,"scale":2}}
            ]}
            """,
        ) { schema ->
            listOf(
                record(
                    schema,
                    "day" to 20313, // 2025-08-13
                    "at" to 1786000000000L,
                    "amount" to ByteBuffer.wrap(java.math.BigInteger.valueOf(123456).toByteArray()),
                ),
            )
        }

        TableSource.open(file, TabularFormat.AVRO).use { source ->
            assertEquals(listOf("DATE", "TIMESTAMP", "DECIMAL(10,2)"), source.columns.map { it.typeName })
            val row = source.fetchPage(Query(), 0, 1).rows.single()
            assertEquals("2025-08-13", row[0])
            assertTrue("timestamp was ${row[1]}", row[1].orEmpty().startsWith("2026-"))
            assertEquals("1234.56", row[2])
        }
    }

    @Test
    fun `records, arrays and maps are rendered as json`() {
        val file = write(
            """
            {"type":"record","name":"Row","fields":[
              {"name":"address","type":{"type":"record","name":"Address","fields":[
                 {"name":"city","type":"string"},{"name":"zip","type":"int"}]}},
              {"name":"tags","type":{"type":"array","items":"string"}},
              {"name":"counts","type":{"type":"map","values":"long"}}
            ]}
            """,
        ) { schema ->
            val address = GenericData.Record(schema.getField("address").schema()).apply {
                put("city", "Prague")
                put("zip", 11000)
            }
            listOf(
                record(
                    schema,
                    "address" to address,
                    "tags" to listOf("a", "b"),
                    "counts" to mapOf("x" to 1L),
                ),
            )
        }

        TableSource.open(file, TabularFormat.AVRO).use { source ->
            val row = source.fetchPage(Query(), 0, 1).rows.single()
            assertEquals("""{"city":"Prague","zip":11000}""", row[0])
            assertEquals("""["a","b"]""", row[1])
            assertEquals("""{"x":1}""", row[2])
        }
    }

    @Test
    fun `an enum arrives as its symbol`() {
        val file = write(
            """
            {"type":"record","name":"Row","fields":[
              {"name":"status","type":{"type":"enum","name":"Status","symbols":["NEW","PAID","SHIPPED"]}}
            ]}
            """,
        ) { schema ->
            val status = schema.getField("status").schema()
            listOf(
                record(schema, "status" to GenericData.EnumSymbol(status, "PAID")),
                record(schema, "status" to GenericData.EnumSymbol(status, "NEW")),
            )
        }

        TableSource.open(file, TabularFormat.AVRO).use { source ->
            assertEquals(listOf("PAID", "NEW"), source.fetchPage(Query(), 0, 10).rows.map { it[0] })
        }
    }

    /** Deflate is the codec people actually meet, and the JDK can undo it. */
    @Test
    fun `a deflate compressed file reads back`() {
        val file = write(
            """{"type":"record","name":"Row","fields":[{"name":"n","type":"long"}]}""",
            codec = CodecFactory.deflateCodec(6),
        ) { schema -> (1..5000L).map { record(schema, "n" to it) } }

        TableSource.open(file, TabularFormat.AVRO).use { source ->
            assertEquals(5000L, source.rowCount)
            assertEquals("5000", source.fetchPage(Query().sortedBy("n", true), 0, 1).rows.single()[0])
        }
    }

    @Test
    fun `sorting and filtering work like on any other file`() {
        val file = write(
            """
            {"type":"record","name":"Row","fields":[
              {"name":"city","type":"string"},{"name":"population","type":"long"}]}
            """,
        ) { schema ->
            listOf(
                record(schema, "city" to "Prague", "population" to 1300000L),
                record(schema, "city" to "Brno", "population" to 380000L),
                record(schema, "city" to "Ostrava", "population" to 285000L),
            )
        }

        TableSource.open(file, TabularFormat.AVRO).use { source ->
            val sorted = source.fetchPage(Query().sortedBy("population", descending = true), 0, 10)
            assertEquals(listOf("Prague", "Brno", "Ostrava"), sorted.rows.map { it[0] })

            val filtered = Query().filteredBy(ColumnFilter.Contains("city", "ra"))
            assertEquals(2L, source.countRows(filtered))
        }
    }

    @Test
    fun `a file that is not avro fails with a readable message`() {
        val file = temp.newFile("broken.avro").toPath()
        Files.write(file, "not an avro file".toByteArray())

        try {
            TableSource.open(file, TabularFormat.AVRO).close()
            fail("expected the open to fail")
        } catch (e: TableSourceException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains("Avro", ignoreCase = true))
            assertFalse("message leaks a stack trace", e.message.orEmpty().contains("\n"))
        }
    }

    private fun assertFalse(message: String, condition: Boolean) = assertTrue(message, !condition)

    // --- fixtures -----------------------------------------------------------

    private fun record(schema: Schema, vararg values: Pair<String, Any?>): GenericRecord =
        GenericData.Record(schema).apply { values.forEach { (name, value) -> put(name, value) } }

    private fun write(
        schemaJson: String,
        codec: CodecFactory? = null,
        rows: (Schema) -> List<GenericRecord>,
    ): Path {
        val schema = Schema.Parser().parse(schemaJson.trimIndent())
        val file = temp.newFile("data-${counter++}.avro").toPath()
        DataFileWriter(GenericDatumWriter<GenericRecord>(schema)).use { writer ->
            codec?.let(writer::setCodec)
            writer.create(schema, Files.newOutputStream(file))
            rows(schema).forEach(writer::append)
        }
        return file
    }

    private companion object {
        var counter = 0
    }
}
