package com.kitworks.tablekit.data

/**
 * Where a result can be written. The engine does the writing, so this doubles
 * as format conversion: any supported file can be saved as any of these.
 */
enum class ExportFormat(
    val displayName: String,
    val extension: String,
    internal val copyOptions: String,
    /** Text formats have no nested types, so struct and list columns become JSON. */
    internal val nestedAsJson: Boolean,
) {
    CSV("CSV", "csv", "FORMAT CSV, HEADER", nestedAsJson = true),
    TSV("TSV", "tsv", "FORMAT CSV, HEADER, DELIMITER '\t'", nestedAsJson = true),
    JSONL("JSON Lines", "jsonl", "FORMAT JSON", nestedAsJson = false),
    PARQUET("Parquet", "parquet", "FORMAT PARQUET", nestedAsJson = false),
    ;

    companion object {
        fun forExtension(extension: String?): ExportFormat? =
            extension?.lowercase()?.let { wanted -> values().firstOrNull { it.extension == wanted } }
    }
}
