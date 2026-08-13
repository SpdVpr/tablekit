package com.kitworks.tablekit.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.TableKitBundle
import com.kitworks.tablekit.data.TableSource
import com.kitworks.tablekit.data.TableSourceException
import com.kitworks.tablekit.format.TabularFormat
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.NumberFormat
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.ListSelectionModel

/**
 * Root component of the tabular editor: a loading state, then either the grid
 * or a readable error.
 *
 * Opening a file, counting its rows and fetching pages all happen on a single
 * background thread per file. The EDT only ever paints what has already been
 * fetched - an IDE that freezes on a large file is the exact failure we exist
 * to fix.
 */
class TabularEditorPanel(
    private val file: VirtualFile,
    private val format: TabularFormat,
) : JBPanel<TabularEditorPanel>(BorderLayout()), Disposable {

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("TableKit: " + file.name, 1)
    private val statusLabel = JBLabel(format.displayName)

    private var center: JComponent? = null
    private var source: TableSource? = null
    private var table: JBTable? = null
    private var columnsSized = false

    @Volatile
    private var disposed = false

    init {
        add(createStatusBar(), BorderLayout.SOUTH)
        setCenter(JBPanelWithEmptyText().withEmptyText(TableKitBundle.message("editor.loading", file.name)))
        executor.execute(::openSource)
    }

    val preferredFocusedComponent: JComponent get() = table ?: this

    // --- opening ------------------------------------------------------------

    private fun openSource() {
        val opened = runCatching { TableSource.open(file.toNioPath(), format) }
        onEdt {
            if (disposed) {
                opened.getOrNull()?.close()
                return@onEdt
            }
            opened.onSuccess(::showGrid).onFailure(::showFailure)
        }
    }

    private fun showGrid(opened: TableSource) {
        source = opened

        val model = TabularTableModel(opened, executor, ::showPageError)
        val grid = JBTable(model).apply {
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            cellSelectionEnabled = true
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            setDefaultRenderer(String::class.java, TabularCellRenderer(model.columns, model::isLoaded))
            tableHeader.reorderingAllowed = false
            tableHeader.defaultRenderer = TabularHeaderRenderer(
                tableHeader.defaultRenderer,
                model.columns,
            ) { column -> model.query.sortKeyFor(column)?.descending }
            tableHeader.addMouseListener(sortOnHeaderClick(this, model))
        }
        model.addTableModelListener {
            if (!columnsSized && model.rowCount > 0 && model.isLoaded(0)) {
                columnsSized = true
                sizeColumnsToContent(grid, model)
            }
        }

        table = grid
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.text = TableKitBundle.message(
            "editor.status",
            format.displayName,
            ROW_FORMAT.format(opened.rowCount),
            opened.columns.size,
            StringUtil.formatFileSize(file.length),
        )
        setCenter(JBScrollPane(grid))
    }

    private fun showFailure(failure: Throwable) {
        val message = (failure as? TableSourceException)?.message
            ?: failure.message
            ?: TableKitBundle.message("editor.error.unknown")
        setCenter(
            JBPanelWithEmptyText()
                .withEmptyText(TableKitBundle.message("editor.error.title", file.name))
                .apply { emptyText.appendLine(message) },
        )
        statusLabel.foreground = JBColor.RED
        statusLabel.text = message
    }

    /**
     * A page that fails mid-scroll must not throw the user out of the file they
     * are reading, so it is reported in the status bar and nowhere else.
     */
    private fun showPageError(message: String) {
        statusLabel.foreground = JBColor.RED
        statusLabel.text = message
    }

    // --- grid behaviour -----------------------------------------------------

    private fun sortOnHeaderClick(grid: JBTable, model: TabularTableModel) = object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
            // A click on the resize handle is a resize, not a sort.
            if (grid.tableHeader.cursor.type == Cursor.E_RESIZE_CURSOR) return

            val viewColumn = grid.columnAtPoint(event.point)
            if (viewColumn < 0) return

            model.sortBy(grid.convertColumnIndexToModel(viewColumn))
            grid.tableHeader.repaint()
            grid.scrollRectToVisible(Rectangle(0, 0, 1, 1))
        }
    }

    /** Sizes columns from the first loaded page - a default width per column is useless. */
    private fun sizeColumnsToContent(grid: JBTable, model: TabularTableModel) {
        val cellMetrics = grid.getFontMetrics(grid.font)
        val headerMetrics = grid.tableHeader.getFontMetrics(grid.tableHeader.font)
        val sampleRows = minOf(model.rowCount, SAMPLE_ROWS)

        for (columnIndex in 0 until model.columnCount) {
            val info = model.columns[columnIndex]
            var width = headerMetrics.stringWidth(info.name + "  " + info.typeName) + JBUI.scale(28)
            for (row in 0 until sampleRows) {
                val value = model.getValueAt(row, columnIndex) ?: continue
                width = maxOf(width, cellMetrics.stringWidth(value.take(MAX_MEASURED_CHARS)) + JBUI.scale(16))
            }
            grid.columnModel.getColumn(columnIndex).preferredWidth =
                width.coerceIn(JBUI.scale(MIN_COLUMN_WIDTH), JBUI.scale(MAX_COLUMN_WIDTH))
        }
    }

    // --- plumbing -----------------------------------------------------------

    private fun createStatusBar(): JComponent = statusLabel.apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(UIUtil.getBoundsColor()),
            JBUI.Borders.empty(4, 8),
        )
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun setCenter(component: JComponent) {
        center?.let(::remove)
        center = component
        add(component, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun onEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any())

    override fun dispose() {
        disposed = true
        val opened = source
        source = null
        // Queued after any query still in flight, so nothing closes underneath it.
        executor.execute { opened?.close() }
        executor.shutdown()
    }

    private companion object {
        val ROW_FORMAT: NumberFormat = NumberFormat.getIntegerInstance()

        const val SAMPLE_ROWS = 50
        const val MAX_MEASURED_CHARS = 80
        const val MIN_COLUMN_WIDTH = 60
        const val MAX_COLUMN_WIDTH = 400
    }
}
