package dev.gipo.agentreview.export

import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ReviewSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One session plus its comments, for moving a review between machines. */
@Serializable
data class SessionFile(
    val format: Int = 1,
    val plugin: String = "nitpick",
    val branch: String? = null,
    val exportedAt: Long = System.currentTimeMillis(),
    val session: ReviewSession = ReviewSession(),
    val comments: List<Comment> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun encode(session: ReviewSession, comments: List<Comment>, branch: String?): String =
            json.encodeToString(serializer(), SessionFile(branch = branch, session = session, comments = comments))

        fun decode(text: String): SessionFile = json.decodeFromString(serializer(), text)
    }
}
