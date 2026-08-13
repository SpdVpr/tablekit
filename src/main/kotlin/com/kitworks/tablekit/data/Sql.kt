package com.kitworks.tablekit.data

/**
 * Minimal SQL quoting helpers.
 *
 * File paths and column names come from the file system and from file headers,
 * so they are attacker-controlled in the sense that matters here: a stray quote
 * must not be able to change the statement we execute.
 */
internal object Sql {

    /** Wraps a value in single quotes, escaping embedded ones: `a'b` -> `'a''b'`. */
    fun literal(value: String): String = "'" + value.replace("'", "''") + "'"

    /** Wraps an identifier in double quotes, escaping embedded ones. */
    fun identifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
