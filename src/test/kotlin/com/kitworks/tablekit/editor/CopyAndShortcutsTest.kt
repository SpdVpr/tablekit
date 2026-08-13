package com.kitworks.tablekit.editor

import com.intellij.openapi.actionSystem.AnAction
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
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.TransferHandler

/**
 * Two things the plugin advertises that nothing else in the suite touches:
 * copying a selection out, and the keys that reach the filter and the reload.
 */
class CopyAndShortcutsTest : BasePlatformTestCase() {

    fun `test a selection copies out as tab separated text`() {
        withGrid { grid ->
            grid.setRowSelectionInterval(0, 1)
            grid.setColumnSelectionInterval(0, 1)

            // A clipboard of our own, so the test neither reads nor disturbs the
            // one the person running it is using.
            val clipboard = Clipboard("test")
            grid.transferHandler.exportToClipboard(grid, clipboard, TransferHandler.COPY)

            val copied = clipboard.getData(DataFlavor.stringFlavor) as String
            assertEquals(
                listOf("1\tAda", "2\tLinus"),
                copied.trim().lines().map { it.trim('\r') },
            )
        }
    }

    fun `test a null copies as nothing rather than as the word null`() {
        withGrid { grid ->
            grid.setRowSelectionInterval(2, 2)
            grid.setColumnSelectionInterval(1, 1)

            val clipboard = Clipboard("test")
            grid.transferHandler.exportToClipboard(grid, clipboard, TransferHandler.COPY)

            assertEquals("", (clipboard.getData(DataFlavor.stringFlavor) as String).trim())
        }
    }

    fun `test the editor registers its keyboard shortcuts`() {
        withEditorComponent { component ->
            val panel = UIUtil.findComponentOfType(component, TabularEditorPanel::class.java)
            val actions = panel?.getClientProperty(AnAction.ACTIONS_KEY) as? List<*>
            assertEquals(
                "focus the filter, reload, and show the value",
                3,
                actions?.size,
            )
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun withGrid(assertions: (JBTable) -> Unit) = withEditorComponent { component ->
        val grid = checkNotNull(UIUtil.findComponentOfType(component, JBTable::class.java))
        val model = grid.model as TabularTableModel
        model.getValueAt(0, 0)
        PlatformTestUtil.waitWithEventsDispatching("the first page never arrived", { model.isLoaded(0) }, 30)
        assertions(grid)
    }

    private fun withEditorComponent(assertions: (JComponent) -> Unit) {
        val file = parquet()
        val editor = BinaryTabularFileEditorProvider().createEditor(project, file)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "the grid never appeared",
                { UIUtil.findComponentOfType(editor.component, JBTable::class.java) != null },
                30,
            )
            assertions(editor.component)
        } finally {
            Disposer.dispose(editor)
        }
    }

    private fun parquet(): VirtualFile {
        val path: Path = FileUtil.createTempDirectory("tablekit-copy", null, true).toPath().resolve("people.parquet")
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "COPY (SELECT * FROM (VALUES (1, 'Ada'), (2, 'Linus'), (3, NULL)) t(id, name)) " +
                        "TO ${Sql.literal(path.toString())} (FORMAT PARQUET)",
                )
            }
        }
        return checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }
}
