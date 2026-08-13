package com.kitworks.tablekit.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
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
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.text.NumberFormat
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JTable
import javax.swing.KeyStroke
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
    private val filterField = SearchTextField(false)
    private val chips = FilterChipsPanel(::removeFilter, ::clearFilters)
    private val toolbarRow = JBPanel<JBPanel<*>>(BorderLayout())
    private val toolbarLeft = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3)))

    /** Debounces typing so a filter is one query, not one query per keystroke. */
    private val filterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var center: JComponent? = null
    private var source: TableSource? = null
    private var model: TabularTableModel? = null
    private var table: JBTable? = null
    private var rowNumbers: RowNumberTable? = null
    private var actionToolbar: com.intellij.openapi.actionSystem.ActionToolbar? = null
    private var sheetIndex = 0
    private var columnsSized = false

    @Volatile
    private var disposed = false

    init {
        add(createStatusBar(), BorderLayout.SOUTH)
        add(createNorth(), BorderLayout.NORTH)
        installShortcuts()
        open()
    }

    val preferredFocusedComponent: JComponent get() = table ?: this

    // --- opening ------------------------------------------------------------

    private fun open() {
        columnsSized = false
        toolbarRow.isVisible = false
        chips.isVisible = false
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

        val tableModel = TabularTableModel(
            source = opened,
            executor = executor,
            onError = ::showPageError,
            onRowCountChanged = { updateStatus() },
            onQueryChanged = { refreshQueryUi() },
        )
        val grid = JBTable(tableModel).apply {
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            cellSelectionEnabled = true
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            setStriped(true)
            setExpandableItemsEnabled(true)
            setDefaultRenderer(String::class.java, TabularCellRenderer(tableModel.columns, tableModel::isLoaded))
            tableHeader.reorderingAllowed = false
            tableHeader.defaultRenderer = TabularHeaderRenderer(
                tableHeader.defaultRenderer,
                tableModel.columns,
            ) { column -> tableModel.query.sortKeyFor(column)?.descending }
            tableHeader.addMouseListener(headerMouse(this, tableModel))
            addMouseListener(cellMouse(this))
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
        buildToolbar(opened)
        // The actions are disabled until a file is open; tell the toolbar that
        // one now is, instead of waiting for whatever would refresh it next.
        actionToolbar?.updateActionsAsync()
        refreshQueryUi()
        setCenter(JBScrollPane(grid).apply { setRowHeaderView(numbers) })
    }

    private fun showFailure(failure: Throwable) {
        val message = (failure as? TableSourceException)?.message
            ?: failure.message
            ?: TableKitBundle.message("editor.error.unknown")
        setCenter(
            JBPanelWithEmptyText()
                .withEmptyText(TableKitBundle.message("editor.error.title", file.name))
                .apply {
                    emptyText.appendLine(message)
                    emptyText.appendLine("")
                    emptyText.appendLine(
                        TableKitBundle.message("editor.error.retry"),
                        SimpleTextAttributes.LINK_ATTRIBUTES,
                    ) { open() }
                },
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

    // --- chrome -------------------------------------------------------------

    private fun createNorth(): JComponent {
        filterField.textEditor.emptyText.text = TableKitBundle.message("editor.filter.hint")
        filterField.textEditor.columns = FILTER_COLUMNS
        filterField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                model?.let(::scheduleFilter)
            }
        })
        filterField.addKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode != KeyEvent.VK_ESCAPE) return
                filterField.text = ""
                table?.requestFocusInWindow()
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions(), true)
        toolbar.targetComponent = this
        actionToolbar = toolbar

        toolbarRow.add(toolbarLeft, BorderLayout.WEST)
        toolbarRow.add(toolbar.component, BorderLayout.EAST)
        toolbarRow.isVisible = false

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolbarRow, BorderLayout.NORTH)
            add(chips, BorderLayout.SOUTH)
        }
    }

    private fun actions() = DefaultActionGroup(
        action(
            TableKitBundle.message("action.statistics"),
            TableKitBundle.message("action.statistics.description"),
            AllIcons.General.InspectionsEye,
        ) { showStatisticsForSelection() },
        action(
            TableKitBundle.message("action.export"),
            TableKitBundle.message("action.export.description"),
            AllIcons.ToolbarDecorator.Export,
        ) { exportRows() },
        action(
            TableKitBundle.message("action.reload"),
            TableKitBundle.message("action.reload.description"),
            AllIcons.Actions.Refresh,
        ) { open() },
    )

    private fun action(text: String, description: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = model != null
            }

            override fun actionPerformed(event: AnActionEvent) = run()
        }

    private fun buildToolbar(opened: TableSource) {
        toolbarLeft.removeAll()

        if (opened.sheets.size > 1) {
            toolbarLeft.add(JBLabel(TableKitBundle.message("editor.sheet")))
            toolbarLeft.add(
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

        filterField.text = ""
        toolbarLeft.add(filterField)
        toolbarRow.isVisible = true
        toolbarRow.revalidate()
        toolbarRow.repaint()
    }

    private fun installShortcuts() {
        val find = ActionManager.getInstance().getAction(IdeActions_FIND)?.shortcutSet
        if (find != null) {
            DumbAwareAction.create {
                filterField.textEditor.requestFocusInWindow()
                filterField.textEditor.selectAll()
            }.registerCustomShortcutSet(find, this, this)
        }

        DumbAwareAction.create { open() }
            .registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0)), this, this)

        DumbAwareAction.create { showSelectedValue() }
            .registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), this, this)
    }

    private fun scheduleFilter(tableModel: TabularTableModel) {
        filterAlarm.cancelAllRequests()
        filterAlarm.addRequest({ tableModel.filterByText(filterField.text) }, FILTER_DELAY_MS)
    }

    // --- what the user does -------------------------------------------------

    private fun removeFilter(filter: ColumnFilter) {
        if (filter is ColumnFilter.AnyColumnContains) filterField.text = ""
        model?.clearFilterOn(filter.column)
    }

    private fun clearFilters() {
        filterField.text = ""
        model?.clearFilters()
    }

    /** Shows the selected cell in full - the truncated ones are the interesting ones. */
    private fun showSelectedValue() {
        val grid = table ?: return
        val tableModel = model ?: return
        val row = grid.selectedRow
        val column = grid.selectedColumn
        if (row < 0 || column < 0) return

        val modelColumn = grid.convertColumnIndexToModel(column)
        val cell = grid.getCellRect(row, column, true)
        CellValueViewer
            .popup(tableModel.columns[modelColumn], tableModel.getValueAt(row, modelColumn))
            .show(RelativePoint(grid, Point(cell.x, cell.y + cell.height)))
    }

    private fun showStatisticsForSelection() {
        val grid = table ?: return
        val tableModel = model ?: return
        val column = grid.selectedColumn.takeIf { it >= 0 }?.let(grid::convertColumnIndexToModel) ?: 0
        showStatistics(tableModel, tableModel.columns[column], RelativePoint.getSouthWestOf(toolbarRow))
    }

    /**
     * Writes what the user is looking at - filters and sort included, every row
     * rather than the page on screen. The engine writes the file, so this is
     * also how a Parquet file becomes a CSV.
     */
    private fun exportRows() {
        val opened = source ?: return
        val tableModel = model ?: return

        // Deprecated from 2025.1 onwards, and the only constructor 2024.2 has.
        // It stays until sinceBuild moves past 242. The plugin verifier reports
        // it as an informational deprecation, not a compatibility problem.
        @Suppress("DEPRECATION")
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

    private fun cellMouse(grid: JBTable) = object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (event.isPopupTrigger) return
            if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) showSelectedValue()
        }

        override fun mousePressed(event: MouseEvent) = maybeShowMenu(event)

        override fun mouseReleased(event: MouseEvent) = maybeShowMenu(event)

        private fun maybeShowMenu(event: MouseEvent) {
            if (!event.isPopupTrigger) return
            val row = grid.rowAtPoint(event.point)
            val column = grid.columnAtPoint(event.point)
            if (row < 0 || column < 0) return
            // Right clicking outside the selection moves it, as everywhere else.
            if (!grid.isCellSelected(row, column)) grid.changeSelection(row, column, false, false)
            cellMenu(grid).show(grid, event.x, event.y)
        }
    }

    /**
     * The same actions as the toolbar, where the pointer already is. A viewer
     * whose only affordances live in a toolbar makes people hunt.
     */
    private fun cellMenu(grid: JBTable): JBPopupMenu {
        val menu = JBPopupMenu()
        menu.add(menuItem(TableKitBundle.message("action.value")) { showSelectedValue() })
        menu.add(menuItem(TableKitBundle.message("action.copy")) { copySelection(grid) })
        menu.addSeparator()
        menu.add(menuItem(TableKitBundle.message("stats.action")) { showStatisticsForSelection() })
        menu.add(menuItem(TableKitBundle.message("action.export")) { exportRows() })
        return menu
    }

    /** Swing already copies a selection as tab separated text; use its action. */
    private fun copySelection(grid: JBTable) =
        javax.swing.TransferHandler.getCopyAction()
            .actionPerformed(java.awt.event.ActionEvent(grid, java.awt.event.ActionEvent.ACTION_PERFORMED, "copy"))

    private fun headerMouse(grid: JBTable, tableModel: TabularTableModel) = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) = maybeShowMenu(event)

        override fun mouseReleased(event: MouseEvent) = maybeShowMenu(event)

        override fun mouseClicked(event: MouseEvent) {
            if (event.isPopupTrigger || event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
            // A click on the resize handle is a resize, not a sort.
            if (grid.tableHeader.cursor.type == Cursor.E_RESIZE_CURSOR) return

            val column = columnAt(grid, event) ?: return
            tableModel.sortBy(column)
        }

        private fun maybeShowMenu(event: MouseEvent) {
            if (!event.isPopupTrigger) return
            val column = columnAt(grid, event) ?: return
            val where = RelativePoint(grid.tableHeader, Point(event.x, event.y))
            columnMenu(tableModel, column, where).show(grid.tableHeader, event.x, event.y)
        }
    }

    private fun columnAt(grid: JBTable, event: MouseEvent): Int? =
        grid.columnAtPoint(event.point).takeIf { it >= 0 }?.let(grid::convertColumnIndexToModel)

    private fun columnMenu(tableModel: TabularTableModel, column: Int, where: RelativePoint): JBPopupMenu {
        val info: ColumnInfo = tableModel.columns[column]
        val menu = JBPopupMenu(info.name)

        menu.add(menuItem(TableKitBundle.message("stats.action")) { showStatistics(tableModel, info, where) })
        menu.addSeparator()

        menu.add(menuItem(TableKitBundle.message("filter.sort.asc")) { tableModel.sortBy(column, descending = false) })
        menu.add(menuItem(TableKitBundle.message("filter.sort.desc")) { tableModel.sortBy(column, descending = true) })
        if (tableModel.query.sortKeyFor(info.name) != null) {
            menu.add(menuItem(TableKitBundle.message("filter.sort.clear")) { tableModel.clearSort() })
        }
        menu.addSeparator()

        menu.add(
            menuItem(TableKitBundle.message("filter.contains")) {
                ask(TableKitBundle.message("filter.contains.prompt", info.name))?.let {
                    tableModel.filterBy(ColumnFilter.Contains(info.name, it))
                }
            },
        )
        menu.add(
            menuItem(TableKitBundle.message("filter.equals")) {
                ask(TableKitBundle.message("filter.equals.prompt", info.name))?.let {
                    tableModel.filterBy(ColumnFilter.Equals(info.name, it))
                }
            },
        )
        menu.add(menuItem(TableKitBundle.message("filter.nulls")) { tableModel.filterBy(ColumnFilter.IsNull(info.name)) })
        menu.add(menuItem(TableKitBundle.message("filter.notNulls")) { tableModel.filterBy(ColumnFilter.IsNotNull(info.name)) })

        if (tableModel.query.filterOn(info.name) != null) {
            menu.add(menuItem(TableKitBundle.message("filter.clear.column")) { tableModel.clearFilterOn(info.name) })
        }
        if (tableModel.query.filters.isNotEmpty()) {
            menu.add(menuItem(TableKitBundle.message("filter.clear.all")) { clearFilters() })
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

    // --- what the query looks like ------------------------------------------

    private fun refreshQueryUi() {
        val tableModel = model ?: return
        chips.show(tableModel.query.filters)
        updateEmptyText(tableModel)
        table?.let { grid ->
            grid.tableHeader.repaint()
            grid.scrollRectToVisible(Rectangle(0, 0, 1, 1))
        }
        updateStatus()
    }

    /** An empty grid must say why it is empty, and offer the way out. */
    private fun updateEmptyText(tableModel: TabularTableModel) {
        val emptyText = table?.emptyText ?: return
        emptyText.clear()
        if (tableModel.query.filters.isEmpty()) {
            emptyText.text = TableKitBundle.message("grid.empty")
        } else {
            emptyText.text = TableKitBundle.message("grid.empty.filtered")
            emptyText.appendSecondaryText(
                TableKitBundle.message("grid.empty.clear"),
                SimpleTextAttributes.LINK_ATTRIBUTES,
            ) { clearFilters() }
        }
    }

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

        const val IdeActions_FIND = "Find"
        const val TOOLBAR_PLACE = "TableKitEditorToolbar"
        const val FILTER_COLUMNS = 22
        const val FILTER_DELAY_MS = 300
        const val SAMPLE_ROWS = 50
        const val MAX_MEASURED_CHARS = 80
        const val MIN_COLUMN_WIDTH = 60
        const val MAX_COLUMN_WIDTH = 400
    }
}
