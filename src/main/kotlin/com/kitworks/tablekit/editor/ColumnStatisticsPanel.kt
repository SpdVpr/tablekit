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
import com.kitworks.tablekit.data.Histogram
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
 * distinct values there are, its range, and how it spreads out.
 *
 * The bars are the point - a column that is 90% one value, or a price column
 * with a long tail, is something you should see rather than count. They are
 * also the one place in the plugin where colour carries meaning rather than
 * decoration, so they take the theme's accent.
 */
class ColumnStatisticsPanel(column: ColumnInfo, statistics: ColumnStatistics) :
    JBPanel<ColumnStatisticsPanel>(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(8, 10)
        add(header(column), BorderLayout.NORTH)
        add(figures(statistics), BorderLayout.CENTER)

        // Ordered columns get their shape; categorical ones get their leaders.
        // A list of values that each occur once says nothing the distinct count
        // has not already said, so it is left out.
        val histogram = statistics.histogram
        val repeats = statistics.topValues.any { it.value != null && it.count > 1 }
        when {
            histogram != null && histogram.isUseful -> add(distribution(statistics, histogram), BorderLayout.SOUTH)
            repeats -> add(topValues(statistics), BorderLayout.SOUTH)
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
        add(TableKitBundle.message("stats.average"), statistics.average?.let(::readableNumber))
        return panel
    }

    /** An average is a summary; seventeen significant digits is not one. */
    private fun readableNumber(text: String): String =
        text.toDoubleOrNull()?.let(AVERAGE::format) ?: text

    private fun distribution(statistics: ColumnStatistics, histogram: Histogram): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyTop(10)

        panel.add(
            JBLabel(TableKitBundle.message("stats.distribution")).apply { font = JBFont.label().asBold() },
            BorderLayout.NORTH,
        )
        panel.add(HistogramView(histogram), BorderLayout.CENTER)

        val bounds = JPanel(BorderLayout())
        bounds.isOpaque = false
        bounds.add(edgeLabel(statistics.min, SwingConstants.LEFT), BorderLayout.WEST)
        bounds.add(edgeLabel(statistics.max, SwingConstants.RIGHT), BorderLayout.EAST)
        panel.add(bounds, BorderLayout.SOUTH)
        return panel
    }

    private fun edgeLabel(text: String?, alignment: Int) =
        JBLabel(text.orEmpty().take(MAX_BOUND_LENGTH), alignment).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBFont.small()
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

    /** The shape of an ordered column: one bar per slice of its range. */
    private class HistogramView(private val histogram: Histogram) : JPanel() {

        init {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(300), JBUI.scale(56))
        }

        override fun paintComponent(g: Graphics) {
            val bins = histogram.counts
            if (bins.isEmpty()) return

            val gap = JBUI.scale(1)
            val barWidth = ((width - gap * (bins.size - 1)).toDouble() / bins.size).coerceAtLeast(1.0)
            val highest = histogram.highest.coerceAtLeast(1)
            val baseline = height - JBUI.scale(1)

            g.color = ColorUtil.withAlpha(JBUI.CurrentTheme.Focus.focusColor(), 0.75)
            bins.forEachIndexed { index, count ->
                val barHeight = (count.toDouble() / highest * (baseline - JBUI.scale(2))).toInt()
                val x = (index * (barWidth + gap)).toInt()
                // Something that occurred at all should be visible.
                val drawn = if (count > 0) barHeight.coerceAtLeast(JBUI.scale(1)) else 0
                g.fillRect(x, baseline - drawn, barWidth.toInt().coerceAtLeast(1), drawn)
            }

            g.color = ColorUtil.withAlpha(UIUtil.getLabelForeground(), 0.20)
            g.fillRect(0, baseline, width, JBUI.scale(1))
        }
    }

    /** A proportional bar; the width is the share of the most frequent value. */
    private class FrequencyBar(private val fraction: Double) : JPanel() {

        init {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(120), JBUI.scale(10))
        }

        override fun paintComponent(g: Graphics) {
            val filled = (width * fraction.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(JBUI.scale(2))
            val top = (height - JBUI.scale(8)) / 2
            g.color = ColorUtil.withAlpha(UIUtil.getLabelForeground(), 0.10)
            g.fillRect(0, top, width, JBUI.scale(8))
            g.color = ColorUtil.withAlpha(JBUI.CurrentTheme.Focus.focusColor(), 0.75)
            g.fillRect(0, top, filled, JBUI.scale(8))
        }
    }

    private companion object {
        val COUNT: NumberFormat = NumberFormat.getIntegerInstance()
        val AVERAGE: NumberFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 4 }
        val PERCENT: NumberFormat = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
        const val MAX_VALUE_LENGTH = 60
        const val MAX_BOUND_LENGTH = 24
    }
}
