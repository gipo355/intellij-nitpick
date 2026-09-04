package dev.gipo.agentreview

import dev.gipo.agentreview.export.ExportOptions
import dev.gipo.agentreview.export.MarkdownExporter
import dev.gipo.agentreview.model.Author
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ThreadEntry
import dev.gipo.agentreview.model.CommentType
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExporterTest {

    private val session = ReviewSession()
    private val comments = listOf(
            Comment(path = "src/auth.rs", startLine = 50, endLine = 55, text = "This block could be refactored", createdAt = 2),
            Comment(
                path = "src/auth.rs", startLine = 42, type = CommentType.ISSUE,
                text = "Magic number should be a named constant", snippet = "let timeout = 3000;", createdAt = 1,
            ),
            Comment(path = "src/auth.rs", text = "Consider adding unit tests", createdAt = 3),
            Comment(path = "src/old.rs", side = Side.OLD, startLine = 12, text = "why was this removed?", createdAt = 4),
            Comment(path = "", text = "overall fine", createdAt = 5),
            Comment(path = "src/done.rs", startLine = 1, text = "done already", resolved = true, createdAt = 6),
    )

    @Test
    fun exportsTuicrCompatibleMarkdown() {
        val out = MarkdownExporter.export(session, comments, ExportOptions(branch = "main", mcpHint = false))
        val expected = """
            |I reviewed your code and have the following comments. Please address them.
            |
            |Scope: uncommitted changes on `main`
            |
            |1. `src/auth.rs` - Consider adding unit tests
            |2. **[ISSUE]** `src/auth.rs:42` - Magic number should be a named constant
            |   ```rust
            |   let timeout = 3000;
            |   ```
            |3. `src/auth.rs:50-55` - This block could be refactored
            |4. `src/old.rs:~12` - why was this removed?
            |
            |Review notes:
            |- overall fine
            |""".trimMargin()
        assertEquals(expected, out)
    }

    @Test
    fun threadsAndWontFix() {
        val c = Comment(
            path = "a.kt", startLine = 3, type = CommentType.QUESTION, text = "why?",
            thread = listOf(ThreadEntry(Author.AGENT, "because X", 10), ThreadEntry(Author.USER, "ok, keep it", 11)),
            resolved = true, wontFix = true, reply = "kept as is",
        )
        val out = MarkdownExporter.export(session, listOf(c), ExportOptions(includeResolved = true, mcpHint = false))
        assertTrue(out, out.contains("1. **[QUESTION]** `a.kt:3` - why?\n   > **agent:** because X\n   > **reviewer:** ok, keep it\n   _(won't fix: kept as is)_\n"))
    }

    @Test
    fun includesResolvedWhenAsked() {
        val out = MarkdownExporter.export(session, comments, ExportOptions(includeResolved = true, includeSnippets = false))
        assertTrue(out, out.contains("`src/done.rs:1` - done already\n   _(resolved)_"))
    }

    @Test
    fun multilineTextIsIndented() {
        val c = Comment(path = "a.kt", startLine = 3, text = "first\nsecond")
        val out = MarkdownExporter.export(ReviewSession(), listOf(c), ExportOptions(includeSnippets = false))
        assertTrue(out, out.contains("1. `a.kt:3` - first\n   second\n"))
    }

    @Test
    fun emptySession() {
        val out = MarkdownExporter.export(ReviewSession(), emptyList())
        assertTrue(out, out.endsWith("No comments.\n"))
    }

    @Test
    fun mentionsMcpToolsWhenEnabled() {
        val out = MarkdownExporter.export(session, comments, ExportOptions(mcpHint = true))
        assertTrue(out, out.contains("agent_review_list_comments"))
        assertTrue(out, out.contains("agent_review_resolve_comment"))
        val silent = MarkdownExporter.export(session, comments, ExportOptions(mcpHint = false))
        assertFalse(silent, silent.contains("agent_review"))
    }

    @Test
    fun outdatedCommentsAreMarked() {
        val c = Comment(path = "a.kt", startLine = 3, text = "moved away", outdated = true)
        val out = MarkdownExporter.export(session, listOf(c), ExportOptions(mcpHint = false))
        assertTrue(out, out.contains("`a.kt:3` (outdated) - moved away"))
    }
}
