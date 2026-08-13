package com.kitworks.tablekit.editor

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.data.DuckDb
import com.kitworks.tablekit.data.Sql
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * Paints the editor into a PNG so the layout can be looked at without starting
 * an IDE. Opt in with -Ptablekit.screenshots=true; images land in
 * build/screenshots.
 *
 * Colours come from whatever look and feel the headless test framework sets up,
 * so this checks layout and content, not theming.
 */
class GridScreenshotTest : BasePlatformTestCase() {

    fun `test render the grid`() {
        if (System.getProperty("tablekit.screenshots") == null) return

        val file = parquet(
            "orders.parquet",
            """
            SELECT
                i AS order_id,
                ['Ada Lovelace', 'Linus Torvalds', 'Grace Hopper', 'Alan Turing'][(i % 4) + 1] AS customer,
                CASE WHEN i % 7 = 0 THEN NULL ELSE round(((i * 37) % 900) / 7.0, 2) END AS total,
                TIMESTAMP '2026-01-01 08:00:00' + INTERVAL (i * 3607) SECOND AS placed_at,
                ['new', 'paid', 'shipped'][(i % 3) + 1] AS status,
                {'city': ['Prague', 'Brno', 'Ostrava'][(i % 3) + 1], 'zip': 10000 + i} AS address
            FROM range(0, 5000) t(i)
            """.trimIndent(),
        )

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
            // Asking for a cell is what schedules its page; then let the column
            // sizing pass run before anything is painted.
            val model = grid!!.model as TabularTableModel
            model.getValueAt(0, 0)
            PlatformTestUtil.waitWithEventsDispatching("the first page never arrived", { model.isLoaded(0) }, 30)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            write(editor.component, "grid.png")

            // Every colour TableKit picks comes from the theme, so the dark
            // rendering is a check that none of them was hard coded.
            inDarkTheme {
                javax.swing.SwingUtilities.updateComponentTreeUI(editor.component)
                write(editor.component, "grid-dark.png")
            }
            javax.swing.SwingUtilities.updateComponentTreeUI(editor.component)

            // The statistics popup cannot be opened without a window, but the
            // panel it shows can be painted on its own.
            val source = com.kitworks.tablekit.data.TableSource.open(
                java.nio.file.Paths.get(file.path),
                com.kitworks.tablekit.format.TabularFormat.PARQUET,
            )
            source.use {
                val column = it.columns[1]
                val panel = ColumnStatisticsPanel(column, it.statistics(com.kitworks.tablekit.data.Query(), column))
                panel.size = panel.preferredSize
                write(panel, "statistics.png", panel.preferredSize.width, panel.preferredSize.height)
            }
        } finally {
            Disposer.dispose(editor)
        }
    }

    /** Repaints under Darcula if this platform build lets a test install it. */
    private fun inDarkTheme(block: () -> Unit) {
        val previous = javax.swing.UIManager.getLookAndFeel()
        try {
            javax.swing.UIManager.setLookAndFeel(com.intellij.ide.ui.laf.darcula.DarculaLaf())
            block()
        } catch (e: Throwable) {
            println("[screenshot] dark theme unavailable: " + e.message)
        } finally {
            runCatching { javax.swing.UIManager.setLookAndFeel(previous) }
        }
    }

    private fun write(component: javax.swing.JComponent, name: String, width: Int = 1200, height: Int = 620) {
        // Two things the IDE does on addNotify(), which never runs without a
        // window: a table installs its header into the enclosing scroll pane,
        // and a toolbar builds its buttons. Both by hand so the picture shows
        // what the IDE would show.
        UIUtil.uiTraverser(component).traverse()
            .filter(com.intellij.openapi.actionSystem.impl.ActionToolbarImpl::class.java)
            .forEach { it.updateActionsImmediately() }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        UIUtil.uiTraverser(component).traverse()
            .filter(javax.swing.JScrollPane::class.java)
            .forEach { scrollPane ->
                (scrollPane.viewport?.view as? JBTable)?.let { scrollPane.setColumnHeaderView(it.tableHeader) }
            }

        component.setSize(width, height)
        component.doLayout()
        UIUtil.uiTraverser(component).traverse().forEach { it.doLayout() }

        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }

        val directory = Paths.get("build", "screenshots").toAbsolutePath()
        Files.createDirectories(directory)
        val target = directory.resolve(name)
        ImageIO.write(image, "png", target.toFile())
        println("[screenshot] $target")
    }

    private fun parquet(name: String, select: String): VirtualFile {
        val path: Path = FileUtil.createTempDirectory("tablekit-shot", null, true).toPath().resolve(name)
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY ($select) TO ${Sql.literal(path.toString())} (FORMAT PARQUET)")
            }
        }
        return checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }
}
