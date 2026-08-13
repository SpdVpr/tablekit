package com.kitworks.tablekit

import com.kitworks.tablekit.format.TabularFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabularFormatTest {

    @Test
    fun `extensions resolve to their format`() {
        assertEquals(TabularFormat.PARQUET, TabularFormat.byExtension("parquet"))
        assertEquals(TabularFormat.EXCEL, TabularFormat.byExtension("xlsx"))
        assertEquals(TabularFormat.EXCEL, TabularFormat.byExtension("xlsm"))
        assertEquals(TabularFormat.JSONL, TabularFormat.byExtension("ndjson"))
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertEquals(TabularFormat.PARQUET, TabularFormat.byExtension("PARQUET"))
        assertEquals(TabularFormat.CSV, TabularFormat.byExtension("Csv"))
    }

    @Test
    fun `unknown and missing extensions resolve to null`() {
        assertNull(TabularFormat.byExtension("txt"))
        assertNull(TabularFormat.byExtension(""))
        assertNull(TabularFormat.byExtension(null))
    }

    /** A duplicate would silently shadow one of the formats in the lookup map. */
    @Test
    fun `no extension is claimed by two formats`() {
        val all = TabularFormat.values().flatMap { it.extensions }
        assertEquals(all.size, all.toSet().size)
    }

    /** Only text formats keep their existing default editor - see PLAN.md. */
    @Test
    fun `text formats are the ones the IDE already owns`() {
        val text = TabularFormat.values().filterNot { it.binary }.toSet()
        assertEquals(setOf(TabularFormat.CSV, TabularFormat.TSV, TabularFormat.JSONL), text)
        assertTrue(TabularFormat.PARQUET.binary)
    }
}
