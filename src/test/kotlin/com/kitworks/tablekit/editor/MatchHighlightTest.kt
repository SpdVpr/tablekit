package com.kitworks.tablekit.editor

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.data.ColumnFilter
import com.kitworks.tablekit.data.DuckDb
import com.kitworks.tablekit.data.Sql

/**
 * A filter that only removes rows leaves people guessing which column it
 * caught, so the grid marks the match. This is where that decision is checked.
 */
class MatchHighlightTest : BasePlatformTestCase() {

    fun `test free text marks the part of the cell it matched`() {
        withModel { model ->
            model.filterByText("ra")
            awaitFilter(model)

            assertEquals("Prague matches at r-a", 1 until 3, model.matchIn("Prague", 0))
            assertEquals(1 until 3, model.matchIn("Brasil", 0))
            assertNull("nothing to mark where it does not match", model.matchIn("Brno", 0))
        }
    }

    fun `test matching ignores case, like the filter itself does`() {
        withModel { model ->
            model.filterByText("PRAGUE")
            awaitFilter(model)

            assertEquals(0 until 6, model.matchIn("Prague", 0))
        }
    }

    fun `test a per column filter marks only its own column`() {
        withModel { model ->
            model.filterBy(ColumnFilter.Contains("city", "ra"))
            awaitFilter(model)

            assertEquals(1 until 3, model.matchIn("Prague", 0))
            assertNull("another column was not filtered", model.matchIn("Prague", 1))
        }
    }

    fun `test nothing is marked when nothing is filtered`() {
        withModel { model ->
            assertNull(model.matchIn("Prague", 0))
        }
    }

    private fun withModel(assertions: (TabularTableModel) -> Unit) {
        val path = FileUtil.createTempDirectory("tablekit-mark", null, true).toPath().resolve("cities.parquet")
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "COPY (SELECT * FROM (VALUES ('Prague', 1), ('Brno', 2)) t(city, id)) " +
                        "TO ${Sql.literal(path.toString())} (FORMAT PARQUET)",
                )
            }
        }
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))

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
            assertions(grid!!.model as TabularTableModel)
        } finally {
            Disposer.dispose(editor)
        }
    }

    private fun awaitFilter(model: TabularTableModel) =
        PlatformTestUtil.waitWithEventsDispatching("the filter never applied", { model.query.filters.isNotEmpty() }, 30)
}
