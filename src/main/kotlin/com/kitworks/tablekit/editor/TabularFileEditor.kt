package com.kitworks.tablekit.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.kitworks.tablekit.TableKitBundle
import com.kitworks.tablekit.format.TabularFormat
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class TabularFileEditor(
    project: Project,
    private val file: VirtualFile,
    format: TabularFormat,
) : UserDataHolderBase(), FileEditor {

    private val panel = TabularEditorPanel(project, file, format)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusedComponent

    override fun getName(): String = TableKitBundle.message("editor.tab.name")

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        Disposer.dispose(panel)
    }
}
