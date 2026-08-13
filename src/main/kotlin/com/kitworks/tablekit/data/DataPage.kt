package com.kitworks.tablekit.data

/**
 * A window of rows starting at [offset]. Cells are pre-rendered to strings on
 * the query thread so the EDT only ever paints ready-made text.
 */
class DataPage(
    val offset: Long,
    val rows: List<Array<String?>>,
) {
    val size: Int get() = rows.size

    operator fun contains(rowIndex: Long): Boolean =
        rowIndex >= offset && rowIndex < offset + size

    fun cell(rowIndex: Long, columnIndex: Int): String? =
        rows[(rowIndex - offset).toInt()][columnIndex]

    companion object {
        val EMPTY = DataPage(0, emptyList())
    }
}
