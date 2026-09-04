package dev.gipo.agentreview

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
        assertEquals(store.session.scope.key(), fresh.session.scope.key())
        assertEquals(1, fresh.comments.size)
        assertEquals("hello", fresh.comments[0].text)
        assertEquals(fresh.currentKey, fresh.comments[0].scopeKey)
        assertEquals(ReviewState.REVIEWED, fresh.session.reviewState("a.kt", "h1"))
        assertEquals(ReviewState.STALE, fresh.session.reviewState("a.kt", "h2"))
        store.clear()
        assertTrue(store.comments.isEmpty())
    }
}

class SessionPerScopeTest : com.intellij.testFramework.fixtures.BasePlatformTestCase() {
    fun testScopesKeepSeparateSessions() {
        val store = ReviewStore.getInstance(project)
        store.setScope(dev.gipo.agentreview.model.Scope(dev.gipo.agentreview.model.ScopeKind.RANGE, base = "c", head = "d"))
        store.clear()
        store.addComment(Comment(path = "x.kt", startLine = 1, text = "cd"))
        store.setScope(dev.gipo.agentreview.model.Scope(dev.gipo.agentreview.model.ScopeKind.RANGE, base = "a", head = "b"))
        // Comments are project-wide; clearing another scope keeps them.
        store.clear()
        assertEquals("cd", store.comments.single().text)
        assertEquals("range:c..d", store.comments.single().scopeKey)

        // Legacy single-session state still loads; its comments move to the project list.
        val legacy = ReviewStore.State().also {
            it.json = """{"scope":{"kind":"UNCOMMITTED"},"comments":[{"id":"1","path":"y.kt","startLine":2,"text":"old"}],"reviewed":{},"notes":""}"""
        }
        val fresh = ReviewStore(project)
        fresh.loadState(legacy)
        assertEquals("old", fresh.comments.single().text)
        assertEquals("uncommitted", fresh.comments.single().scopeKey)
        assertTrue(fresh.session.comments.isEmpty())
        store.forgetOtherSessions()
        store.clearAll()
    }

    fun testSavedSessionsListAndForgetOne() {
        val store = ReviewStore.getInstance(project)
        val ab = dev.gipo.agentreview.model.Scope(dev.gipo.agentreview.model.ScopeKind.RANGE, base = "a", head = "b")
        val cd = dev.gipo.agentreview.model.Scope(dev.gipo.agentreview.model.ScopeKind.RANGE, base = "c", head = "d")
        store.setScope(ab)
        store.setReviewed("x.kt", "h1")
        store.setScope(cd)
        store.setReviewed("y.kt", "h2")
        store.setScope(dev.gipo.agentreview.model.Scope())

        // Current session first even when empty, then the others newest first.
        assertEquals(listOf("uncommitted", "range:c..d", "range:a..b"), store.savedSessions().map { it.scope.key() })

        store.forgetSession("range:c..d")
        assertEquals(listOf("uncommitted", "range:a..b"), store.savedSessions().map { it.scope.key() })
        // The current session cannot be forgotten.
        store.forgetSession("uncommitted")
        assertEquals("uncommitted", store.currentKey)

        // Switching back restores the marks.
        store.setScope(ab)
        assertEquals(ReviewState.REVIEWED, store.session.reviewState("x.kt", "h1"))
        store.forgetOtherSessions()
        store.clearAll()
    }
}
