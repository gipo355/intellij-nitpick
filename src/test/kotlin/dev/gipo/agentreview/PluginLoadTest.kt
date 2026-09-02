package dev.gipo.agentreview

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gipo.agentreview.channels.ReviewExport
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ReviewState
import dev.gipo.agentreview.store.ReviewListener
import dev.gipo.agentreview.store.ReviewStore

/** Boots the platform with plugin.xml, so wrong class names or EPs fail here. */
class PluginLoadTest : BasePlatformTestCase() {

    fun testActionsRegistered() {
        val am = ActionManager.getInstance()
        for (id in listOf(
            "AgentReview.AddComment", "AgentReview.AddFileComment", "AgentReview.ToggleReviewed",
            "AgentReview.NextUnreviewed", "AgentReview.CopyMarkdown", "AgentReview.SendToTerminal",
            "AgentReview.WriteFile", "AgentReview.ReviewCommit", "AgentReview.ReviewUncommitted",
        )) {
            assertNotNull("action $id", am.getAction(id))
        }
    }

    fun testStoreRoundTripAndListener() {
        val store = ReviewStore.getInstance(project)
        var events = 0
        project.messageBus.connect(testRootDisposable).subscribe(ReviewListener.TOPIC, object : ReviewListener {
            override fun sessionChanged(session: dev.gipo.agentreview.model.ReviewSession) {
                events++
            }
        })
        store.clear()
        store.addComment(Comment(path = "a.kt", startLine = 3, text = "hello"))
        store.setReviewed("a.kt", "h1")
        assertEquals(3, events)

        val state = store.state
        val fresh = ReviewStore(project)
        fresh.loadState(state)
        assertEquals(1, fresh.session.comments.size)
        assertEquals("hello", fresh.session.comments[0].text)
        assertEquals(ReviewState.REVIEWED, fresh.session.reviewState("a.kt", "h1"))
        assertEquals(ReviewState.STALE, fresh.session.reviewState("a.kt", "h2"))

        val md = ReviewExport.markdown(project)
        assertTrue(md, md.contains("`a.kt:3` - hello"))
        store.clear()
    }
}
