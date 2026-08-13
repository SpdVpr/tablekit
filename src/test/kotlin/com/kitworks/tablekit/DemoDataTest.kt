package com.kitworks.tablekit

import com.kitworks.tablekit.data.DuckDb
import com.kitworks.tablekit.data.Sql
import org.apache.avro.Schema
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate

/**
 * Writes the sample files the sandbox IDE opens for the Marketplace pictures.
 *
 * Run with:
 *
 *     ./gradlew test -Ptablekit.demo=true --tests '*DemoDataTest'
 *
 * Files land in demo/ - one per format, small enough to open instantly and
 * varied enough that the screenshots show real column types.
 */
class DemoDataTest {

    @Test
    fun `write the sample files`() {
        val directory = Paths.get("demo").toAbsolutePath()
        Files.createDirectories(directory)

        parquet(directory.resolve("orders.parquet"))
        workbook(directory.resolve("q3-report.xlsx"))
        avro(directory.resolve("events.avro"))
        csv(directory.resolve("cities.csv"))

        Files.list(directory).use { files ->
            files.forEach { println("[demo] $it (${Files.size(it) / 1024} kB)") }
        }
    }

    private fun parquet(target: Path) = write(
        target,
        """
        SELECT
            i AS order_id,
            'INV-2026-' || lpad(CAST(i AS VARCHAR), 5, '0') AS invoice,
            ['Ada Lovelace', 'Linus Torvalds', 'Grace Hopper', 'Alan Turing', 'Barbara Liskov'][(i % 5) + 1] AS customer,
            CASE WHEN i % 11 = 0 THEN NULL ELSE round(((i * 37) % 90000) / 7.0, 2) END AS amount,
            TIMESTAMP '2026-01-01 08:00:00' + INTERVAL (i * 3607) SECOND AS placed_at,
            {'city': ['Prague', 'Brno', 'Ostrava'][(i % 3) + 1],
             'street': 'Dlouha ' || (i % 90 + 1),
             'zip': 10000 + i} AS address,
            ['new', 'paid', 'shipped'][(i % 3) + 1] AS status,
            i % 7 = 0 AS express
        FROM range(0, 120000) t(i)
        """,
        "FORMAT PARQUET",
    )

    private fun csv(target: Path) = write(
        target,
        """
        SELECT * FROM (VALUES
            ('Prague', 1357326, 496.0), ('Brno', 400566, 230.2), ('Ostrava', 284765, 214.2),
            ('Plzen', 186683, 137.7), ('Liberec', 107624, 106.1), ('Olomouc', 102633, 103.4)
        ) t(city, population, area_km2)
        """,
        "FORMAT CSV, HEADER",
    )

    private fun write(target: Path, select: String, options: String) {
        Files.deleteIfExists(target)
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY (${select.trimIndent()}) TO ${Sql.literal(target.toString())} ($options)")
            }
        }
    }

    private fun workbook(target: Path) {
        XSSFWorkbook().use { book ->
            val dateStyle = book.createCellStyle().apply {
                dataFormat = book.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
            }
            listOf("Invoices", "Customers", "Notes").forEach { name ->
                val sheet = book.createSheet(name)
                sheet.createRow(0).let { header ->
                    listOf("invoice", "customer", "issued", "amount", "paid")
                        .forEachIndexed { index, title -> header.createCell(index).setCellValue(title) }
                }
                for (index in 1..400) {
                    val row = sheet.createRow(index)
                    row.createCell(0).setCellValue("INV-2026-%04d".format(index))
                    row.createCell(1).setCellValue(CUSTOMERS[index % CUSTOMERS.size])
                    row.createCell(2).apply {
                        setCellValue(LocalDate.of(2026, 7, 1).plusDays((index % 90).toLong()).atStartOfDay())
                        cellStyle = dateStyle
                    }
                    row.createCell(3).setCellValue((index * 1375) % 90000 / 10.0)
                    row.createCell(4).setCellValue(index % 3 != 0)
                }
            }
            Files.newOutputStream(target).use(book::write)
        }
    }

    private fun avro(target: Path) {
        val schema = Schema.Parser().parse(
            """
            {"type":"record","name":"Event","fields":[
              {"name":"event_id","type":"long"},
              {"name":"kind","type":{"type":"enum","name":"Kind","symbols":["CLICK","VIEW","PURCHASE"]}},
              {"name":"at","type":{"type":"long","logicalType":"timestamp-millis"}},
              {"name":"day","type":{"type":"int","logicalType":"date"}},
              {"name":"user","type":{"type":"record","name":"User","fields":[
                 {"name":"id","type":"long"},{"name":"country","type":"string"}]}},
              {"name":"tags","type":{"type":"array","items":"string"}},
              {"name":"note","type":["null","string"]}
            ]}
            """.trimIndent(),
        )
        val kind = schema.getField("kind").schema()
        val user = schema.getField("user").schema()

        Files.deleteIfExists(target)
        DataFileWriter(GenericDatumWriter<GenericRecord>(schema)).use { writer ->
            writer.create(schema, Files.newOutputStream(target))
            for (index in 0 until 20000) {
                writer.append(
                    GenericData.Record(schema).apply {
                        put("event_id", index.toLong())
                        put("kind", GenericData.EnumSymbol(kind, listOf("CLICK", "VIEW", "PURCHASE")[index % 3]))
                        put("at", 1767225600000L + index * 37_000L)
                        put("day", 20454 + index % 90)
                        put(
                            "user",
                            GenericData.Record(user).apply {
                                put("id", (index % 900).toLong())
                                put("country", listOf("CZ", "DE", "PL", "AT")[index % 4])
                            },
                        )
                        put("tags", listOf("web", "eu").take(1 + index % 2))
                        put("note", if (index % 5 == 0) null else "seen on page ${index % 30}")
                    },
                )
            }
        }
    }

    companion object {
        private val CUSTOMERS = listOf("Ada Lovelace", "Linus Torvalds", "Grace Hopper", "Alan Turing", "Barbara Liskov")

        @BeforeClass
        @JvmStatic
        fun requireOptIn() {
            assumeTrue("set -Ptablekit.demo=true to write the sample files", System.getProperty("tablekit.demo") != null)
        }
    }
}
