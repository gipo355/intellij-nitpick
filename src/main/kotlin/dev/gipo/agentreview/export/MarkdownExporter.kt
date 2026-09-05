package dev.gipo.agentreview.export

import dev.gipo.agentreview.model.Author
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.model.commentOrder

data class ExportOptions(
    val intro: String = DEFAULT_INTRO,
    val includeSnippets: Boolean = true,
    val snippetMaxLines: Int = 12,
    val includeResolved: Boolean = false,
    val branch: String? = null,
    val mcpHint: Boolean = true,
    /** `### path` headings, short locations and a one-line snippet per item. */
    val groupByFile: Boolean = false,
) {
    companion object {
        const val DEFAULT_INTRO = "I reviewed your code and have the following comments. Please address them."
        /** Branch mode: the comments are a plan on unchanged code, not a review of a diff. */
        const val PLAN_INTRO = "I annotated the codebase with notes for a change I want to make. " +
            "Treat them as the plan: implement what they ask, in the order that makes sense, and keep the notes' intent."
        const val MCP_HINT = "If you have the agent_review MCP tools: call agent_review_list_comments for ids, " +
            "fix the items, then agent_review_resolve_comments with a one-line reply each (wont_fix for pushback). " +
            "Answer a QUESTION with agent_review_reply, ask with agent_review_add_comment when a comment is unclear. " +
            "Otherwise reply here with what you changed per item."
    }
}

object MarkdownExporter {

    fun export(session: ReviewSession, allComments: List<Comment>, options: ExportOptions = ExportOptions()): String {
        val comments = allComments
            .filter { options.includeResolved || !it.resolved }
            .sortedWith(commentOrder)
        val reviewLevel = comments.filter { it.isReviewLevel }
        val located = comments.filter { !it.isReviewLevel }

        val sb = StringBuilder()
        val planning = session.scope.kind == ScopeKind.BRANCH
        sb.append(if (planning && options.intro == ExportOptions.DEFAULT_INTRO) ExportOptions.PLAN_INTRO else options.intro).append("\n\n")
        sb.append("Scope: ").append(session.scope.describe())
        if (!planning) options.branch?.let { sb.append(" on `").append(it).append('`') }
        if (planning) session.scope.base?.let { sb.append(", plan started at `").append(it.take(8)).append("` (git diff ").append(it.take(8)).append("..HEAD shows what changed since)") }
        sb.append("\n\n")
        if (options.mcpHint) sb.append(ExportOptions.MCP_HINT).append("\n\n")

        if (located.isEmpty() && reviewLevel.isEmpty() && session.notes.isBlank()) {
            sb.append("No comments.\n")
            return sb.toString()
        }

        if (options.groupByFile) {
            var number = 0
            located.groupBy { it.path }.forEach { (path, items) ->
                sb.append("### ").append(path).append('\n')
                items.forEach { appendGroupedItem(sb, ++number, it) }
                sb.append('\n')
            }
        } else {
            located.forEachIndexed { index, c -> appendItem(sb, index + 1, c, options) }
        }

        val notes = buildList {
            reviewLevel.forEach { add(it.text.trim()) }
            if (session.notes.isNotBlank()) add(session.notes.trim())
        }
        if (notes.isNotEmpty()) {
            if (located.isNotEmpty()) sb.append('\n')
            sb.append("Review notes:\n")
            notes.forEach { sb.append("- ").append(it.lines().joinToString("\n  ")).append('\n') }
        }
        return sb.toString()
    }

    /** `N. **[TYPE]** `:42` `snippet` - text`, replies and resolution as in the flat form. */
    private fun appendGroupedItem(sb: StringBuilder, number: Int, c: Comment) {
        val marker = if (c.type.marker.isEmpty()) "" else "**[${c.type.marker}]** "
        val prefix = "$number. "
        val where = c.location().removePrefix(c.path).ifEmpty { "(file)" }
        val lines = c.text.trim().lines()
        sb.append(prefix).append(marker).append('`').append(where).append('`')
        c.snippet?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { sb.append(" `").append(it.trim()).append('`') }
        if (c.outdated) sb.append(" (outdated)")
        sb.append(" - ").append(lines.firstOrNull() ?: "").append('\n')
        val pad = " ".repeat(prefix.length)
        lines.drop(1).forEach { sb.append(pad).append(it).append('\n') }
        appendReplies(sb, pad, c)
    }

    private fun appendReplies(sb: StringBuilder, pad: String, c: Comment) {
        c.thread.forEach { t ->
            val who = if (t.author == Author.AGENT) "agent" else "reviewer"
            sb.append(pad).append("> **").append(who).append(":** ").append(t.text.trim().lines().joinToString("\n$pad> ")).append('\n')
        }
        if (c.resolved) {
            sb.append(pad).append(if (c.wontFix) "_(won't fix" else "_(resolved").append(c.reply?.let { ": $it" } ?: "").append(")_\n")
        }
    }

    private fun appendItem(sb: StringBuilder, number: Int, c: Comment, options: ExportOptions) {
        val marker = if (c.type.marker.isEmpty()) "" else "**[${c.type.marker}]** "
        val prefix = "$number. "
        val lines = c.text.trim().lines()
        sb.append(prefix).append(marker).append('`').append(c.location()).append('`')
        if (c.outdated) sb.append(" (outdated)")
        sb.append(" - ")
        sb.append(lines.firstOrNull() ?: "").append('\n')
        val pad = " ".repeat(prefix.length)
        lines.drop(1).forEach { sb.append(pad).append(it).append('\n') }
        appendReplies(sb, pad, c)
        val snippet = c.snippet?.takeIf { options.includeSnippets && it.isNotBlank() } ?: return
        val snippetLines = snippet.trimEnd().lines()
        val shown = snippetLines.take(options.snippetMaxLines)
        sb.append(pad).append("```").append(fenceLang(c.path)).append('\n')
        shown.forEach { sb.append(pad).append(it).append('\n') }
        if (snippetLines.size > shown.size) sb.append(pad).append("…\n")
        sb.append(pad).append("```\n")
    }

    private fun fenceLang(path: String): String = when (val ext = path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "rs" -> "rust"
        "ts", "tsx" -> "typescript"
        "js", "jsx", "mjs" -> "javascript"
        "go" -> "go"
        "rb" -> "ruby"
        "sh", "zsh", "bash" -> "bash"
        "yml", "yaml" -> "yaml"
        "json" -> "json"
        "md" -> "markdown"
        "sql" -> "sql"
        "xml", "html", "css", "toml", "c", "cpp", "h", "cs", "php", "swift", "scala" -> ext
        else -> ""
    }
}
