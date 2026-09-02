package dev.gipo.agentreview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gipo.agentreview.diff.EditorReviewBinding
import dev.gipo.agentreview.diff.SingleSideMapper
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.store.ReviewStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ZZProbeTest : BasePlatformTestCase() {

    /** Simulates MCP `agent_review_add_comment`: ReviewStore.update from a background thread. */
    fun testStoreUpdateFromBackgroundThreadWithOpenDiffBinding() {
        myFixture.configureByText("a.kt", "line1\nline2\nline3\nline4\n")
        val editor = myFixture.editor as EditorEx
        val store = ReviewStore.getInstance(project)
        store.clear()

        EditorReviewBinding(project, editor, "a.kt", SingleSideMapper(Side.NEW), Side.NEW, testRootDisposable)

        // EDT path works
        store.addComment(Comment(path = "a.kt", side = Side.NEW, startLine = 2, endLine = 2, text = "on edt"))
        println("PROBE: EDT add OK")

        val err = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                store.addComment(Comment(path = "a.kt", side = Side.NEW, startLine = 3, endLine = 3, text = "from mcp"))
                println("PROBE: BGT add returned normally")
            } catch (t: Throwable) {
                err.set(t)
                println("PROBE: BGT add THREW ${t.javaClass.name}: ${t.message?.lineSequence()?.first()}")
            } finally {
                latch.countDown()
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        println("PROBE-RESULT: ${err.get()?.javaClass?.name ?: "no-exception"}")
        store.clear()
    }
}
