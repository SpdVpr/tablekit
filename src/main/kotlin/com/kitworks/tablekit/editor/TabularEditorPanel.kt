package com.kitworks.tablekit.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kitworks.tablekit.TableKitBundle
import com.kitworks.tablekit.format.TabularFormat
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * Root component of the tabular editor.
 *
 * Phase 0 renders a placeholder; the virtualized grid backed by DuckDB replaces
 * the center component in phase 1. Everything expensive must stay off the EDT -
 * a frozen IDE is the exact failure mode we are here to kill.
 */
class TabularEditorPanel(
    private val project: Project,
    private val file: VirtualFile,
    private val format: TabularFormat,
) : JBPanel<TabularEditorPanel>(BorderLayout()), Disposable {

    init {
        add(createPlaceholder(), BorderLayout.CENTER)
        add(createStatusBar(), BorderLayout.SOUTH)
    }

    val preferredFocusedComponent: JComponent get() = this

    private fun createPlaceholder(): JComponent = JBLabel(
        TableKitBundle.message("editor.placeholder", format.displayName, file.name),
        SwingConstants.CENTER,
    ).apply {
        font = JBFont.label().biggerOn(2f)
        foreground = UIUtil.getInactiveTextColor()
    }

    private fun createStatusBar(): JComponent = JBLabel(
        TableKitBundle.message("editor.status", format.displayName, StringUtil.formatFileSize(file.length)),
    ).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(UIUtil.getBoundsColor()),
            JBUI.Borders.empty(4, 8),
        )
        foreground = UIUtil.getContextHelpForeground()
    }

    override fun dispose() {
        // Phase 1: release the DuckDB connection and cancel pending queries here.
    }
}
