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
data class Query(
    val sort: List<SortKey> = emptyList(),
    val filters: List<ColumnFilter> = emptyList(),
) {

    fun orderByClause(): String =
        if (sort.isEmpty()) "" else " ORDER BY " + sort.joinToString(", ") { it.toSql() }

    fun whereClause(): String =
        if (filters.isEmpty()) "" else " WHERE " + filters.joinToString(" AND ") { it.toSql() }

    // --- sorting ------------------------------------------------------------

    fun sortedBy(column: String, descending: Boolean): Query = copy(sort = listOf(SortKey(column, descending)))

    fun unsorted(): Query = copy(sort = emptyList())

    fun sortKeyFor(column: String): SortKey? = sort.firstOrNull { it.column == column }

    // --- filtering ----------------------------------------------------------

    /**
     * One filter per target at a time; a new one replaces it. The free text
     * filter names no column, so it replaces only itself.
     */
    fun filteredBy(filter: ColumnFilter): Query =
        copy(filters = filters.filterNot { it.column == filter.column } + filter)

    fun withoutFilterOn(column: String): Query = copy(filters = filters.filterNot { it.column == column })

    fun withoutFreeText(): Query = copy(filters = filters.filterNot { it is ColumnFilter.AnyColumnContains })

    fun unfiltered(): Query = copy(filters = emptyList())

    fun filterOn(column: String): ColumnFilter? =
        filters.firstOrNull { it.column == column && it !is ColumnFilter.AnyColumnContains }

    val freeText: String?
        get() = filters.filterIsInstance<ColumnFilter.AnyColumnContains>().firstOrNull()?.text
}
