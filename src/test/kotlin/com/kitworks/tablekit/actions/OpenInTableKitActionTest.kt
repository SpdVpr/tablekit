package com.kitworks.tablekit.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The action exists so people find the grid on formats where TableKit is not
 * the default editor. It must therefore appear on exactly those.
 */
class OpenInTableKitActionTest : BasePlatformTestCase() {

    fun `test the action is registered with the IDE`() {
        assertNotNull(ActionManager.getInstance().getAction("TableKit.OpenIn"))
    }

    fun `test it offers itself for formats that keep their own editor`() {
        for (name in listOf("data.csv", "data.tsv", "events.jsonl")) {
            assertTrue("expected the action on $name", isVisibleFor(name))
        }
    }

    /** On Parquet and friends TableKit is already the editor; the action would be noise. */
    fun `test it stays out of the way elsewhere`() {
        for (name in listOf("data.parquet", "book.xlsx", "notes.txt", "Main.java")) {
            assertFalse("did not expect the action on $name", isVisibleFor(name))
        }
    }

    private fun isVisibleFor(name: String): Boolean {
        val file: VirtualFile = myFixture.tempDirFixture.createFile(name)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .build()

        val action = OpenInTableKitAction()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }
}
