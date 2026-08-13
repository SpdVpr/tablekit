package com.kitworks.tablekit

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kitworks.tablekit.editor.BinaryTabularFileEditorProvider
import com.kitworks.tablekit.editor.TabularFileEditorProviderBase
import com.kitworks.tablekit.editor.TextTabularFileEditorProvider
import com.kitworks.tablekit.filetype.ParquetFileType

/**
 * Phase 0 acceptance: the IDE really hands a .parquet file to TableKit, and it
 * really does not steal .csv from whatever editor already owns it.
 */
class TabularEditorRegistrationTest : BasePlatformTestCase() {

    fun `test parquet extension resolves to the TableKit file type`() {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName("data.parquet")
        assertEquals(ParquetFileType.INSTANCE, fileType)
        assertTrue(fileType.isBinary)
    }

    fun `test binary formats are opened by TableKit alone`() {
        for (name in listOf("data.parquet", "book.xlsx", "events.avro")) {
            val providers = providersFor(name)
            val ours = providers.filterIsInstance<BinaryTabularFileEditorProvider>()
            assertEquals("expected exactly one TableKit provider for $name", 1, ours.size)
            assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, ours.single().policy)
        }
    }

    fun `test text formats keep their default editor and get TableKit as an extra tab`() {
        for (name in listOf("data.csv", "data.tsv", "events.jsonl")) {
            val providers = providersFor(name)
            val ours = providers.filterIsInstance<TextTabularFileEditorProvider>()
            assertEquals("expected exactly one TableKit provider for $name", 1, ours.size)
            assertEquals(FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR, ours.single().policy)
            assertTrue(
                "TableKit must not be the first provider for $name",
                providers.indexOf(ours.single()) > 0,
            )
        }
    }

    fun `test unrelated files are ignored`() {
        val providers = providersFor("readme.txt")
        assertTrue(providers.none { it is TabularFileEditorProviderBase })
    }

    fun `test editor opens and reports the file it shows`() {
        val file = myFixture.tempDirFixture.createFile("open-me.parquet")
        val provider = BinaryTabularFileEditorProvider()
        assertTrue(provider.accept(project, file))

        val editor = provider.createEditor(project, file)
        try {
            assertEquals(file, editor.file)
            assertNotNull(editor.component)
            assertFalse(editor.isModified)
            assertTrue(editor.isValid)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    private fun providersFor(name: String) =
        FileEditorProviderManager.getInstance().getProviderList(project, myFixture.tempDirFixture.createFile(name))
}
