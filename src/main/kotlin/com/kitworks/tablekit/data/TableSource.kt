package com.kitworks.tablekit.data

import com.kitworks.tablekit.format.TabularFormat
import java.io.Closeable
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

/** A data file could not be opened or queried. Carries a message fit for the UI. */
class TableSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One opened data file, queried in place.
 *
 * Nothing is imported and nothing is cached in the JVM: the file stays on disk
 * and every scroll, sort and filter turns into SQL against it. That is what
 * keeps a multi-gigabyte file from becoming a multi-gigabyte heap.
 *
 * Instances are NOT thread safe - a single background thread per open file owns
 * one, and the EDT never touches it.
 */
class TableSource private constructor(
    private val connection: Connection,
    val columns: List<ColumnInfo>,
    val rowCount: Long,
) : Closeable {

    /** Nested values are rendered by the engine, not by the JDBC driver's toString. */
    private val projection: String = columns.joinToString(", ") { column ->
        val quoted = Sql.identifier(column.name)
        if (column.nested) "CAST(to_json($quoted) AS VARCHAR) AS $quoted" else quoted
    }

    fun fetchPage(query: Query, offset: Long, limit: Int): DataPage {
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(limit > 0) { "limit must be positive: $limit" }

        val sql = "SELECT $projection FROM $VIEW_NAME${query.orderByClause()} LIMIT $limit OFFSET $offset"
        val rows = ArrayList<Array<String?>>(limit)
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    while (resultSet.next()) {
                        rows += Array(columns.size) { resultSet.getString(it + 1) }
                    }
                }
            }
        } catch (e: SQLException) {
            throw TableSourceException(readableMessage(e), e)
        }
        return DataPage(offset, rows)
    }

    override fun close() {
        connection.close()
    }

    companion object {
        private const val VIEW_NAME = "tablekit_source"

        fun open(path: Path, format: TabularFormat): TableSource {
            val connection = try {
                DuckDb.connect()
            } catch (e: SQLException) {
                throw TableSourceException("Could not start the query engine: ${e.message}", e)
            }

            return try {
                connection.createStatement().use { statement ->
                    statement.execute("CREATE OR REPLACE TEMP VIEW $VIEW_NAME AS SELECT * FROM ${relationOf(path, format)}")
                }
                TableSource(connection, describe(connection), countRows(connection))
            } catch (e: SQLException) {
                connection.closeQuietly()
                throw TableSourceException(readableMessage(e), e)
            } catch (e: Throwable) {
                connection.closeQuietly()
                throw e
            }
        }

        /** The engine reads these formats straight off disk. */
        internal fun relationOf(path: Path, format: TabularFormat): String {
            val literal = Sql.literal(path.toAbsolutePath().toString())
            return when (format) {
                TabularFormat.PARQUET -> "read_parquet($literal)"
                TabularFormat.CSV -> "read_csv_auto($literal)"
                TabularFormat.TSV -> "read_csv_auto($literal, delim='\t')"
                TabularFormat.JSONL -> "read_json_auto($literal, format='newline_delimited')"
                TabularFormat.EXCEL,
                TabularFormat.AVRO,
                TabularFormat.ORC,
                -> throw TableSourceException("${format.displayName} files are not supported yet.")
            }
        }

        private fun describe(connection: Connection): List<ColumnInfo> {
            val columns = mutableListOf<ColumnInfo>()
            connection.createStatement().use { statement ->
                statement.executeQuery("DESCRIBE $VIEW_NAME").use { resultSet ->
                    while (resultSet.next()) {
                        columns += ColumnInfo(
                            index = columns.size,
                            name = resultSet.getString("column_name"),
                            typeName = resultSet.getString("column_type"),
                            nullable = !"NO".equals(resultSet.getString("null"), ignoreCase = true),
                        )
                    }
                }
            }
            if (columns.isEmpty()) throw TableSourceException("The file contains no columns.")
            return columns
        }

        private fun countRows(connection: Connection): Long =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $VIEW_NAME").use { resultSet ->
                    if (resultSet.next()) resultSet.getLong(1) else 0L
                }
            }

        private fun Connection.closeQuietly() {
            try {
                close()
            } catch (ignored: SQLException) {
                // Nothing useful to do while unwinding a failed open.
            }
        }

        /**
         * Engine errors are the only thing a user sees when a file is corrupt, so
         * they must read like a sentence, not like a stack trace.
         */
        private fun readableMessage(e: SQLException): String {
            val raw = e.message?.trim().orEmpty()
            val stripped = raw.substringAfter("Error: ", raw).lineSequence().firstOrNull()?.trim().orEmpty()
            return stripped.ifEmpty { "The file could not be read." }
        }
    }
}
