package dev.gipo.agentreview

import dev.gipo.agentreview.channels.TerminalPayload
import dev.gipo.agentreview.export.ContextLines
import dev.gipo.agentreview.export.JsonExporter
import dev.gipo.agentreview.model.Author
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ThreadEntry
import dev.gipo.agentreview.model.ContentHash
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.ReviewState
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.settings.CommentFilter
import dev.gipo.agentreview.settings.FileFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun locations() {
        assertEquals("a.kt:1", Comment(path = "a.kt", startLine = 1).location())
        assertEquals("a.kt:1", Comment(path = "a.kt", startLine = 1, endLine = 1).location())
        assertEquals("a.kt:1-3", Comment(path = "a.kt", startLine = 1, endLine = 3).location())
        assertEquals("a.kt:~4-~5", Comment(path = "a.kt", side = Side.OLD, startLine = 4, endLine = 5).location())
        assertEquals("a.kt", Comment(path = "a.kt").location())
        assertEquals("review", Comment().location())
    }

    @Test
    fun scopeShortLabel() {
        assertEquals("Uncommitted", Scope(ScopeKind.UNCOMMITTED).shortLabel())
        assertEquals("Staged", Scope(ScopeKind.STAGED).shortLabel())
        assertEquals("6702505d..7a11a982", Scope(ScopeKind.RANGE, base = "6702505d1234", head = "7a11a982abcd").shortLabel())
        assertEquals("main..HEAD", Scope(ScopeKind.RANGE, base = "main", head = "HEAD").shortLabel())
        assertEquals("main...HEAD", Scope(ScopeKind.RANGE, base = "abcdef0123", head = "HEAD", baseLabel = "merge-base(main)").shortLabel())
        assertEquals("commit 6702505d", Scope(ScopeKind.COMMIT, head = "6702505d1234").shortLabel())
    }

    @Test
    fun fileFilterTreatsStaleAsUnreviewed() {
        assertTrue(ReviewState.entries.all { FileFilter.ALL.shows(it) })
        assertEquals(listOf(ReviewState.UNREVIEWED, ReviewState.STALE), ReviewState.entries.filter { FileFilter.UNREVIEWED.shows(it) })
        assertEquals(listOf(ReviewState.REVIEWED), ReviewState.entries.filter { FileFilter.REVIEWED.shows(it) })
    }

    @Test
    fun contextLinesAroundRange() {
        val content = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj"
        assertEquals(" 9: i\n10: j", ContextLines.around(content, 10, 10, 1))
        assertEquals("1: a\n2: b\n3: c", ContextLines.around(content, 2, 2, 1))
        assertEquals("3: c", ContextLines.around(content, 3, 3, 0))
        assertEquals("", ContextLines.around(content, 50, 50, 2))
    }

    @Test
    fun threadActivityAndAgentReply() {
        val c = Comment(text = "q", createdAt = 5)
        assertFalse(c.hasAgentReply)
        assertEquals(5L, c.lastActivity)
        val replied = c.copy(thread = listOf(ThreadEntry(Author.AGENT, "a", 9)))
        assertTrue(replied.hasAgentReply)
        assertEquals(9L, replied.lastActivity)
        assertTrue(replied in listOf(replied).filter { CommentFilter.WITH_REPLY.shows(it) })
    }

    @Test
    fun commentFilter() {
        val open = Comment(text = "a")
        val done = Comment(text = "b", resolved = true)
        val replied = Comment(text = "c", resolved = true, reply = "fixed")
        val all = listOf(open, done, replied)
        assertEquals(all, all.filter { CommentFilter.ALL.shows(it) })
        assertEquals(listOf(open), all.filter { CommentFilter.UNRESOLVED.shows(it) })
        assertEquals(listOf(done, replied), all.filter { CommentFilter.RESOLVED.shows(it) })
        assertEquals(listOf(replied), all.filter { CommentFilter.WITH_REPLY.shows(it) })
    }

    @Test
    fun reviewedStateFollowsContentHash() {
        val h = ContentHash.of("abc")
        val s = ReviewSession(reviewed = mapOf("a.kt" to h))
        assertEquals(ReviewState.REVIEWED, s.reviewState("a.kt", h))
        assertEquals(ReviewState.REVIEWED, s.reviewState("a.kt", null))
        assertEquals(ReviewState.STALE, s.reviewState("a.kt", ContentHash.of("abd")))
        assertEquals(ReviewState.UNREVIEWED, s.reviewState("b.kt", h))
    }

    @Test
    fun terminalPayloadUsesBracketedPaste() {
        assertEquals("\u001b[200~hi\nthere\u001b[201~\r", TerminalPayload.frame("hi\nthere\n", submit = true))
        assertEquals("\u001b[200~hi\u001b[201~", TerminalPayload.frame("hi", submit = false))
    }

    @Test
    fun jsonShape() {
        val c = Comment(id = "x", path = "a.kt", startLine = 2, text = "t")
        val json = JsonExporter.encode(JsonExporter.comment(c))
        assertTrue(json, json.contains("\"location\": \"a.kt:2\""))
        assertTrue(json, json.contains("\"start_line\": 2"))
        assertTrue(json, json.contains("\"end_line\": null"))
    }
}
