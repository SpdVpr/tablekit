package com.kitworks.tablekit.editor

import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.data.DuckDb
import com.kitworks.tablekit.data.Sql

/**
 * The toolbar carries export and reload, which have no other home but the
 * context menu - so it must actually build its buttons.
 */
class ToolbarTest : BasePlatformTestCase() {

    fun `test the toolbar builds its actions once a file is open`() {
        val path = FileUtil.createTempDirectory("tablekit-toolbar", null, true).toPath().resolve("data.parquet")
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY (SELECT 1 AS n) TO ${Sql.literal(path.toString())} (FORMAT PARQUET)")
            }
        }
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))

        val editor = BinaryTabularFileEditorProvider().createEditor(project, file)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "the grid never appeared",
                { UIUtil.findComponentOfType(editor.component, JBTable::class.java) != null },
                30,
            )

            val toolbar = checkNotNull(UIUtil.findComponentOfType(editor.component, ActionToolbarImpl::class.java)) {
                "the editor has no action toolbar"
            }
            toolbar.updateActionsImmediately()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertEquals("statistics, export and reload", 3, toolbar.componentCount)
            assertTrue(
                "the actions must be enabled once a file is open",
                toolbar.actions.all { it.templatePresentation.isEnabled },
            )
        } finally {
            Disposer.dispose(editor)
        }
    }
}
