package com.kitworks.tablekit.editor

import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.TableKitBundle
import com.kitworks.tablekit.data.ColumnInfo
import com.kitworks.tablekit.data.ColumnStatistics
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Graphics
import java.text.NumberFormat
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * What a column contains, shown in a popup: how much of it is missing, how many
 * distinct values there are, its range, and which values dominate.
 *
 * The frequency bars are the point - a column that is 90% one value is
 * something you should be able to see, not count.
 */
class ColumnStatisticsPanel(column: ColumnInfo, statistics: ColumnStatistics) :
    JBPanel<ColumnStatisticsPanel>(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(8, 10)
        add(header(column), BorderLayout.NORTH)
        add(figures(statistics), BorderLayout.CENTER)
        if (statistics.topValues.isNotEmpty()) {
            add(topValues(statistics), BorderLayout.SOUTH)
        }
    }

    private fun header(column: ColumnInfo): JComponent = JBLabel(
        "<html><b>${escape(column.name)}</b> <font color='#${hex(UIUtil.getContextHelpForeground())}'>" +
            "${escape(column.typeName)}</font></html>",
    ).apply {
        border = JBUI.Borders.emptyBottom(8)
    }

    private fun figures(statistics: ColumnStatistics): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.isOpaque = false
        var row = 0

        fun add(label: String, value: String?) {
            if (value == null) return
            panel.add(
                JBLabel(label).apply { foreground = UIUtil.getContextHelpForeground() },
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    anchor = GridBagConstraints.LINE_START
                    insets = JBUI.insets(1, 0, 1, 12)
                },
            )
            panel.add(
                JBLabel(value.take(MAX_VALUE_LENGTH)),
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    anchor = GridBagConstraints.LINE_START
                    insets = JBUI.insets(1, 0)
                },
            )
            row++
        }

        add(TableKitBundle.message("stats.rows"), COUNT.format(statistics.total))
        add(
            TableKitBundle.message("stats.nulls"),
            "${COUNT.format(statistics.nulls)} (${PERCENT.format(statistics.nullRatio)})",
        )
        add(TableKitBundle.message("stats.distinct"), COUNT.format(statistics.distinct))
        add(TableKitBundle.message("stats.min"), statistics.min)
        add(TableKitBundle.message("stats.max"), statistics.max)
        add(TableKitBundle.message("stats.average"), statistics.average)
        return panel
    }

    private fun topValues(statistics: ColumnStatistics): JComponent {
        val panel = JPanel()
        panel.isOpaque = false
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.emptyTop(10)

        panel.add(
            JBLabel(TableKitBundle.message("stats.top")).apply {
                font = JBFont.label().asBold()
                alignmentX = LEFT_ALIGNMENT
            },
        )

        val highest = statistics.topValues.maxOf { it.count }.coerceAtLeast(1)
        for (entry in statistics.topValues) {
            val row = JPanel(BorderLayout(JBUI.scale(8), 0))
            row.isOpaque = false
            row.alignmentX = LEFT_ALIGNMENT
            row.border = JBUI.Borders.emptyTop(3)

            val text = entry.value?.take(MAX_VALUE_LENGTH) ?: TableKitBundle.message("stats.null")
            row.add(
                JBLabel(text).apply {
                    if (entry.value == null) foreground = UIUtil.getInactiveTextColor()
                    preferredSize = Dimension(JBUI.scale(170), preferredSize.height)
                },
                BorderLayout.WEST,
            )
            row.add(FrequencyBar(entry.count.toDouble() / highest), BorderLayout.CENTER)
            row.add(
                JBLabel(COUNT.format(entry.count), SwingConstants.RIGHT).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    preferredSize = Dimension(JBUI.scale(70), preferredSize.height)
                },
                BorderLayout.EAST,
            )
            panel.add(row)
        }
        return panel
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun hex(color: Color): String = ColorUtil.toHex(color)

    /** A proportional bar; the width is the share of the most frequent value. */
    private class FrequencyBar(private val fraction: Double) : JPanel() {

        init {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(120), JBUI.scale(10))
        }

        override fun paintComponent(g: Graphics) {
            val filled = (width * fraction.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(JBUI.scale(2))
            val top = (height - JBUI.scale(8)) / 2
            g.color = ColorUtil.withAlpha(UIUtil.getLabelForeground(), 0.12)
            g.fillRect(0, top, width, JBUI.scale(8))
            g.color = ColorUtil.withAlpha(UIUtil.getLabelForeground(), 0.45)
            g.fillRect(0, top, filled, JBUI.scale(8))
        }
    }

    private companion object {
        val COUNT: NumberFormat = NumberFormat.getIntegerInstance()
        val PERCENT: NumberFormat = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
        const val MAX_VALUE_LENGTH = 60
    }
}
