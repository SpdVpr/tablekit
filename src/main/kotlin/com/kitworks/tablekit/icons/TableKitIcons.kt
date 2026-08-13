package com.kitworks.tablekit.icons

import com.intellij.openapi.util.IconLoader
import com.kitworks.tablekit.format.TabularFormat
import javax.swing.Icon

/**
 * One icon per format, same glyph in different colours: a project tree full of
 * data files should tell you at a glance which is which.
 */
object TableKitIcons {

    private val PARQUET = load("parquetFile")
    private val EXCEL = load("excelFile")
    private val AVRO = load("avroFile")
    private val ORC = load("orcFile")

    fun forFormat(format: TabularFormat): Icon = when (format) {
        TabularFormat.PARQUET -> PARQUET
        TabularFormat.EXCEL -> EXCEL
        TabularFormat.AVRO -> AVRO
        TabularFormat.ORC -> ORC
        else -> PARQUET
    }

    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", TableKitIcons::class.java)
}
