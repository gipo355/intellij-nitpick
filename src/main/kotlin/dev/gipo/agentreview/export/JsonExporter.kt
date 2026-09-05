package dev.gipo.agentreview.export

import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.commentOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** tuicr-compatible `review comments` shape. */
object JsonExporter {
    private val json = Json { prettyPrint = true }

    fun comment(c: Comment): JsonObject = buildJsonObject {
        put("id", c.id)
        put("location", c.location())
        put("path", c.path)
        put("side", c.side.name.lowercase())
        put("start_line", c.startLine?.let { JsonPrimitive(it) } ?: JsonNull)
        put("end_line", c.endLine?.let { JsonPrimitive(it) } ?: JsonNull)
        put("type", c.type.name.lowercase())
        put("author", c.author.name.lowercase())
        put("resolved", c.resolved)
        put("outdated", c.outdated)
        put("reply", c.reply?.let { JsonPrimitive(it) } ?: JsonNull)
        put("wont_fix", c.wontFix)
        put("thread", buildJsonArray {
            c.thread.forEach { t ->
                add(buildJsonObject { put("author", t.author.name.lowercase()); put("text", t.text); put("created_at", t.createdAt) })
            }
        })
        put("snippet", c.snippet?.let { JsonPrimitive(it) } ?: JsonNull)
        put("created_at", c.createdAt)
        put("text", c.text)
    }

    fun comments(comments: List<Comment>, includeResolved: Boolean): JsonArray = buildJsonArray {
        comments
            .filter { includeResolved || !it.resolved }
            .sortedWith(commentOrder)
            .forEach { add(comment(it)) }
    }

    fun session(session: ReviewSession, comments: List<Comment>, includeResolved: Boolean, branch: String?): JsonObject = buildJsonObject {
        put("scope", session.scope.describe())
        put("scope_kind", session.scope.kind.name.lowercase())
        put("base", session.scope.base?.let { JsonPrimitive(it) } ?: JsonNull)
        put("head", session.scope.head?.let { JsonPrimitive(it) } ?: JsonNull)
        put("root", session.scope.root?.let { JsonPrimitive(it) } ?: JsonNull)
        put("branch", branch?.let { JsonPrimitive(it) } ?: JsonNull)
        put("notes", session.notes)
        put("reviewed_files", buildJsonArray { session.reviewed.keys.sorted().forEach { add(JsonPrimitive(it)) } })
        put("comments", comments(comments, includeResolved))
    }

    fun encode(element: JsonObject): String = json.encodeToString(JsonObject.serializer(), element)
    fun encode(element: JsonArray): String = json.encodeToString(JsonArray.serializer(), element)
}
