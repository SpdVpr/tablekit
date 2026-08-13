package com.kitworks.tablekit.filetype

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.kitworks.tablekit.format.TabularFormat
import com.kitworks.tablekit.icons.TableKitIcons
import javax.swing.Icon

/**
 * File types for the binary tabular formats. Registering them is our main free
 * distribution channel: an IDE that meets an unknown .parquet file offers the
 * plugin that declares the type (see PLAN.md).
 *
 * Text formats (CSV/TSV/JSONL) are intentionally NOT registered here - they
 * already have owners in the IDE and we only attach a secondary editor tab.
 */
abstract class TabularFileType(private val format: TabularFormat) : FileType {

    override fun getName(): String = "TableKit ${format.displayName}"

    override fun getDescription(): String = "${format.displayName} data file"

    override fun getDefaultExtension(): String = format.extensions.first()

    override fun getIcon(): Icon = TableKitIcons.forFormat(format)

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = false

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}

class ParquetFileType private constructor() : TabularFileType(TabularFormat.PARQUET) {
    companion object {
        @JvmField
        val INSTANCE = ParquetFileType()
    }
}

class ExcelFileType private constructor() : TabularFileType(TabularFormat.EXCEL) {
    companion object {
        @JvmField
        val INSTANCE = ExcelFileType()
    }
}

class AvroFileType private constructor() : TabularFileType(TabularFormat.AVRO) {
    companion object {
        @JvmField
        val INSTANCE = AvroFileType()
    }
}
