package com.kitworks.tablekit.data

import org.duckdb.DuckDBDriver
import java.sql.Connection
import java.util.Properties

/**
 * Owns how TableKit talks to its embedded query engine.
 *
 * The driver is instantiated directly instead of going through `DriverManager`:
 * inside an IDE every plugin has its own class loader, and JDBC's global driver
 * registry is not something a plugin should be reaching into.
 */
object DuckDb {

    /** In-memory database. Data files are read in place, nothing is imported. */
    fun connect(): Connection = DuckDBDriver().connect(JDBC_URL, Properties()).apply {
        createStatement().use { statement ->
            SESSION_SETUP.forEach(statement::execute)
        }
    }

    private const val JDBC_URL = "jdbc:duckdb:"

    private val SESSION_SETUP = listOf(
        // TableKit promises zero network activity. DuckDB would happily fetch an
        // extension over HTTP the first time a query needs one; it must not.
        "SET autoinstall_known_extensions=false",
        "SET autoload_known_extensions=false",
        // We are a guest in someone's IDE: leave them cores to compile with, and
        // spill to disk rather than fight the JVM for the last gigabyte.
        "SET threads=${(Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)}",
        "SET memory_limit='1GB'",
    )
}
