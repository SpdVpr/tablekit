package com.kitworks.tablekit.data

/** One of the most frequent values in a column, with how often it occurs. */
data class ValueCount(val value: String?, val count: Long)

/**
 * How an ordered column spreads out: [counts] holds one bucket per equal slice
 * of the range between the column's smallest and largest value.
 *
 * A list of the most frequent values says nothing about a column of prices or
 * timestamps, where almost everything occurs once. The shape does.
 */
data class Histogram(val counts: List<Long>) {

    val highest: Long get() = counts.maxOrNull() ?: 0

    val isUseful: Boolean get() = counts.size > 1 && highest > 0
}

/**
 * What a column actually contains, computed on demand by the engine.
 *
 * This is the question every data viewer gets asked first - how many nulls, how
 * many distinct values, what is the range - and answering it by scrolling is
 * exactly the work TableKit exists to remove.
 */
data class ColumnStatistics(
    val column: String,
    val total: Long,
    val nonNull: Long,
    val distinct: Long,
    val min: String?,
    val max: String?,
    val average: String?,
    val topValues: List<ValueCount>,
    val histogram: Histogram? = null,
) {
    val nulls: Long get() = total - nonNull

    /** Share of rows that are null, 0.0 to 1.0. */
    val nullRatio: Double get() = if (total == 0L) 0.0 else nulls.toDouble() / total
}
