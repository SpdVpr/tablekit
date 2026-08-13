package com.kitworks.tablekit.editor

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.TableKitBundle
import com.kitworks.tablekit.data.ColumnInfo
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Shows one cell in full.
 *
 * A grid has to truncate, and the values that get truncated - JSON blobs, long
 * text, stack traces stored in a column - are usually the ones worth reading.
 * Nested values are re-indented on the way in.
 */
object CellValueViewer {

    fun popup(column: ColumnInfo, value: String?): JBPopup {
        val content = component(column, value)
        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setTitle(column.name + "  " + column.typeName)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
    }

    /** The popup's content, separately so it can be rendered on its own. */
    fun component(column: ColumnInfo, value: String?): JComponent {
        val text = when {
            value == null -> TableKitBundle.message("value.null")
            JsonFormat.looksLikeJson(value) -> JsonFormat.pretty(value)
            else -> value
        }

        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
            font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
            border = JBUI.Borders.empty(6)
            if (value == null) foreground = UIUtil.getInactiveTextColor()
            caretPosition = 0
        }

        return JBScrollPane(area).apply {
            preferredSize = Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT))
        }
    }

    private const val WIDTH = 540
    private const val HEIGHT = 300
}
