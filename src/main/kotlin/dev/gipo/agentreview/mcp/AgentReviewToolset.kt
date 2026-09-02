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
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.store.ReviewStore
import kotlinx.serialization.Serializable
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
        After fixing an item, call agent_review_resolve_comment with its id (use format=json to get ids).""",
    )
    suspend fun agent_review_get_review(
        @McpDescription("\"markdown\" or \"json\"") format: String = "markdown",
    ): String {
        val project = coroutineContext.project
        return if (format.equals("json", ignoreCase = true)) ReviewExport.json(project) else ReviewExport.markdown(project)
    }

    @McpTool
    @McpDescription("List review comments as JSON objects with id, location, path, side, start_line, end_line, type, text, resolved.")
    suspend fun agent_review_list_comments(
        @McpDescription("Include comments already marked resolved") include_resolved: Boolean = false,
    ): String {
        val session = ReviewStore.getInstance(coroutineContext.project).session
        return JsonExporter.encode(JsonExporter.comments(session, include_resolved))
    }

    @McpTool
    @McpDescription("Mark a review comment as resolved after addressing it. Optionally attach a short reply explaining what you did.")
    suspend fun agent_review_resolve_comment(
        @McpDescription("Comment id from agent_review_list_comments") id: String,
        @McpDescription("What you changed, one or two sentences") reply: String = "",
    ): ResolveResult {
        val store = ReviewStore.getInstance(coroutineContext.project)
        if (store.session.comments.none { it.id == id }) mcpFail("No comment with id $id")
        store.updateComment(id) { it.copy(resolved = true, reply = reply.ifBlank { null }) }
        return ResolveResult(id, true)
    }

    @McpTool
    @McpDescription(
        """Add a note or question for the human reviewer at a file location. Use it to ask about ambiguous review comments
        or to flag something you deliberately left as is. It shows up in the IDE's Agent Review tool window.""",
    )
    suspend fun agent_review_add_comment(
        @McpDescription("Project-relative path, e.g. src/main/App.kt") path: String,
        @McpDescription("Comment text") text: String,
        @McpDescription("1-based line in the new version; omit for a file-level comment") line: Int = 0,
        @McpDescription("1-based end line for a range") end_line: Int = 0,
        @McpDescription("note, issue, question, nit, praise") type: String = "question",
    ): AddResult {
        val store = ReviewStore.getInstance(coroutineContext.project)
        val commentType = CommentType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) } ?: CommentType.QUESTION
        val comment = Comment(
            path = path.trim().trimStart('/'),
            side = Side.NEW,
            startLine = line.takeIf { it > 0 },
            endLine = end_line.takeIf { it > 0 && line > 0 },
            type = commentType,
            text = text,
            author = Author.AGENT,
        )
        store.addComment(comment)
        return AddResult(comment.id, comment.location())
    }

    @McpTool
    @McpDescription("Files in the current review scope with their review state (reviewed, stale, unreviewed) and open comment counts.")
    suspend fun agent_review_status(): StatusResult {
        val project = coroutineContext.project
        val store = ReviewStore.getInstance(project)
        val model = ReviewChangesModel.getInstance(project)
        return StatusResult(model.changes.map { rc ->
            FileStatus(rc.path, model.state(rc).name.lowercase(), store.session.commentsFor(rc.path).count { !it.resolved })
        })
    }

    @Serializable
    data class ResolveResult(val id: String, val resolved: Boolean)

    @Serializable
    data class AddResult(val id: String, val location: String)

    @Serializable
    data class FileStatus(val path: String, val state: String, val open_comments: Int)

    @Serializable
    data class StatusResult(val files: List<FileStatus>)
}
