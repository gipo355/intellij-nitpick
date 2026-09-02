package dev.gipo.agentreview

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.gipo.agentreview.diff.EditorReviewBinding
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.store.ReviewStore

/** Opens a real two-side diff viewer headlessly and checks comments render as inlays. */
class DiffBindingTest : BasePlatformTestCase() {

    fun testBindingAndInlay() {
        val file = myFixture.addFileToProject("src/a.kt", "line1\nline2\nline3\n").virtualFile
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "t",
            factory.create(project, "line1\nlineX\nline3\n", file.fileType),
            factory.create(project, file),
            "old", "new",
        )
        val panel = DiffManager.getInstance().createRequestPanel(project, testRootDisposable, null)
        panel.setRequest(request)
        UIUtil.dispatchAllInvocationEvents()

        val bindings = EditorFactory.getInstance().allEditors.mapNotNull { it.getUserData(EditorReviewBinding.KEY) }
        assertTrue("binding installed on diff editors, got ${bindings.size}", bindings.isNotEmpty())
        val newSide = bindings.first { it.primarySide == Side.NEW }
        assertTrue(newSide.path, newSide.path.endsWith("src/a.kt"))

        val store = ReviewStore.getInstance(project)
        store.clear()
        store.addComment(Comment(path = newSide.path, side = Side.NEW, startLine = 2, endLine = 2, text = "hey"))
        UIUtil.dispatchAllInvocationEvents()

        val editor = newSide.editor as EditorEx
        val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
        assertEquals("one comment inlay", 1, inlays.size)
        assertEquals(1, editor.document.getLineNumber(inlays[0].offset))

        store.clear()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(0, editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size)

        // Writes from a background thread (MCP calls) must not touch the editor off EDT.
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread {
            try {
                store.addComment(Comment(path = newSide.path, side = Side.NEW, startLine = 3, endLine = 3, text = "from agent"))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.start()
        worker.join()
        assertNull(failure.get()?.toString(), failure.get())
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("inlay rendered after EDT dispatch", 1, editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size)
        store.clear()
        UIUtil.dispatchAllInvocationEvents()
    }
}
