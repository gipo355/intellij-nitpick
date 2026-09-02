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
        put("reply", c.reply?.let { JsonPrimitive(it) } ?: JsonNull)
        put("snippet", c.snippet?.let { JsonPrimitive(it) } ?: JsonNull)
        put("created_at", c.createdAt)
        put("text", c.text)
    }

    fun comments(session: ReviewSession, includeResolved: Boolean): JsonArray = buildJsonArray {
        session.comments
            .filter { includeResolved || !it.resolved }
            .sortedWith(commentOrder)
            .forEach { add(comment(it)) }
    }

    fun session(session: ReviewSession, includeResolved: Boolean, branch: String?): JsonObject = buildJsonObject {
        put("scope", session.scope.describe())
        put("branch", branch?.let { JsonPrimitive(it) } ?: JsonNull)
        put("notes", session.notes)
        put("reviewed_files", buildJsonArray { session.reviewed.keys.sorted().forEach { add(JsonPrimitive(it)) } })
        put("comments", comments(session, includeResolved))
    }

    fun encode(element: JsonObject): String = json.encodeToString(JsonObject.serializer(), element)
    fun encode(element: JsonArray): String = json.encodeToString(JsonArray.serializer(), element)
}
