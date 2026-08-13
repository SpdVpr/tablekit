package com.kitworks.tablekit.editor

import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.data.DuckDb
import com.kitworks.tablekit.data.Query
import com.kitworks.tablekit.data.Sql
import com.kitworks.tablekit.data.TableSource
import com.kitworks.tablekit.format.TabularFormat
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.UIManager

/**
 * Paints the editor into PNGs: visual evidence that the layout works, and the
 * source of the pictures on the Marketplace listing.
 *
 * Opt in with -Ptablekit.screenshots=true; images land in build/screenshots.
 *
 * Two caveats. Colours come from whatever look and feel the test framework
 * installs, and the action toolbar draws nothing outside a real window - the
 * buttons are there (see [ToolbarTest]), they just do not paint headlessly.
 */
class ScreenshotsTest : BasePlatformTestCase() {

    fun `test render every screen`() {
        if (System.getProperty("tablekit.screenshots") == null) return

        renderParquet()
        renderFiltered()
        renderExcel()
    }

    // --- the screens --------------------------------------------------------

    private fun renderParquet() {
        val file = parquet("orders.parquet", ORDERS)
        withEditor(file) { editor, grid ->
            val model = grid.model as TabularTableModel
            awaitPage(model)

            write(editor.component, "01-parquet.png")

            inDarkTheme(editor.component) { write(editor.component, "02-parquet-dark.png") }

            val base = image(editor.component)
            TableSource.open(Paths.get(file.path), TabularFormat.PARQUET).use { source ->
                val amount = source.columns[3]
                val statistics = ColumnStatisticsPanel(amount, source.statistics(Query(), amount))
                write(overlay(base, statistics, x = 470, y = 90), "03-statistics.png")

                val nested = source.columns[5]
                val value = source.fetchPage(Query(), 0, 1).rows.single()[5]
                write(overlay(base, CellValueViewer.component(nested, value), x = 380, y = 150), "04-value.png")
            }
        }
    }

    private fun renderFiltered() {
        val file = parquet("filtered.parquet", ORDERS)
        withEditor(file) { editor, grid ->
            val model = grid.model as TabularTableModel
            awaitPage(model)

            // Typed into the field the user would type into, so the picture
            // shows the filter and the debounce gets exercised.
            val field = checkNotNull(UIUtil.findComponentOfType(editor.component, com.intellij.ui.SearchTextField::class.java))
            field.text = "Prague"
            PlatformTestUtil.waitWithEventsDispatching("the filter never applied", { model.rowCount in 1..4999 }, 30)
            model.getValueAt(0, 0)
            PlatformTestUtil.waitWithEventsDispatching("the filtered page never arrived", { model.isLoaded(0) }, 30)

            write(editor.component, "05-filtered.png")
            // The filtered state is the one picture that cannot be automated in a
            // real IDE - typing is not available there - so it is also rendered
            // dark, to serve as the reference for taking it by hand.
            inDarkTheme(editor.component) { write(editor.component, "05-filtered-dark.png") }
        }
    }

    private fun renderExcel() {
        val file = workbook()
        withEditor(file) { editor, grid ->
            awaitPage(grid.model as TabularTableModel)
            write(editor.component, "06-excel.png")
        }
    }

    // --- painting -----------------------------------------------------------

    private fun write(component: JComponent, name: String) = write(image(component), name)

    private fun image(component: JComponent): BufferedImage {
        // Two things the IDE does on addNotify(), which never runs without a
        // window: a table installs its header into the enclosing scroll pane,
        // and a toolbar builds its buttons. Both by hand, so the picture shows
        // as much as a headless render can.
        UIUtil.uiTraverser(component).traverse()
            .filter(ActionToolbarImpl::class.java)
            .forEach { it.updateActionsImmediately() }
        UIUtil.uiTraverser(component).traverse()
            .filter(JScrollPane::class.java)
            .forEach { scrollPane ->
                (scrollPane.viewport?.view as? JBTable)?.let { scrollPane.setColumnHeaderView(it.tableHeader) }
            }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        component.setSize(WIDTH, HEIGHT)
        component.doLayout()
        UIUtil.uiTraverser(component).traverse().forEach { it.doLayout() }

        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** Paints a popup's content over a screen, the way the IDE would stack them. */
    private fun overlay(base: BufferedImage, content: JComponent, x: Int, y: Int): BufferedImage {
        content.size = content.preferredSize
        content.doLayout()
        UIUtil.uiTraverser(content).traverse().forEach { it.doLayout() }

        val popup = BufferedImage(content.width, content.height, BufferedImage.TYPE_INT_RGB)
        popup.createGraphics().use { graphics ->
            graphics.color = UIUtil.getPanelBackground()
            graphics.fillRect(0, 0, popup.width, popup.height)
            content.paint(graphics)
        }

        val result = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_RGB)
        result.createGraphics().use { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(base, 0, 0, null)
            graphics.color = Color(0, 0, 0, 40)
            graphics.fillRoundRect(x + 3, y + 4, popup.width, popup.height, 10, 10)
            graphics.drawImage(popup, x, y, null)
            graphics.color = UIUtil.getBoundsColor()
            graphics.drawRect(x, y, popup.width - 1, popup.height - 1)
        }
        return result
    }

