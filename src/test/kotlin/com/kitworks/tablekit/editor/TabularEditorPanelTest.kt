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
import java.nio.file.Path

/**
 * End to end through the real editor: a file on disk, the provider the IDE
 * would use, and the grid the user would see.
 */
class TabularEditorPanelTest : BasePlatformTestCase() {

    fun `test the grid shows the rows of the file`() {
        val file = parquet("people.parquet", "SELECT * FROM (VALUES (1, 'Ada'), (2, 'Linus'), (3, NULL)) t(id, name)")

        withEditorFor(file) { table ->
            assertEquals(3, table.model.rowCount)
            assertEquals(2, table.model.columnCount)
            assertEquals("id", table.model.getColumnName(0))

            val model = table.model as TabularTableModel
            awaitPage(model)

            assertEquals("1", model.getValueAt(0, 0))
            assertEquals("Ada", model.getValueAt(0, 1))
            assertNull("SQL NULL must stay null", model.getValueAt(2, 1))
        }
    }

    fun `test sorting a column re-queries the file`() {
        val file = parquet("sorted.parquet", "SELECT * FROM (VALUES (2, 'b'), (1, 'a'), (3, 'c')) t(id, name)")

        withEditorFor(file) { table ->
            val model = table.model as TabularTableModel
            awaitPage(model)
            assertEquals("2", model.getValueAt(0, 0))

            model.sortBy(0)
            awaitPage(model)
            assertEquals("1", model.getValueAt(0, 0))

            model.sortBy(0)
            awaitPage(model)
            assertEquals("3", model.getValueAt(0, 0))

            model.sortBy(0)
            awaitPage(model)
            assertEquals("back to file order", "2", model.getValueAt(0, 0))
        }
    }

    /** More rows than one page: the grid must not have loaded them all. */
    fun `test only the visited pages are held in memory`() {
        val file = parquet("big.parquet", "SELECT i AS n FROM range(0, 10000) t(i)")

        withEditorFor(file) { table ->
            val model = table.model as TabularTableModel
            awaitPage(model)

            assertEquals(10000, model.rowCount)
            assertTrue(model.isLoaded(0))
            assertFalse("page must not be fetched before it is shown", model.isLoaded(9999))

            model.getValueAt(9999, 0)
            PlatformTestUtil.waitWithEventsDispatching("last page never arrived", { model.isLoaded(9999) }, TIMEOUT_SECONDS)
            assertEquals("9999", model.getValueAt(9999, 0))
        }
    }

    fun `test a corrupt file shows an error instead of a grid`() {
        val path = tempDirectory().resolve("broken.parquet")
        path.toFile().writeText("not a parquet file")
        val file = refresh(path)

        val editor = BinaryTabularFileEditorProvider().createEditor(project, file)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "the editor never reported the failure",
                { editor.component.hasText("Could not open") },
                TIMEOUT_SECONDS,
            )
            assertNull("no grid for an unreadable file", UIUtil.findComponentOfType(editor.component, JBTable::class.java))
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun `test the row numbers follow what the filter leaves`() {
        val file = parquet("cities.parquet", "SELECT * FROM (VALUES ('Prague'), ('Brno'), ('Ostrava'), ('Plzen'), ('Liberec')) t(city)")

        withEditorFor(file) { table ->
            val model = table.model as TabularTableModel
            awaitPage(model)

            val numbers = numbersOf(table)
            assertEquals(5, numbers.model.rowCount)
            assertEquals(1, numbers.model.getValueAt(0, 0))
            assertEquals(5, numbers.model.getValueAt(4, 0))

            model.filterByText("Pr")
            PlatformTestUtil.waitWithEventsDispatching("the filter never applied", { model.rowCount == 1 }, TIMEOUT_SECONDS)
            assertEquals("row numbers must count the filtered view", 1, numbers.model.rowCount)
        }
    }

    fun `test a filter that matches nothing explains itself`() {
        val file = parquet("empty.parquet", "SELECT * FROM (VALUES ('Prague'), ('Brno')) t(city)")

        withEditorFor(file) { table ->
            val model = table.model as TabularTableModel
            awaitPage(model)

            model.filterByText("nothing here matches")
            PlatformTestUtil.waitWithEventsDispatching("the filter never applied", { model.rowCount == 0 }, TIMEOUT_SECONDS)

            val emptyText = table.emptyText.text
            assertTrue("an empty grid must say why: '$emptyText'", emptyText.contains("No rows match"))
        }
    }

