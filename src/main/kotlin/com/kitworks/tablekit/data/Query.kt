package com.kitworks.tablekit.data

/** How a column is sorted. Sorting is pushed down to SQL, never done in the JVM. */
data class SortKey(val column: String, val descending: Boolean) {

    fun toSql(): String = Sql.identifier(column) + if (descending) " DESC" else " ASC"
}

/**
 * The user's current view of a file: which rows, in which order.
 *
 * Everything here becomes SQL, so the grid can stay ignorant of how many rows
 * the file actually has.
 */
data class Query(val sort: List<SortKey> = emptyList()) {

    fun orderByClause(): String =
        if (sort.isEmpty()) "" else " ORDER BY " + sort.joinToString(", ") { it.toSql() }

    fun sortedBy(column: String, descending: Boolean): Query = Query(listOf(SortKey(column, descending)))

    fun unsorted(): Query = Query()

    fun sortKeyFor(column: String): SortKey? = sort.firstOrNull { it.column == column }
}
