package com.kitworks.tablekit.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.kitworks.tablekit.format.TabularFormat

/**
 * Two providers, one editor. They differ only in how aggressively they claim a
 * file - see [TabularFormat.binary].
 */
sealed class TabularFileEditorProviderBase(private val binaryFormats: Boolean) : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val format = TabularFormat.of(file) ?: return false
        return format.binary == binaryFormats
    }

    /** Extension check only - no read action needed, so file opening stays fast. */
    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val format = requireNotNull(TabularFormat.of(file)) { "Unsupported file: ${file.name}" }
        return TabularFileEditor(project, file, format)
    }
}

/** Parquet, Excel, Avro, ORC: no usable built-in editor exists, so we take over. */
class BinaryTabularFileEditorProvider : TabularFileEditorProviderBase(binaryFormats = true) {

    override fun getEditorTypeId(): String = "tablekit.binary"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * CSV, TSV, JSONL: the text editor (or the CSV Editor plugin, 13.4M installs)
 * stays the default. We only add a tab next to it.
 */
class TextTabularFileEditorProvider : TabularFileEditorProviderBase(binaryFormats = false) {

    override fun getEditorTypeId(): String = "tablekit.text"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
