package com.kitworks.tablekit.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.kitworks.tablekit.data.ColumnFilter
import com.kitworks.tablekit.data.ColumnInfo
import com.kitworks.tablekit.data.DataPage
import com.kitworks.tablekit.data.Query
import com.kitworks.tablekit.data.TableSource
import com.kitworks.tablekit.data.TableSourceException
import java.util.concurrent.ExecutorService
import javax.swing.table.AbstractTableModel

/**
 * The grid's window onto a file that may be far larger than memory.
 *
 * Only [MAX_CACHED_PAGES] pages of [PAGE_SIZE] rows are ever held, and a cell
 * that is not loaded yet returns null immediately and schedules a fetch - the
 * EDT never waits for the disk.
 *
 * All state lives on the EDT. The background executor only runs queries and
 * hands the result back through invokeLater, so there are no locks to get wrong.
 */
class TabularTableModel(
    private val source: TableSource,
    private val executor: ExecutorService,
    private val onError: (String) -> Unit,
    private val onRowCountChanged: (Long) -> Unit = {},
) : AbstractTableModel() {

    val columns: List<ColumnInfo> = source.columns

    var query: Query = Query()
        private set

    /** Rows the current filters leave; the full count while nothing is filtered. */
    var filteredRowCount: Long = source.rowCount
        private set

    /** Access-ordered so the eldest entry is the least recently used page. */
    private val pages = object : LinkedHashMap<Long, DataPage>(MAX_CACHED_PAGES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, DataPage>?): Boolean = size > MAX_CACHED_PAGES
    }
    private val pending = mutableSetOf<Long>()

    /** Bumped whenever the query changes; results from earlier generations are dropped. */
    private var generation = 0

    /** Swing addresses rows with an Int - not a limit any real file reaches today. */
    override fun getRowCount(): Int = filteredRowCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column].name

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): String? {
        val row = rowIndex.toLong()
        val page = pages[pageIndexOf(row)]
        if (page == null) {
            requestPage(pageIndexOf(row))
            return null
        }
        return page.cell(row, columnIndex)
    }

    /** True while the row's page is still being fetched, so the grid can say so. */
    fun isLoaded(rowIndex: Int): Boolean = pages.containsKey(pageIndexOf(rowIndex.toLong()))

    // --- what the user asks for ---------------------------------------------

    /** Cycles the column through ascending, descending and back to file order. */
    fun sortBy(columnIndex: Int) {
        val column = columns[columnIndex].name
        val current = query.sortKeyFor(column)
        applyQuery(
            when {
                current == null -> query.sortedBy(column, descending = false)
                !current.descending -> query.sortedBy(column, descending = true)
                else -> query.unsorted()
            },
        )
    }

    fun sortBy(columnIndex: Int, descending: Boolean) =
        applyQuery(query.sortedBy(columns[columnIndex].name, descending))

    fun clearSort() = applyQuery(query.unsorted())

    fun filterBy(filter: ColumnFilter) = applyQuery(query.filteredBy(filter))

    fun clearFilterOn(column: String) = applyQuery(query.withoutFilterOn(column))

    /** Free text across every column, the filter people reach for first. */
    fun filterByText(text: String) = applyQuery(
        if (text.isBlank()) {
            query.withoutFreeText()
        } else {
            query.filteredBy(ColumnFilter.AnyColumnContains(text, columns.map { it.name }))
        },
    )

    fun clearFilters() = applyQuery(query.unfiltered())

    // --- query plumbing -----------------------------------------------------

    private fun applyQuery(newQuery: Query) {
        if (newQuery == query) return

        val filtersChanged = newQuery.filters != query.filters
        query = newQuery
        generation++
        pages.clear()
        pending.clear()

        if (!filtersChanged) {
            fireTableDataChanged()
            return
        }

        // The row count is part of the answer now, and only the engine knows it.
        val requested = generation
        executor.execute {
            val counted = runCatching { source.countRows(newQuery) }
            invokeOnEdt {
                if (requested != generation) return@invokeOnEdt
                counted
                    .onSuccess {
                        filteredRowCount = it
                        fireTableDataChanged()
                        onRowCountChanged(it)
                    }
                    .onFailure { failure -> onError(messageOf(failure)) }
            }
        }
    }

    private fun pageIndexOf(row: Long): Long = row / PAGE_SIZE

    private fun requestPage(pageIndex: Long) {
        if (!pending.add(pageIndex)) return

        val requestedGeneration = generation
        val requestedQuery = query
        executor.execute {
            val result = runCatching { source.fetchPage(requestedQuery, pageIndex * PAGE_SIZE, PAGE_SIZE) }
            invokeOnEdt { deliver(pageIndex, requestedGeneration, result) }
        }
    }

    private fun deliver(pageIndex: Long, requestedGeneration: Int, result: Result<DataPage>) {
        if (requestedGeneration != generation) return
        pending.remove(pageIndex)

        result.onSuccess { page ->
            if (page.size == 0) return@onSuccess
            pages[pageIndex] = page
            val first = (pageIndex * PAGE_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val last = (first.toLong() + page.size - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            fireTableRowsUpdated(first, last)
        }.onFailure { failure -> onError(messageOf(failure)) }
    }

    private fun messageOf(failure: Throwable): String =
        (failure as? TableSourceException)?.message ?: "The file could not be read."

    private fun invokeOnEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any())

    companion object {
        /** One screen of rows is ~50; a page of 200 keeps scrolling ahead of the user. */
        const val PAGE_SIZE = 200

        /** 40 pages = 8000 rows in memory, whatever the file size. */
        private const val MAX_CACHED_PAGES = 40
    }
}
