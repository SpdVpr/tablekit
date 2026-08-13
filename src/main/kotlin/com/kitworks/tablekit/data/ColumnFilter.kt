package com.kitworks.tablekit.data

/**
 * A condition on one column, translated to SQL and pushed down to the engine.
 *
 * Values come from the user, so every one of them goes through [Sql.literal];
 * nothing here builds SQL by string concatenation of raw input.
 */
sealed class ColumnFilter {

    abstract val column: String

    abstract fun toSql(): String

    /** Human readable form for the filter bar. */
    abstract fun describe(): String

    protected fun quoted(): String = Sql.identifier(column)

    protected fun asText(): String = "CAST(${quoted()} AS VARCHAR)"

    data class Contains(override val column: String, val text: String) : ColumnFilter() {
        override fun toSql(): String = "${asText()} ILIKE ${Sql.literal("%" + escapeLike(text) + "%")} ESCAPE '\\'"
        override fun describe(): String = "$column contains \"$text\""
    }

    data class Equals(override val column: String, val text: String) : ColumnFilter() {
        override fun toSql(): String = "${asText()} = ${Sql.literal(text)}"
        override fun describe(): String = "$column = \"$text\""
    }

    data class IsNull(override val column: String) : ColumnFilter() {
        override fun toSql(): String = "${quoted()} IS NULL"
        override fun describe(): String = "$column is null"
    }

    data class IsNotNull(override val column: String) : ColumnFilter() {
        override fun toSql(): String = "${quoted()} IS NOT NULL"
        override fun describe(): String = "$column is not null"
    }

    /**
     * Inclusive range. The engine casts the literals to the column type, so this
     * works for numbers, dates and text alike.
     */
    data class Range(override val column: String, val from: String?, val to: String?) : ColumnFilter() {
        override fun toSql(): String = listOfNotNull(
            from?.let { "${quoted()} >= ${Sql.literal(it)}" },
            to?.let { "${quoted()} <= ${Sql.literal(it)}" },
        ).ifEmpty { listOf("TRUE") }.joinToString(" AND ", prefix = "(", postfix = ")")

        override fun describe(): String = when {
            from != null && to != null -> "$column in $from..$to"
            from != null -> "$column >= $from"
            to != null -> "$column <= $to"
            else -> "$column (any)"
        }
    }

    /** Free text across every column at once - the filter people reach for first. */
    data class AnyColumnContains(val text: String, val columns: List<String>) : ColumnFilter() {
        override val column: String get() = ""

        override fun toSql(): String {
            if (columns.isEmpty()) return "TRUE"
            val pattern = Sql.literal("%" + escapeLike(text) + "%")
            return columns.joinToString(" OR ", prefix = "(", postfix = ")") { name ->
                "CAST(${Sql.identifier(name)} AS VARCHAR) ILIKE $pattern ESCAPE '\\'"
            }
        }

        override fun describe(): String = "contains \"$text\""
    }

    companion object {
        /** LIKE wildcards typed by a user are literal text, not a pattern language. */
        internal fun escapeLike(text: String): String = buildString(text.length) {
            for (character in text) {
                if (character == '\\' || character == '%' || character == '_') append('\\')
                append(character)
            }
        }
    }
}