    private fun write(image: BufferedImage, name: String) {
        val directory = Paths.get("build", "screenshots").toAbsolutePath()
        Files.createDirectories(directory)
        val target = directory.resolve(name)
        ImageIO.write(image, "png", target.toFile())
        println("[screenshot] $target")
    }

    private fun inDarkTheme(component: JComponent, block: () -> Unit) {
        val previous = UIManager.getLookAndFeel()
        try {
            UIManager.setLookAndFeel(com.intellij.ide.ui.laf.darcula.DarculaLaf())
            javax.swing.SwingUtilities.updateComponentTreeUI(component)
            block()
        } catch (e: Throwable) {
            println("[screenshot] dark theme unavailable: " + e.message)
        } finally {
            runCatching {
                UIManager.setLookAndFeel(previous)
                javax.swing.SwingUtilities.updateComponentTreeUI(component)
            }
        }
    }

    private inline fun java.awt.Graphics2D.use(block: (java.awt.Graphics2D) -> Unit) {
        try {
            block(this)
        } finally {
            dispose()
        }
    }

    // --- fixtures -----------------------------------------------------------

    private fun withEditor(file: VirtualFile, block: (FileEditor, JBTable) -> Unit) {
        val editor = BinaryTabularFileEditorProvider().createEditor(project, file)
        try {
            var grid: JBTable? = null
            PlatformTestUtil.waitWithEventsDispatching(
                "the grid never appeared",
                {
                    grid = UIUtil.findComponentOfType(editor.component, JBTable::class.java)
                    grid != null
                },
                30,
            )
            block(editor, grid!!)
        } finally {
            Disposer.dispose(editor)
        }
    }

    private fun awaitPage(model: TabularTableModel) {
        model.getValueAt(0, 0)
        PlatformTestUtil.waitWithEventsDispatching("the first page never arrived", { model.isLoaded(0) }, 30)
    }

    private fun parquet(name: String, select: String): VirtualFile {
        val path = directory().resolve(name)
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY ($select) TO ${Sql.literal(path.toString())} (FORMAT PARQUET)")
            }
        }
        return refresh(path)
    }

    private fun workbook(): VirtualFile {
        val path = directory().resolve("q3-report.xlsx")
        XSSFWorkbook().use { book ->
            val dateStyle = book.createCellStyle().apply {
                dataFormat = book.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
            }
            listOf("Summary", "Invoices", "Notes").forEach { name ->
                val sheet = book.createSheet(name)
                sheet.createRow(0).let { header ->
                    listOf("invoice", "customer", "issued", "amount", "paid").forEachIndexed { index, title ->
                        header.createCell(index).setCellValue(title)
                    }
                }
                for (index in 1..60) {
                    val row = sheet.createRow(index)
                    row.createCell(0).setCellValue("INV-2026-%04d".format(index))
                    row.createCell(1).setCellValue(CUSTOMERS[index % CUSTOMERS.size])
                    row.createCell(2).apply {
                        setCellValue(java.time.LocalDate.of(2026, 7, 1).plusDays(index.toLong()).atStartOfDay())
                        cellStyle = dateStyle
                    }
                    row.createCell(3).setCellValue((index * 1375) % 90000 / 10.0)
                    row.createCell(4).setCellValue(index % 3 != 0)
                }
            }
            Files.newOutputStream(path).use(book::write)
        }
        return refresh(path)
    }

    private fun directory(): Path = FileUtil.createTempDirectory("tablekit-shots", null, true).toPath()

    private fun refresh(path: Path): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))

    private companion object {
        const val WIDTH = 1180
        const val HEIGHT = 560

        val CUSTOMERS = listOf("Ada Lovelace", "Linus Torvalds", "Grace Hopper", "Alan Turing", "Barbara Liskov")

        val ORDERS = """
            SELECT
                i AS order_id,
                'INV-2026-' || lpad(CAST(i AS VARCHAR), 5, '0') AS invoice,
                ['Ada Lovelace', 'Linus Torvalds', 'Grace Hopper', 'Alan Turing'][(i % 4) + 1] AS customer,
                CASE WHEN i % 11 = 0 THEN NULL ELSE round(((i * 37) % 90000) / 7.0, 2) END AS amount,
                TIMESTAMP '2026-01-01 08:00:00' + INTERVAL (i * 3607) SECOND AS placed_at,
                {'city': ['Prague', 'Brno', 'Ostrava'][(i % 3) + 1],
                 'street': 'Dlouha ' || (i % 90 + 1),
                 'zip': 10000 + i} AS address,
                ['new', 'paid', 'shipped'][(i % 3) + 1] AS status
            FROM range(0, 5000) t(i)
        """.trimIndent()
    }
}
