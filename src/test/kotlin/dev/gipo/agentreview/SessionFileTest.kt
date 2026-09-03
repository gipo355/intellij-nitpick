package dev.gipo.agentreview

import dev.gipo.agentreview.export.SessionFile
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFileTest {
    @Test
    fun roundTrip() {
        val session = ReviewSession(scope = Scope(ScopeKind.RANGE, base = "a", head = "b"), reviewed = mapOf("x.kt" to "h"), notes = "n")
        val comments = listOf(Comment(path = "x.kt", startLine = 1, text = "t", scopeKey = session.scope.key()))
        val back = SessionFile.decode(SessionFile.encode(session, comments, branch = "main"))
        assertEquals(1, back.format)
        assertEquals(session, back.session)
        assertEquals(comments, back.comments)
        assertEquals("main", back.branch)
    }
}
