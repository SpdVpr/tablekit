package com.kitworks.tablekit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.kitworks.tablekit.editor.TextTabularFileEditorProvider
import com.kitworks.tablekit.format.TabularFormat

/**
 * Opens a CSV, TSV or JSON Lines file on TableKit's tab.
 *
 * Those formats keep whatever editor already owns them, so the grid sits on a
 * second tab that nobody discovers by accident. This is how they find it.
 */
class OpenInTableKitAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val format = file?.takeIf { !it.isDirectory }?.let(TabularFormat::of)
        event.presentation.isEnabledAndVisible = event.project != null && format != null && !format.binary
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, true)
        manager.setSelectedEditor(file, TextTabularFileEditorProvider.EDITOR_TYPE_ID)
    }
}
