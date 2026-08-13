package com.kitworks.tablekit.data

/**
 * Where a result can be written. The engine does the writing, so this doubles
 * as format conversion: any supported file can be saved as any of these.
 */
enum class ExportFormat(
    val displayName: String,
    val extension: String,
    internal val copyOptions: String,
) {
    CSV("CSV", "csv", "FORMAT CSV, HEADER"),
    TSV("TSV", "tsv", "FORMAT CSV, HEADER, DELIMITER '\t'"),
    JSONL("JSON Lines", "jsonl", "FORMAT JSON"),
    PARQUET("Parquet", "parquet", "FORMAT PARQUET"),
    ;

    companion object {
        fun forExtension(extension: String?): ExportFormat? =
            extension?.lowercase()?.let { wanted -> values().firstOrNull { it.extension == wanted } }
    }
}
