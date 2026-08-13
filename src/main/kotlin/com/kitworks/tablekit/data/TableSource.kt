package com.kitworks.tablekit.data

import com.kitworks.tablekit.data.xlsx.XlsxException
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
 * For Parquet, CSV, TSV and JSON Lines nothing is imported: the file stays on
 * disk and every scroll, sort and filter turns into SQL against it. That is
 * what keeps a multi-gigabyte file from becoming a multi-gigabyte heap.
 *
 * Instances are NOT thread safe - a single background thread per open file owns
 * one, and the EDT never touches it.
 */
class TableSource private constructor(
    private val connection: Connection,
    val columns: List<ColumnInfo>,
    val rowCount: Long,
    val sheets: List<String>,
    val sheetIndex: Int,
) : Closeable {

    /**
     * Every value is rendered to text by the engine rather than by the JDBC
     * driver. The driver would hand back java.sql.Timestamp and print
     * "14:30:00.0", and BigDecimal and byte arrays have their own surprises;
     * the engine prints values the way the format itself means them. Nested
     * values additionally become JSON on the way out.
     */
    private val projection: String = columns.joinToString(", ") { column ->
        val quoted = Sql.identifier(column.name)
        val value = if (column.nested) "to_json($quoted)" else quoted
        "CAST($value AS VARCHAR) AS $quoted"
    }

    fun fetchPage(query: Query, offset: Long, limit: Int): DataPage {
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(limit > 0) { "limit must be positive: $limit" }

        // Filter, sort and page over the typed columns first, render second.
        // Rendering in the same SELECT would make ORDER BY bind to the VARCHAR
        // alias and sort numbers as text.
        val page = "SELECT * FROM $VIEW_NAME${query.whereClause()}${query.orderByClause()}" +
            " LIMIT $limit OFFSET $offset"
        val sql = "SELECT $projection FROM ($page) AS page"
        val rows = ArrayList<Array<String?>>(limit)
        query(sql) { resultSet ->
            while (resultSet.next()) {
                rows += Array(columns.size) { resultSet.getString(it + 1) }
            }
        }
        return DataPage(offset, rows)
    }

    /** How many rows the current filters leave. Unfiltered, this is [rowCount]. */
    fun countRows(query: Query): Long {
        if (query.filters.isEmpty()) return rowCount
        var count = 0L
        query("SELECT count(*) FROM $VIEW_NAME${query.whereClause()}") { resultSet ->
            if (resultSet.next()) count = resultSet.getLong(1)
        }
        return count
    }

    private fun query(sql: String, read: (java.sql.ResultSet) -> Unit) {
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use(read)
            }
        } catch (e: SQLException) {
            throw TableSourceException(readableMessage(e), e)
        }
    }

    override fun close() {
        connection.close()
    }

    companion object {
        private const val VIEW_NAME = "tablekit_source"

        fun open(path: Path, format: TabularFormat, sheetIndex: Int = 0): TableSource {
            val connection = try {
                DuckDb.connect()
            } catch (e: SQLException) {
                throw TableSourceException("Could not start the query engine: ${e.message}", e)
            }

            return try {
                val sheets = if (format == TabularFormat.EXCEL) ExcelImporter.sheetNames(path) else emptyList()
                val relation = when (format) {
                    TabularFormat.EXCEL -> ExcelImporter.load(connection, path, sheetIndex)
                    else -> relationOf(path, format)
                }
                connection.createStatement().use { statement ->
                    statement.execute("CREATE OR REPLACE TEMP VIEW $VIEW_NAME AS SELECT * FROM $relation")
                }
                TableSource(connection, describe(connection), countAll(connection), sheets, sheetIndex)
            } catch (e: SQLException) {
                connection.closeQuietly()
                throw TableSourceException(readableMessage(e), e)
            } catch (e: XlsxException) {
                connection.closeQuietly()
                throw TableSourceException(e.message ?: "The workbook could not be read.", e)
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
                TabularFormat.TSV -> "read_csv_auto($literal, delim='\\t')"
                TabularFormat.JSONL -> "read_json_auto($literal, format='newline_delimited')"
                TabularFormat.EXCEL -> throw TableSourceException("Workbooks are loaded, not read in place.")
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

        private fun countAll(connection: Connection): Long =
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
