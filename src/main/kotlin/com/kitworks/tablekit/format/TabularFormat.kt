package com.kitworks.tablekit.format

import com.intellij.openapi.vfs.VirtualFile

/**
 * Tabular file formats TableKit can open.
 *
 * [binary] separates the two distribution strategies described in CLAUDE.md:
 * binary formats have no usable built-in editor, so TableKit becomes their
 * default editor; text formats (CSV/TSV/JSONL) keep their existing editor and
 * TableKit only adds a secondary tab - we do not fight the CSV Editor plugin.
 */
enum class TabularFormat(
    val displayName: String,
    val binary: Boolean,
    val extensions: List<String>,
) {
    PARQUET("Parquet", binary = true, extensions = listOf("parquet", "parq", "pq")),
    EXCEL("Excel", binary = true, extensions = listOf("xlsx", "xlsm")),
    AVRO("Avro", binary = true, extensions = listOf("avro")),
    CSV("CSV", binary = false, extensions = listOf("csv")),
    TSV("TSV", binary = false, extensions = listOf("tsv", "tab")),
    JSONL("JSON Lines", binary = false, extensions = listOf("jsonl", "ndjson"));

    companion object {
        private val BY_EXTENSION: Map<String, TabularFormat> =
            values().flatMap { format -> format.extensions.map { it to format } }.toMap()

        fun byExtension(extension: String?): TabularFormat? =
            extension?.let { BY_EXTENSION[it.lowercase()] }

        fun of(file: VirtualFile): TabularFormat? = byExtension(file.extension)
    }
}
