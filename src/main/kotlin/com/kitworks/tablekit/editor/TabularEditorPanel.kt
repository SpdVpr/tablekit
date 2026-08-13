package com.kitworks.tablekit.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.TableKitBundle
import com.kitworks.tablekit.data.ColumnFilter
import com.kitworks.tablekit.data.ColumnInfo
import com.kitworks.tablekit.data.ExportFormat
import com.kitworks.tablekit.data.TableSource
import com.kitworks.tablekit.data.TableSourceException
import com.kitworks.tablekit.format.TabularFormat
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.text.NumberFormat
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Root component of the tabular editor: a loading state, then either the grid
 * or a readable error.
 *
 * Opening a file, counting its rows, fetching pages, summarising a column and
 * exporting all happen on a single background thread per file. The EDT only
 * ever paints what has already been fetched - an IDE that freezes on a large
 * file is the exact failure we exist to fix.
 */
class TabularEditorPanel(
    private val file: VirtualFile,
    private val format: TabularFormat,
) : JBPanel<TabularEditorPanel>(BorderLayout()), Disposable {

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("TableKit: " + file.name, 1)
    private val statusLabel = JBLabel(format.displayName)
    private val filterField = JBTextField()
    private val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)))

    /** Debounces typing so a filter is one query, not one query per keystroke. */
    private val filterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var center: JComponent? = null
    private var source: TableSource? = null
    private var model: TabularTableModel? = null
    private var table: JBTable? = null
    private var rowNumbers: RowNumberTable? = null
    private var sheetIndex = 0
    private var columnsSized = false

    @Volatile
    private var disposed = false

    init {
        add(createStatusBar(), BorderLayout.SOUTH)
        filterField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                model?.let(::scheduleFilter)
            }
        })
        open()
    }

    val preferredFocusedComponent: JComponent get() = table ?: this

    // --- opening ------------------------------------------------------------

    private fun open() {
        columnsSized = false
        toolbar.isVisible = false
        setCenter(JBPanelWithEmptyText().withEmptyText(TableKitBundle.message("editor.loading", file.name)))

        val requestedSheet = sheetIndex
        executor.execute {
            val opened = runCatching { TableSource.open(file.toNioPath(), format, requestedSheet) }
            onEdt {
                if (disposed || requestedSheet != sheetIndex) {
                    opened.getOrNull()?.close()
                    return@onEdt
                }
                opened.onSuccess(::showGrid).onFailure(::showFailure)
            }
        }
    }

    private fun showGrid(opened: TableSource) {
        source?.let { previous -> executor.execute { previous.close() } }
        source = opened

        val tableModel = TabularTableModel(opened, executor, ::showPageError) { updateStatus() }
        val grid = JBTable(tableModel).apply {
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            cellSelectionEnabled = true
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            setDefaultRenderer(String::class.java, TabularCellRenderer(tableModel.columns, tableModel::isLoaded))
            tableHeader.reorderingAllowed = false
            tableHeader.defaultRenderer = TabularHeaderRenderer(
                tableHeader.defaultRenderer,
                tableModel.columns,
            ) { column -> tableModel.query.sortKeyFor(column)?.descending }
            tableHeader.addMouseListener(headerMouse(this, tableModel))
        }

        val numbers = RowNumberTable(grid).apply { updateWidth() }
        tableModel.addTableModelListener {
            numbers.updateWidth()
            if (!columnsSized && tableModel.rowCount > 0 && tableModel.isLoaded(0)) {
                columnsSized = true
                sizeColumnsToContent(grid, tableModel)
            }
        }

        model = tableModel
        table = grid
        rowNumbers = numbers
        buildToolbar(opened, tableModel)
        updateStatus()
        setCenter(JBScrollPane(grid).apply { setRowHeaderView(numbers) })
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

    // --- toolbar ------------------------------------------------------------

    private fun buildToolbar(opened: TableSource, tableModel: TabularTableModel) {
        toolbar.removeAll()

        if (opened.sheets.size > 1) {
            toolbar.add(JBLabel(TableKitBundle.message("editor.sheet")))
            toolbar.add(
                ComboBox(opened.sheets.toTypedArray()).apply {
                    selectedIndex = opened.sheetIndex
                    addActionListener {
                        if (selectedIndex >= 0 && selectedIndex != sheetIndex) {
                            sheetIndex = selectedIndex
                            open()
                        }
                    }
                },
            )
        }

        filterField.apply {
            emptyText.text = TableKitBundle.message("editor.filter.hint")
            columns = 24
            text = ""
        }
        toolbar.add(filterField)
        toolbar.add(JButton(TableKitBundle.message("editor.export")).apply { addActionListener { exportRows() } })
        toolbar.isVisible = true
        toolbar.revalidate()
        toolbar.repaint()

        if (toolbar.parent == null) add(toolbar, BorderLayout.NORTH)
    }

    private fun scheduleFilter(tableModel: TabularTableModel) {
        filterAlarm.cancelAllRequests()
        filterAlarm.addRequest({ tableModel.filterByText(filterField.text) }, FILTER_DELAY_MS)
    }

    // --- exporting ----------------------------------------------------------

    /**
     * Writes what the user is looking at - filters and sort included, every row
     * rather than the page on screen. The engine writes the file, so this is
     * also how a Parquet file becomes a CSV.
     */
    private fun exportRows() {
        val opened = source ?: return
        val tableModel = model ?: return

        val descriptor = FileSaverDescriptor(
            TableKitBundle.message("editor.export.title"),
            TableKitBundle.message("editor.export.description"),
            *ExportFormat.values().map { it.extension }.toTypedArray(),
        )
        val suggested = file.nameWithoutExtension + "." + ExportFormat.CSV.extension
        val chosen = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, this)
            .save(file.toNioPath().parent, suggested)
            ?: return

        val target = withKnownExtension(chosen.file.toPath())
        val exportFormat = ExportFormat.forExtension(target.fileName.toString().substringAfterLast('.', ""))
            ?: ExportFormat.CSV
        val query = tableModel.query
        val rows = tableModel.filteredRowCount

        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.text = TableKitBundle.message("editor.export.running", target.fileName.toString())

        executor.execute {
            val result = runCatching { opened.export(query, target, exportFormat) }
            onEdt {
                if (disposed) return@onEdt
                result
                    .onSuccess {
                        statusLabel.foreground = UIUtil.getContextHelpForeground()
                        statusLabel.text = TableKitBundle.message(
                            "editor.export.done",
                            ROW_FORMAT.format(rows),
                            target.fileName.toString(),
                        )
                    }
                    .onFailure { failure ->
                        showPageError(
                            (failure as? TableSourceException)?.message
                                ?: failure.message
                                ?: TableKitBundle.message("editor.export.failed"),
                        )
                    }
            }
        }
    }

    /** A name typed without an extension still has to end up a real CSV file. */
    private fun withKnownExtension(target: Path): Path {
        val name = target.fileName.toString()
        val extension = name.substringAfterLast('.', "")
        return if (ExportFormat.forExtension(extension) != null) {
            target
        } else {
            target.resolveSibling(name + "." + ExportFormat.CSV.extension)
        }
    }

    // --- grid behaviour -----------------------------------------------------

    private fun headerMouse(grid: JBTable, tableModel: TabularTableModel) = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) = maybeShowMenu(event)

        override fun mouseReleased(event: MouseEvent) = maybeShowMenu(event)

        override fun mouseClicked(event: MouseEvent) {
            if (event.isPopupTrigger || event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
            // A click on the resize handle is a resize, not a sort.
            if (grid.tableHeader.cursor.type == Cursor.E_RESIZE_CURSOR) return

            val column = columnAt(grid, event) ?: return
            tableModel.sortBy(column)
            afterQueryChange(grid)
        }

        private fun maybeShowMenu(event: MouseEvent) {
            if (!event.isPopupTrigger) return
            val column = columnAt(grid, event) ?: return
            val where = RelativePoint(grid.tableHeader, Point(event.x, event.y))
            columnMenu(grid, tableModel, column, where).show(grid.tableHeader, event.x, event.y)
        }
    }

    private fun columnAt(grid: JBTable, event: MouseEvent): Int? =
        grid.columnAtPoint(event.point).takeIf { it >= 0 }?.let(grid::convertColumnIndexToModel)

    private fun columnMenu(grid: JBTable, tableModel: TabularTableModel, column: Int, where: RelativePoint): JBPopupMenu {
        val info: ColumnInfo = tableModel.columns[column]
        val menu = JBPopupMenu(info.name)

        menu.add(menuItem(TableKitBundle.message("stats.action")) { showStatistics(tableModel, info, where) })
        menu.addSeparator()

        menu.add(
            menuItem(TableKitBundle.message("filter.sort.asc")) {
                tableModel.sortBy(column, descending = false)
                afterQueryChange(grid)
            },
        )
        menu.add(
            menuItem(TableKitBundle.message("filter.sort.desc")) {
                tableModel.sortBy(column, descending = true)
                afterQueryChange(grid)
            },
        )
        if (tableModel.query.sortKeyFor(info.name) != null) {
            menu.add(menuItem(TableKitBundle.message("filter.sort.clear")) { tableModel.clearSort(); afterQueryChange(grid) })
        }
        menu.addSeparator()

        menu.add(
            menuItem(TableKitBundle.message("filter.contains")) {
                ask(TableKitBundle.message("filter.contains.prompt", info.name))?.let {
                    tableModel.filterBy(ColumnFilter.Contains(info.name, it))
                    afterQueryChange(grid)
                }
            },
        )
        menu.add(
            menuItem(TableKitBundle.message("filter.equals")) {
                ask(TableKitBundle.message("filter.equals.prompt", info.name))?.let {
                    tableModel.filterBy(ColumnFilter.Equals(info.name, it))
                    afterQueryChange(grid)
                }
            },
        )
        menu.add(menuItem(TableKitBundle.message("filter.nulls")) { tableModel.filterBy(ColumnFilter.IsNull(info.name)); afterQueryChange(grid) })
        menu.add(menuItem(TableKitBundle.message("filter.notNulls")) { tableModel.filterBy(ColumnFilter.IsNotNull(info.name)); afterQueryChange(grid) })

        if (tableModel.query.filterOn(info.name) != null) {
            menu.add(menuItem(TableKitBundle.message("filter.clear.column")) { tableModel.clearFilterOn(info.name); afterQueryChange(grid) })
        }
        if (tableModel.query.filters.isNotEmpty()) {
            menu.add(
                menuItem(TableKitBundle.message("filter.clear.all")) {
                    filterField.text = ""
                    tableModel.clearFilters()
                    afterQueryChange(grid)
                },
            )
        }
        return menu
    }

    /** Two full scans of one column, so it runs off the EDT like everything else. */
    private fun showStatistics(tableModel: TabularTableModel, column: ColumnInfo, where: RelativePoint) {
        val opened = source ?: return
        val query = tableModel.query

        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.text = TableKitBundle.message("stats.computing", column.name)

        executor.execute {
            val result = runCatching { opened.statistics(query, column) }
            onEdt {
                if (disposed) return@onEdt
                result
                    .onSuccess { statistics ->
                        updateStatus()
                        JBPopupFactory.getInstance()
                            .createComponentPopupBuilder(ColumnStatisticsPanel(column, statistics), null)
                            .setResizable(true)
                            .setMovable(true)
                            .setRequestFocus(true)
                            .createPopup()
                            .show(where)
                    }
                    .onFailure { failure ->
                        showPageError((failure as? TableSourceException)?.message ?: TableKitBundle.message("stats.failed"))
                    }
            }
        }
    }

    private fun menuItem(text: String, action: () -> Unit) = JMenuItem(text).apply { addActionListener { action() } }

    private fun ask(prompt: String): String? =
        Messages.showInputDialog(this, prompt, TableKitBundle.message("filter.title"), null)?.takeIf { it.isNotEmpty() }

    private fun afterQueryChange(grid: JBTable) {
        grid.tableHeader.repaint()
        grid.scrollRectToVisible(Rectangle(0, 0, 1, 1))
        updateStatus()
    }

    /** Sizes columns from the first loaded page - a default width per column is useless. */
    private fun sizeColumnsToContent(grid: JBTable, tableModel: TabularTableModel) {
        val cellMetrics = grid.getFontMetrics(grid.font)
        val headerMetrics = grid.tableHeader.getFontMetrics(grid.tableHeader.font)
        val sampleRows = minOf(tableModel.rowCount, SAMPLE_ROWS)

        for (columnIndex in 0 until tableModel.columnCount) {
            val info = tableModel.columns[columnIndex]
            var width = headerMetrics.stringWidth(info.name + "  " + info.typeName) + JBUI.scale(28)
            for (row in 0 until sampleRows) {
                val value = tableModel.getValueAt(row, columnIndex) ?: continue
                width = maxOf(width, cellMetrics.stringWidth(value.take(MAX_MEASURED_CHARS)) + JBUI.scale(16))
            }
            grid.columnModel.getColumn(columnIndex).preferredWidth =
                width.coerceIn(JBUI.scale(MIN_COLUMN_WIDTH), JBUI.scale(MAX_COLUMN_WIDTH))
        }
    }

    // --- status -------------------------------------------------------------

    private fun updateStatus() {
        val opened = source ?: return
        rowNumbers?.updateWidth()
        val shown = model?.filteredRowCount ?: opened.rowCount
        val rows = if (shown == opened.rowCount) {
            TableKitBundle.message("editor.rows", ROW_FORMAT.format(shown))
        } else {
            TableKitBundle.message("editor.rows.filtered", ROW_FORMAT.format(shown), ROW_FORMAT.format(opened.rowCount))
        }
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.text = TableKitBundle.message(
            "editor.status",
            format.displayName,
            rows,
            opened.columns.size,
            StringUtil.formatFileSize(file.length),
        )
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

        const val FILTER_DELAY_MS = 300
        const val SAMPLE_ROWS = 50
        const val MAX_MEASURED_CHARS = 80
        const val MIN_COLUMN_WIDTH = 60
        const val MAX_COLUMN_WIDTH = 400
    }
}
