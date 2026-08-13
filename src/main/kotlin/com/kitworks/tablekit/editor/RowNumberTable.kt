package com.kitworks.tablekit.editor

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

/**
 * The numbers down the left edge of the grid.
 *
 * They count what is on screen, so with a filter or a sort in force row 1 is
 * the first row of the current view rather than of the file - the number the
 * user can actually point at.
 */
class RowNumberTable(private val grid: JTable) : JBTable(RowNumberModel(grid.model)) {

    init {
        isFocusable = false
        rowSelectionAllowed = false
        cellSelectionEnabled = false
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = grid.rowHeight
        tableHeader = null
        setShowGrid(false)
        intercellSpacing = JBUI.emptySize()
        background = UIUtil.getPanelBackground()
        setDefaultRenderer(Any::class.java, NumberRenderer())
    }

    /** Widens as the row count grows so five digit files do not clip. */
    fun updateWidth() {
        val digits = maxOf(grid.rowCount.toString().length, MIN_DIGITS)
        val width = getFontMetrics(font).charWidth('0') * digits + JBUI.scale(12)
        preferredScrollableViewportSize = java.awt.Dimension(width, 0)
        columnModel.getColumn(0).preferredWidth = width
    }

    /**
     * JTable asks for the row height from its own constructor, before this
     * class has finished initialising - so despite the type, [grid] really can
     * still be null here.
     */
    @Suppress("SENSELESS_COMPARISON")
    override fun getRowHeight(): Int = if (grid == null) super.getRowHeight() else grid.rowHeight

    private class NumberRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, false, false, row, column)
            horizontalAlignment = SwingConstants.RIGHT
            border = JBUI.Borders.empty(0, 4)
            foreground = UIUtil.getInactiveTextColor()
            background = UIUtil.getPanelBackground()
            return this
        }
    }

    /** Mirrors the grid's row count and nothing else. */
    private class RowNumberModel(private val source: TableModel) : AbstractTableModel() {

        private var lastCount = source.rowCount

        init {
            source.addTableModelListener {
                if (source.rowCount != lastCount) {
                    lastCount = source.rowCount
                    fireTableDataChanged()
                }
            }
        }

        override fun getRowCount(): Int = source.rowCount

        override fun getColumnCount(): Int = 1

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rowIndex + 1

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }

    private companion object {
        const val MIN_DIGITS = 3
    }
}
