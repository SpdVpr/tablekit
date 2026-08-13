package com.kitworks.tablekit.data

import com.kitworks.tablekit.data.avro.AvroException
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

    /**
     * What a column contains, under the filters currently in force.
     *
     * Two full scans, run only when the user asks for them: one aggregate pass
     * and one grouping pass for the most frequent values.
     */
    fun statistics(query: Query, column: ColumnInfo): ColumnStatistics {
        val expression = valueExpression(column)
        val average = if (column.numeric) ", CAST(avg($expression) AS VARCHAR)" else ""

        var statistics: ColumnStatistics? = null
        query(
            "SELECT count(*), count($expression), count(DISTINCT $expression)," +
                " CAST(min($expression) AS VARCHAR), CAST(max($expression) AS VARCHAR)$average" +
                " FROM $VIEW_NAME${query.whereClause()}",
        ) { resultSet ->
            if (resultSet.next()) {
                statistics = ColumnStatistics(
                    column = column.name,
                    total = resultSet.getLong(1),
                    nonNull = resultSet.getLong(2),
                    distinct = resultSet.getLong(3),
                    min = resultSet.getString(4),
                    max = resultSet.getString(5),
                    average = if (column.numeric) resultSet.getString(6) else null,
                    topValues = emptyList(),
                )
            }
        }

        val summary = statistics ?: throw TableSourceException("The column could not be summarised.")
        return summary.copy(
            topValues = topValues(query, expression),
            histogram = if (column.numeric || column.temporal) histogram(query, column) else null,
        )
    }

    /**
     * Buckets an ordered column into [HISTOGRAM_BINS] equal slices of its range.
     *
     * Times are counted as seconds since the epoch, which makes a column of
     * timestamps spread out the same way a column of prices does.
     */
    private fun histogram(query: Query, column: ColumnInfo): Histogram? {
        val quoted = Sql.identifier(column.name)
        val asNumber = if (column.temporal) "epoch($quoted)" else "CAST($quoted AS DOUBLE)"
        val counts = LongArray(HISTOGRAM_BINS)
        var any = false

        try {
            query(
                "WITH v AS (SELECT $asNumber AS x FROM $VIEW_NAME${query.whereClause()})," +
                    " b AS (SELECT min(x) AS lo, max(x) AS hi FROM v WHERE x IS NOT NULL)" +
                    " SELECT CASE WHEN b.hi = b.lo THEN 0 ELSE least(" +
                    "CAST(floor((v.x - b.lo) * $HISTOGRAM_BINS.0 / (b.hi - b.lo)) AS INTEGER), ${HISTOGRAM_BINS - 1}" +
                    ") END AS bin, count(*) AS n" +
                    " FROM v, b WHERE v.x IS NOT NULL GROUP BY 1 ORDER BY 1",
            ) { resultSet ->
                while (resultSet.next()) {
                    val bin = resultSet.getInt(1)
                    if (bin in counts.indices) {
                        counts[bin] = resultSet.getLong(2)
                        any = true
                    }
                }
            }
        } catch (ignored: TableSourceException) {
            // A type that will not become a number has no distribution; the rest
            // of the summary is still worth showing.
            return null
        }

        return if (any) Histogram(counts.toList()) else null
    }

    private fun topValues(query: Query, expression: String): List<ValueCount> {
        val values = mutableListOf<ValueCount>()
        query(
            "SELECT CAST($expression AS VARCHAR), count(*) AS occurrences FROM $VIEW_NAME${query.whereClause()}" +
                " GROUP BY 1 ORDER BY occurrences DESC, 1 LIMIT $TOP_VALUES",
        ) { resultSet ->
            while (resultSet.next()) {
                values += ValueCount(resultSet.getString(1), resultSet.getLong(2))
            }
        }
        return values
    }

    /**
     * Writes the rows the user is currently looking at - filters and sort
     * included, but every row, not just the page on screen - to a file.
     *
     * The engine does the writing, so exporting a gigabyte does not go through
     * the JVM heap, and converting between formats is the same operation.
     */
    fun export(query: Query, target: Path, format: ExportFormat) {
        val rows = "SELECT * FROM $VIEW_NAME${query.whereClause()}${query.orderByClause()}"
        val destination = Sql.literal(target.toAbsolutePath().toString())
        try {
            connection.createStatement().use { statement ->
                statement.execute("COPY ($rows) TO $destination (${format.copyOptions})")
            }
        } catch (e: SQLException) {
            throw TableSourceException(readableMessage(e), e)
        }
    }

    /** Nested columns are compared and grouped as their JSON form. */
    private fun valueExpression(column: ColumnInfo): String {
        val quoted = Sql.identifier(column.name)
        return if (column.nested) "CAST(to_json($quoted) AS VARCHAR)" else quoted
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
        private const val TOP_VALUES = 10
        private const val HISTOGRAM_BINS = 24

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
                    TabularFormat.AVRO -> AvroImporter.load(connection, path)
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
            } catch (e: AvroException) {
                connection.closeQuietly()
                throw TableSourceException(e.message ?: "The Avro file could not be read.", e)
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
                TabularFormat.EXCEL,
                TabularFormat.AVRO,
                -> throw TableSourceException("${format.displayName} files are loaded, not read in place.")
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
