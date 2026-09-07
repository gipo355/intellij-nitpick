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
        val session = ReviewSession(scope = Scope(ScopeKind.RANGE, base = "a", head = "b"), generation = 2, reviewed = mapOf("x.kt" to "h"), notes = "n")
        val comments = listOf(Comment(path = "x.kt", startLine = 1, text = "t", sessionKey = session.key))
        val back = SessionFile.decode(SessionFile.encode(session, comments, branch = "main"))
        assertEquals(1, back.format)
        assertEquals(session, back.session)
        assertEquals("range:a..b#2", back.session.key)
        assertEquals(comments, back.comments)
        assertEquals("main", back.branch)
    }
}
