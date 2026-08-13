package com.kitworks.tablekit.data

import com.kitworks.tablekit.data.avro.AvroFile
import org.duckdb.DuckDBConnection
import java.nio.file.Path
import java.sql.Connection

/**
 * Loads an Avro file into the query engine.
 *
 * Unlike a spreadsheet, an Avro file states its own schema, so there is nothing
 * to infer: one pass, and the column types come from the file. Values are still
 * staged as text and cast in the view, which keeps the loading loop uniform and
 * lets the engine do the parsing.
 */
object AvroImporter {

    private const val STAGING_TABLE = "tablekit_avro"

    fun load(connection: Connection, path: Path): String {
        AvroFile.open(path).use { file ->
            val columns = file.columns
            connection.createStatement().use { statement ->
                val declarations = columns.indices.joinToString(", ") { "${stagingColumn(it)} VARCHAR" }
                statement.execute("CREATE OR REPLACE TABLE $STAGING_TABLE ($declarations)")
            }

            val duckDb = connection.unwrap(DuckDBConnection::class.java)
            duckDb.createAppender(DuckDBConnection.DEFAULT_SCHEMA, STAGING_TABLE).use { appender ->
                file.readRows { row ->
                    appender.beginRow()
                    for (value in row) {
                        if (value == null) appender.appendNull() else appender.append(value)
                    }
                    appender.endRow()
                }
                appender.flush()
            }

            val projection = columns.mapIndexed { index, column ->
                val source = stagingColumn(index)
                val expression =
                    if (column.duckDbType == "VARCHAR") source else "TRY_CAST($source AS ${column.duckDbType})"
                "$expression AS ${Sql.identifier(column.name)}"
            }.joinToString(", ")

            return "(SELECT $projection FROM $STAGING_TABLE)"
        }
    }

    private fun stagingColumn(index: Int): String = "c$index"
}
