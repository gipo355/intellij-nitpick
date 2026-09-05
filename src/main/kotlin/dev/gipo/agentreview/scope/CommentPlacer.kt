package dev.gipo.agentreview.scope

import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.Side

/** Where a comment shows in the current scope. Comments are anchored to text, not to a scope. */
object CommentPlacer {

    fun place(all: List<Comment>, changes: List<ReviewedChange>, currentKey: String): List<Comment> {
        if (all.isEmpty()) return all
        val byPath = HashMap<String, ReviewedChange>(changes.size * 2)
        for (rc in changes) byPath.putIfAbsent(rc.path, rc)
        return all.mapNotNull { c ->
            if (c.isReviewLevel) return@mapNotNull c.takeIf { it.scopeKey == currentKey }
            if (c.isFolderLevel) return@mapNotNull c.takeIf { changes.any { rc -> rc.path.startsWith(c.path) } }
            val rc = byPath[c.path] ?: changes.firstOrNull { ReviewPaths.matches(it.path, c.path) }
                ?: return@mapNotNull null
            if (c.startLine == null) return@mapNotNull c
            val hash = if (c.side == Side.NEW) rc.hash else rc.beforeHash
            if (c.contentHash == null || c.contentHash == hash) return@mapNotNull c
            // Normalized once per change and cached there, not once per comment.
            val text = (if (c.side == Side.NEW) rc.text else rc.beforeText) ?: return@mapNotNull c.copy(outdated = true)
            val found = relocateNormalized(c.snippet ?: "", text) ?: return@mapNotNull c.copy(outdated = true)
            c.copy(startLine = found.first, endLine = found.second)
        }
    }

    /** 1-based first..last line of the unique occurrence of [snippet] in [content], else null. */
    fun relocate(snippet: String, content: CharSequence): Pair<Int, Int>? =
        relocateNormalized(snippet, content.toString().replace("\r\n", "\n"))

    private fun relocateNormalized(snippet: String, text: String): Pair<Int, Int>? {
        val needle = snippet.trimEnd('\n', '\r')
        if (needle.isBlank()) return null
        val first = text.indexOf(needle)
        if (first < 0 || text.indexOf(needle, first + 1) >= 0) return null
        var startLine = 1
        for (i in 0 until first) if (text[i] == '\n') startLine++
        val endLine = startLine + needle.count { it == '\n' }
        return startLine to endLine
    }
}