    /** The sheet dropdown reopens the file, which is more than setting a field. */
    fun `test switching a workbook sheet shows the other sheet`() {
        val path = tempDirectory().resolve("book.xlsx")
        org.apache.poi.xssf.usermodel.XSSFWorkbook().use { book ->
            listOf("First", "Second").forEachIndexed { index, name ->
                val sheet = book.createSheet(name)
                sheet.createRow(0).createCell(0).setCellValue("label")
                sheet.createRow(1).createCell(0).setCellValue("from-$name-$index")
            }
            java.nio.file.Files.newOutputStream(path).use(book::write)
        }
        val file = refresh(path)

        val editor = BinaryTabularFileEditorProvider().createEditor(project, file)
        try {
            var grid: JBTable? = null
            PlatformTestUtil.waitWithEventsDispatching(
                "the grid never appeared",
                {
                    grid = UIUtil.findComponentOfType(editor.component, JBTable::class.java)
                    grid != null
                },
                TIMEOUT_SECONDS,
            )
            awaitPage(grid!!.model as TabularTableModel)
            assertEquals("from-First-0", (grid!!.model as TabularTableModel).getValueAt(0, 0))

            val combo = checkNotNull(
                UIUtil.findComponentOfType(editor.component, com.intellij.openapi.ui.ComboBox::class.java),
            ) { "a workbook with two sheets must offer a sheet selector" }
            combo.selectedIndex = 1

            PlatformTestUtil.waitWithEventsDispatching(
                "the second sheet never loaded",
                {
                    val model = UIUtil.findComponentOfType(editor.component, JBTable::class.java)?.model
                    (model as? TabularTableModel)?.let { it.getValueAt(0, 0) == "from-Second-1" } ?: false
                },
                TIMEOUT_SECONDS,
            )
        } finally {
            Disposer.dispose(editor)
        }
    }

    /** Holding a file open would stop people deleting or replacing it on Windows. */
    fun `test closing the editor lets go of the file`() {
        val path = tempDirectory().resolve("released.parquet")
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY (SELECT i AS n FROM range(0, 1000) t(i)) TO ${Sql.literal(path.toString())} (FORMAT PARQUET)")
            }
        }
        val file = refresh(path)

        val editor = BinaryTabularFileEditorProvider().createEditor(project, file)
        var grid: JBTable? = null
        PlatformTestUtil.waitWithEventsDispatching(
            "the grid never appeared",
            {
                grid = UIUtil.findComponentOfType(editor.component, JBTable::class.java)
                grid != null
            },
            TIMEOUT_SECONDS,
        )
        awaitPage(grid!!.model as TabularTableModel)
        Disposer.dispose(editor)

        PlatformTestUtil.waitWithEventsDispatching(
            "the file was still locked after the editor closed",
            { runCatching { java.nio.file.Files.delete(path) }.isSuccess },
            TIMEOUT_SECONDS,
        )
        assertFalse(java.nio.file.Files.exists(path))
    }

    private fun numbersOf(table: JBTable): RowNumberTable {
        val scrollPane = checkNotNull(UIUtil.getParentOfType(javax.swing.JScrollPane::class.java, table)) { "grid is not in a scroll pane" }
        return checkNotNull(scrollPane.rowHeader?.view as? RowNumberTable) { "grid has no row numbers" }
    }

    // --- helpers ------------------------------------------------------------

    private fun withEditorFor(file: VirtualFile, assertions: (JBTable) -> Unit) {
        val editor = BinaryTabularFileEditorProvider().createEditor(project, file)
        try {
            var table: JBTable? = null
            PlatformTestUtil.waitWithEventsDispatching(
                "the grid never appeared",
                {
                    table = UIUtil.findComponentOfType(editor.component, JBTable::class.java)
                    table != null
                },
                TIMEOUT_SECONDS,
            )
            assertions(table!!)
        } finally {
            Disposer.dispose(editor)
        }
    }

    private fun awaitPage(model: TabularTableModel) {
        model.getValueAt(0, 0)
        PlatformTestUtil.waitWithEventsDispatching("the first page never arrived", { model.isLoaded(0) }, TIMEOUT_SECONDS)
    }

    private fun parquet(name: String, select: String): VirtualFile {
        val path = tempDirectory().resolve(name)
        DuckDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY ($select) TO ${Sql.literal(path.toString())} (FORMAT PARQUET)")
            }
        }
        return refresh(path)
    }

    private fun tempDirectory(): Path = FileUtil.createTempDirectory("tablekit-test", null, true).toPath()

    private fun refresh(path: Path): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) { "not in the VFS: $path" }

    private fun java.awt.Component.hasText(text: String): Boolean =
        UIUtil.uiTraverser(this).traverse().any { component ->
            when (component) {
                is javax.swing.JLabel -> component.text?.contains(text) == true
                is com.intellij.ui.components.JBPanelWithEmptyText -> component.emptyText.text.contains(text)
                else -> false
            }
        }

    private companion object {
        const val TIMEOUT_SECONDS = 30
    }
}
