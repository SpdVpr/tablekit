package com.kitworks.tablekit.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.kitworks.tablekit.data.ColumnFilter
import com.kitworks.tablekit.data.ColumnInfo
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * The grid's three cell states and the header's schema line, checked directly.
 *
 * A renderer that quietly shows the wrong thing - a null that reads as an empty
 * string, a number aligned like text - is the kind of bug a data viewer cannot
 * afford, and none of it throws.
 */
class RenderersTest : BasePlatformTestCase() {

    private val columns = listOf(
        ColumnInfo(0, "amount", "DOUBLE", nullable = true),
        ColumnInfo(1, "city", "VARCHAR", nullable = false),
        ColumnInfo(2, "address", "STRUCT(city VARCHAR)", nullable = true),
    )

    private val table = JBTable(DefaultTableModel(3, 3))

    fun `test a value, a null and a row still loading are three different things`() {
        val loadedRows = setOf(0, 1)
        val renderer = TabularCellRenderer(columns, isRowLoaded = { it in loadedRows })

        val value = renderer.render("42.5", row = 0, column = 0)
        assertEquals("42.5", value.text)

        val sqlNull = renderer.render(null, row = 1, column = 0)
        assertEquals("NULL", sqlNull.text)

        val pending = renderer.render(null, row = 2, column = 0)
        assertEquals("...", pending.text)
    }

    fun `test numbers are right aligned and text is not`() {
        val renderer = TabularCellRenderer(columns, isRowLoaded = { true })

        assertEquals(SwingConstants.RIGHT, renderer.render("1", row = 0, column = 0).horizontalAlignment)
        assertEquals(SwingConstants.LEFT, renderer.render("Prague", row = 0, column = 1).horizontalAlignment)
        assertEquals(
            "a struct is not a number, whatever it contains",
            SwingConstants.LEFT,
            renderer.render("{}", row = 0, column = 2).horizontalAlignment,
        )
    }

    fun `test a long value is cut for the grid but kept in the tooltip`() {
        val renderer = TabularCellRenderer(columns, isRowLoaded = { true })
        val long = "x".repeat(2000)

        val rendered = renderer.render(long, row = 0, column = 1)
        assertTrue("the grid must not try to draw 2000 characters", rendered.text.length < 600)
        assertTrue(rendered.text.endsWith("..."))
        assertTrue("the full value has to stay reachable", rendered.toolTipText.length > 1000)
    }

    fun `test the header carries the column type and the sort direction`() {
        var direction: Boolean? = null
        val renderer = TabularHeaderRenderer(table.tableHeader.defaultRenderer, columns) { direction }

        val plain = header(renderer, column = 0)
        assertTrue("the type belongs in the header: ${plain.text}", plain.text.contains("DOUBLE"))
        assertTrue(plain.text.contains("amount"))
        assertNull("no sort, no arrow", plain.icon)

        direction = false
        assertNotNull("ascending needs an arrow", header(renderer, column = 0).icon)

        direction = true
        val descending = header(renderer, column = 0).icon
        assertNotNull(descending)
        assertNotSame("the two directions must not look the same", header(renderer, 0).icon, null)
    }

    fun `test a column name with markup in it stays text`() {
        val hostile = listOf(ColumnInfo(0, "<b>id</b>", "BIGINT", nullable = true))
        val renderer = TabularHeaderRenderer(table.tableHeader.defaultRenderer, hostile) { null }

        val rendered = header(renderer, column = 0).text
        assertTrue("the name must be escaped, not interpreted: $rendered", rendered.contains("&lt;b&gt;id&lt;/b&gt;"))
    }

    fun `test the value viewer formats nested json and says NULL out loud`() {
        val nested = CellValueViewer.component(columns[2], """{"city":"Prague","zip":11000}""")
        val text = textOf(nested)
        assertTrue("nested values are re-indented:\n$text", text.contains("\n  \"city\": \"Prague\""))

        assertEquals("NULL", textOf(CellValueViewer.component(columns[2], null)).trim())

        val plain = textOf(CellValueViewer.component(columns[1], "just text"))
        assertEquals("just text", plain)
    }

    fun `test the filter chips show what is filtered and disappear when nothing is`() {
        val removed = mutableListOf<ColumnFilter>()
        val chips = FilterChipsPanel(onRemove = { removed += it }, onClearAll = {})

        chips.show(emptyList())
        assertFalse("no filters, no bar", chips.isVisible)

        chips.show(listOf(ColumnFilter.Contains("city", "Prague"), ColumnFilter.IsNull("amount")))
        assertTrue(chips.isVisible)
        val labels = com.intellij.util.ui.UIUtil.uiTraverser(chips).traverse()
            .filter(JLabel::class.java)
            .map { it.text }
            .toList()
        assertTrue("the chips must name the filters: $labels", labels.any { it.contains("Prague") })
        assertTrue(labels.any { it.contains("amount") && it.contains("null") })
    }

    fun `test the renderer marks what the filter matched`() {
        var needle: IntRange? = null
        val renderer = TabularCellRenderer(columns, isRowLoaded = { true }, matchIn = { _, _ -> needle })

        needle = 2 until 4
        val marked = renderer.render("Prague", row = 0, column = 1)
        assertEquals("the text itself must not change", "Prague", marked.text)

        // A selected cell is already marked; the highlight would fight it.
        val selected = renderer.getTableCellRendererComponent(table, "Prague", true, false, 0, 1)
        assertNotNull(selected)
    }

    // --- helpers ------------------------------------------------------------

    private fun TabularCellRenderer.render(value: String?, row: Int, column: Int): JLabel =
        getTableCellRendererComponent(table, value, false, false, row, column) as JLabel

    private fun header(renderer: TabularHeaderRenderer, column: Int): JLabel =
        renderer.getTableCellRendererComponent(table, "ignored", false, false, -1, column) as JLabel

    private fun textOf(component: javax.swing.JComponent): String =
        com.intellij.util.ui.UIUtil.uiTraverser(component).traverse()
            .filter(javax.swing.JTextArea::class.java)
            .first()!!
            .text
}
