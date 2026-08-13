package com.kitworks.tablekit.editor

import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.data.ColumnInfo
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Renders cell values that are already strings.
 *
 * Three states must stay distinguishable: a real value, a SQL NULL, and a row
 * whose page has not arrived yet. Conflating the last two is how data viewers
 * end up lying to people.
 */
class TabularCellRenderer(
    private val columns: List<ColumnInfo>,
    private val isRowLoaded: (Int) -> Boolean,
) : DefaultTableCellRenderer() {

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)

        val modelColumn = table.convertColumnIndexToModel(column)
        horizontalAlignment = if (columns[modelColumn].numeric) SwingConstants.RIGHT else SwingConstants.LEFT

        val text = value as String?
        when {
            text != null -> {
                this.text = if (text.length > MAX_CELL_LENGTH) text.take(MAX_CELL_LENGTH) + "..." else text
                toolTipText = if (text.length > MAX_CELL_LENGTH) text.take(MAX_TOOLTIP_LENGTH) else null
            }

            isRowLoaded(row) -> {
                this.text = "NULL"
                toolTipText = null
                if (!isSelected) foreground = UIUtil.getInactiveTextColor()
            }

            else -> {
                this.text = ""
                toolTipText = null
            }
        }
        return this
    }

    private companion object {
        const val MAX_CELL_LENGTH = 500
        const val MAX_TOOLTIP_LENGTH = 2000
    }
}

/**
 * Column header showing the name, its type, and the current sort direction.
 *
 * Wraps the look and feel's own header renderer instead of replacing it, so the
 * header keeps looking like a header in every theme.
 */
class TabularHeaderRenderer(
    private val delegate: TableCellRenderer,
    private val columns: List<ColumnInfo>,
    private val sortDirectionOf: (String) -> Boolean?,
) : TableCellRenderer {

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val component = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (component !is JLabel) return component

        val info = columns[table.convertColumnIndexToModel(column)]
        val typeColor = ColorUtil.toHex(UIUtil.getContextHelpForeground())
        component.text = "<html>${escape(info.name)} <font color='#$typeColor'>${escape(info.typeName)}</font></html>"
        component.toolTipText = "${info.name}: ${info.typeName}${if (info.nullable) "" else " NOT NULL"}"
        component.icon = when (sortDirectionOf(info.name)) {
            false -> AllIcons.General.ArrowUp
            true -> AllIcons.General.ArrowDown
            null -> null
        }
        component.horizontalTextPosition = SwingConstants.LEADING
        return component
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
