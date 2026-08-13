package com.kitworks.tablekit.editor

import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.TableKitBundle
import com.kitworks.tablekit.data.ColumnFilter
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * The filters currently in force, each removable on its own.
 *
 * Without this the only evidence of a filter is a smaller row count, which is
 * how people end up reading a fraction of a file and believing it is all of it.
 */
class FilterChipsPanel(
    private val onRemove: (ColumnFilter) -> Unit,
    private val onClearAll: () -> Unit,
) : JBPanel<FilterChipsPanel>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))) {

    init {
        isVisible = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(UIUtil.getBoundsColor()),
            JBUI.Borders.empty(1, 6),
        )
    }

    fun show(filters: List<ColumnFilter>) {
        removeAll()
        isVisible = filters.isNotEmpty()

        if (filters.isNotEmpty()) {
            add(
                JBLabel(TableKitBundle.message("filter.active")).apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
            filters.forEach { add(Chip(it)) }
            if (filters.size > 1) {
                add(ActionLink(TableKitBundle.message("filter.clear.all")) { onClearAll() })
            }
        }

        revalidate()
        repaint()
    }

    private inner class Chip(private val filter: ColumnFilter) :
        JBPanel<Chip>(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {

        init {
            isOpaque = false
            border = JBUI.Borders.empty(1, 7, 1, 3)
            add(JBLabel(filter.describe().take(MAX_LABEL_LENGTH)))
            add(
                InplaceButton(TableKitBundle.message("filter.remove"), AllIcons.Actions.Close) {
                    onRemove(filter)
                },
            )
        }

        override fun paintComponent(g: Graphics) {
            val graphics = g.create() as Graphics2D
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Tinted with the theme accent so a chip reads as something
                // switched on rather than as decoration.
                graphics.color = ColorUtil.withAlpha(JBUI.CurrentTheme.Focus.focusColor(), 0.18)
                val arc = JBUI.scale(ARC)
                graphics.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                graphics.dispose()
            }
            super.paintComponent(g)
        }
    }

    private companion object {
        const val MAX_LABEL_LENGTH = 60
        const val ARC = 12
    }
}
