package dev.gipo.agentreview.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import dev.gipo.agentreview.channels.ReviewExport
import dev.gipo.agentreview.export.JsonExporter
import dev.gipo.agentreview.model.Author
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.CommentType
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.model.ThreadEntry
import com.intellij.openapi.project.Project
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.store.ReviewStore
import dev.gipo.agentreview.export.ContextLines
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Agent-side pull channel on IntelliJ's bundled MCP server.
 * Any MCP client (claude, codex, opencode, pi, Copilot, AI Assistant) can call these.
 */
class AgentReviewToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpTool
    @McpDescription(
        """Get the human's code review of the current changes as Markdown (default) or JSON.
        Call this when the user says they left review comments in the IDE, or asks you to address the review.
        Each comment has a location `path:line` (or `path:start-end`, `~` marks the old side) and text.
        After fixing items, call agent_review_resolve_comments with their ids (use format=json to get ids).
        JSON carries the scope as base/head so you can diff exactly that range.""",
    )
    suspend fun agent_review_get_review(
        @McpDescription("\"markdown\" or \"json\"") format: String = "markdown",
    ): String {
        val project = coroutineContext.project
        return if (format.equals("json", ignoreCase = true)) ReviewExport.json(project) else ReviewExport.markdown(project)
    }

    @McpTool
    @McpDescription(
        """List review comments as JSON objects with id, location, path, side, start_line, end_line, type, text, resolved, reply, snippet.
        Filter by path prefix, type, or creation time to work one file at a time or poll for new comments.
        context_lines > 0 adds a `context` field with numbered lines around the anchor.""",
    )
    suspend fun agent_review_list_comments(
        @McpDescription("Include comments already marked resolved") include_resolved: Boolean = false,
        @McpDescription("Only comments whose path starts with this, e.g. src/main/") path_prefix: String = "",
        @McpDescription("Only this type: note, issue, question, nit, praise") type: String = "",
        @McpDescription("Only comments created or replied to at or after this epoch millisecond timestamp") since: Long = 0,
        @McpDescription("Lines of file content to include before and after the anchor; 0 for none") context_lines: Int = 0,
    ): String {
        val model = ReviewChangesModel.getInstance(coroutineContext.project)
        val wantType = CommentType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) }
        val comments = model.comments().filter { c ->
            c.path.startsWith(path_prefix) && (wantType == null || c.type == wantType) && c.lastActivity >= since
        }
        val array = JsonExporter.comments(comments, include_resolved)
        if (context_lines <= 0) return JsonExporter.encode(array)
        val byId = comments.associateBy { it.id }
        val withContext = buildJsonArray {
            for (element in array) {
                val obj = element.jsonObject
                val c = byId[obj["id"]?.jsonPrimitive?.content] ?: continue
                val start = c.startLine
                val rc = model.find(c.path)
                val content = if (c.side == Side.OLD) rc?.beforeContent else rc?.content
                val context = if (start != null && content != null) ContextLines.around(content, start, c.endLine ?: start, context_lines) else null
                add(JsonObject(obj + ("context" to (context?.let { JsonPrimitive(it) } ?: JsonNull))))
            }
        }
        return JsonExporter.encode(withContext)
    }

    @McpTool
    @McpDescription(
        """Mark a review comment as resolved after addressing it. Optionally attach a short reply explaining what you did.
        wont_fix=true records a pushback instead: the comment closes as "won't fix" with your reason as the reply.
        If the fix landed elsewhere (moved code), pass new_path and new_line so the IDE points at it.""",
    )
    suspend fun agent_review_resolve_comment(
        @McpDescription("Comment id from agent_review_list_comments, or a unique prefix of it") id: String,
        @McpDescription("What you changed, one or two sentences") reply: String = "",
        @McpDescription("Close as won't fix instead of fixed") wont_fix: Boolean = false,
        @McpDescription("Project-relative path where the fix landed, when it moved") new_path: String = "",
        @McpDescription("1-based line in the new version where the fix landed") new_line: Int = 0,
        @McpDescription("1-based end line of the fix") new_end_line: Int = 0,
    ): ResolveResult {
        val project = coroutineContext.project
        val store = ReviewStore.getInstance(project)
        val comment = store.findComment(id) ?: mcpFail("No comment with id $id")
        store.updateComment(comment.id) {
            relocate(project, it, new_path, new_line, new_end_line).copy(resolved = true, wontFix = wont_fix, reply = reply.ifBlank { null })
        }
        return ResolveResult(comment.id, true)
    }

    /** Moves the anchor to where the fix landed. Hash and snippet come from the scope's current content. */
    private fun relocate(project: Project, c: Comment, newPath: String, newLine: Int, newEndLine: Int): Comment {
        if (newPath.isBlank() && newLine <= 0) return c
        val path = newPath.trim().trimStart('/').ifEmpty { c.path }
        val line = newLine.takeIf { it > 0 } ?: return c.copy(path = path)
        val end = newEndLine.takeIf { it >= line } ?: line
        val rc = ReviewChangesModel.getInstance(project).find(path)
        val snippet = rc?.content?.lines()?.let { lines -> if (line <= lines.size) lines.subList(line - 1, minOf(end, lines.size)).joinToString("\n") else null }
        return c.copy(path = path, side = Side.NEW, startLine = line, endLine = end, contentHash = rc?.hash, snippet = snippet ?: c.snippet)
    }

    @McpTool
    @McpDescription(
        """Reply to a review comment without resolving it, e.g. to answer a QUESTION or ask the reviewer something.
        The reply threads under the comment in the IDE. Poll agent_review_list_comments with `since` for the reviewer's answer.""",
    )
    suspend fun agent_review_reply(
        @McpDescription("Comment id, or a unique prefix of it") id: String,
        @McpDescription("Reply text") text: String,
    ): ResolveResult {
        val store = ReviewStore.getInstance(coroutineContext.project)
        val comment = store.findComment(id) ?: mcpFail("No comment with id $id")
        if (text.isBlank()) mcpFail("Empty reply")
        store.updateComment(comment.id) { it.copy(thread = it.thread + ThreadEntry(Author.AGENT, text.trim())) }
        return ResolveResult(comment.id, comment.resolved)
    }

    @McpTool
    @McpDescription("Resolve several comments in one call. Each item has the comment id (or unique prefix) and an optional reply.")
    suspend fun agent_review_resolve_comments(
        @McpDescription("Items to resolve") items: List<ResolveItem>,
    ): BatchResolveResult {
        val project = coroutineContext.project
        val store = ReviewStore.getInstance(project)
        val resolved = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (item in items) {
            val comment = store.findComment(item.id)
            if (comment == null) { unknown += item.id; continue }
            store.updateComment(comment.id) {
                relocate(project, it, item.new_path, item.new_line, item.new_end_line)
                    .copy(resolved = true, wontFix = item.wont_fix, reply = item.reply.ifBlank { null })
            }
            resolved += comment.id
        }
        return BatchResolveResult(resolved, unknown)
    }

    @McpTool
    @McpDescription(
        """Add a note or question for the human reviewer at a file location. Use it to ask about ambiguous review comments
        or to flag something you deliberately left as is. It shows up in the IDE's Nitpick tool window.""",
    )
    suspend fun agent_review_add_comment(
        @McpDescription("Project-relative path, e.g. src/main/App.kt") path: String,
        @McpDescription("Comment text") text: String,
        @McpDescription("1-based line in the new version; omit for a file-level comment") line: Int = 0,
        @McpDescription("1-based end line for a range") end_line: Int = 0,
        @McpDescription("note, issue, question, nit, praise") type: String = "question",
    ): AddResult {
        val project = coroutineContext.project
        val store = ReviewStore.getInstance(project)
        val commentType = CommentType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) } ?: CommentType.QUESTION
        val comment = Comment(
            path = path.trim().trimStart('/'),
            side = Side.NEW,
            startLine = line.takeIf { it > 0 },
            endLine = end_line.takeIf { it > 0 && line > 0 },
            type = commentType,
            text = text,
            author = Author.AGENT,
            contentHash = if (line > 0) ReviewChangesModel.getInstance(project).find(path)?.hash else null,
        )
        store.addComment(comment)
        return AddResult(comment.id, comment.location())
    }

    @McpTool
    @McpDescription("Comment totals (total, open, resolved, outdated) and the files in scope with review state and open comment counts.")
    suspend fun agent_review_status(): StatusResult {
        val project = coroutineContext.project
        val model = ReviewChangesModel.getInstance(project)
        val all = model.comments()
        val open = all.filter { !it.resolved }
        return StatusResult(
            total = all.size,
            open = open.size,
            resolved = all.size - open.size,
            outdated = all.count { it.outdated },
            files = model.changes.map { rc ->
                FileStatus(rc.path, model.state(rc).name.lowercase(), open.count { ReviewPaths.matches(it.path, rc.path) })
            },
        )
    }

    @Serializable
    data class ResolveResult(val id: String, val resolved: Boolean)

    @Serializable
    data class AddResult(val id: String, val location: String)

    @Serializable
    data class FileStatus(val path: String, val state: String, val open_comments: Int)

    @Serializable
    data class StatusResult(val total: Int, val open: Int, val resolved: Int, val outdated: Int, val files: List<FileStatus>)

    @Serializable
    data class ResolveItem(
        val id: String,
        val reply: String = "",
        val wont_fix: Boolean = false,
        val new_path: String = "",
        val new_line: Int = 0,
        val new_end_line: Int = 0,
    )

    @Serializable
    data class BatchResolveResult(val resolved: List<String>, val unknown: List<String>)
}
