package dev.gipo.agentreview.store

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.ReviewStorage
import dev.gipo.agentreview.model.Scope
import kotlinx.serialization.json.Json

interface ReviewListener {
    fun sessionChanged(session: ReviewSession)

    companion object {
        val TOPIC: Topic<ReviewListener> = Topic.create("AgentReview.session", ReviewListener::class.java)
    }
}

/** One session per scope key, persisted in workspace.xml. */
@State(name = "AgentReviewSession", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewStore(private val project: Project) : PersistentStateComponent<ReviewStore.State> {

    class State {
        var json: String = ""
    }

    @Volatile
    private var storage: ReviewStorage = ReviewStorage()

    val session: ReviewSession
        get() = storage.sessions[storage.currentKey] ?: ReviewSession()

    val sessionCount: Int get() = storage.sessions.count { !it.value.isEmpty }

    override fun getState(): State = State().also {
        val current = storage.currentKey
        val kept = storage.sessions
            .filter { (k, v) -> k == current || !v.isEmpty }
            .entries.sortedByDescending { it.value.updatedAt }
            .take(MAX_SESSIONS)
            .associate { it.key to it.value }
        it.json = json.encodeToString(ReviewStorage.serializer(), storage.copy(sessions = kept))
    }

    override fun loadState(state: State) {
        if (state.json.isBlank()) return
        storage = try {
            if (state.json.contains("\"sessions\"")) {
                json.decodeFromString(ReviewStorage.serializer(), state.json)
            } else {
                // Pre-0.2 format: a single session.
                val legacy = json.decodeFromString(ReviewSession.serializer(), state.json)
                ReviewStorage(mapOf(legacy.scope.key() to legacy), legacy.scope.key())
            }
        } catch (e: Exception) {
            LOG.warn("Discarding unreadable review state", e)
            ReviewStorage()
        }
    }

    fun update(transform: (ReviewSession) -> ReviewSession) {
        val updated = synchronized(this) {
            val next = transform(session).copy(updatedAt = System.currentTimeMillis())
            storage = storage.copy(sessions = storage.sessions + (storage.currentKey to next))
            next
        }
        val app = ApplicationManager.getApplication()
        // Listeners touch editors and Swing; MCP calls arrive on a coroutine thread.
        if (app.isDispatchThread) publish(updated) else app.invokeLater({ publish(updated) }, project.disposed)
    }

    private fun publish(updated: ReviewSession) {
        if (!project.isDisposed) project.messageBus.syncPublisher(ReviewListener.TOPIC).sessionChanged(updated)
    }

    fun addComment(comment: Comment) = update { it.copy(comments = it.comments + comment) }

    fun updateComment(id: String, transform: (Comment) -> Comment) =
        update { s -> s.copy(comments = s.comments.map { if (it.id == id) transform(it) else it }) }

    fun removeComment(id: String) = update { s -> s.copy(comments = s.comments.filterNot { it.id == id }) }

    fun setReviewed(path: String, hash: String?) = update { s ->
        if (hash == null) s.copy(reviewed = s.reviewed - path) else s.copy(reviewed = s.reviewed + (path to hash))
    }

    /** Switches to the session of [scope], creating it when needed. */
    fun setScope(scope: Scope) {
        synchronized(this) {
            val key = scope.key()
            val existing = storage.sessions[key]?.copy(scope = scope) ?: ReviewSession(scope = scope)
            storage = ReviewStorage(storage.sessions + (key to existing), key)
        }
        update { it }
    }

    fun setNotes(notes: String) = update { it.copy(notes = notes) }

    /** Wipes the current session's comments, marks and notes. */
    fun clear() = update { ReviewSession(scope = it.scope) }

    /** Drops every session, including the current one. Scope selection is kept. */
    fun clearAll() {
        synchronized(this) {
            storage = ReviewStorage(mapOf(storage.currentKey to ReviewSession(scope = session.scope)), storage.currentKey)
        }
        update { it }
    }

    /** Drops every session except the current one. */
    fun forgetOtherSessions() {
        synchronized(this) {
            storage = storage.copy(sessions = storage.sessions.filterKeys { it == storage.currentKey })
        }
        update { it }
    }

    companion object {
        private const val MAX_SESSIONS = 30
        private val LOG = logger<ReviewStore>()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun getInstance(project: Project): ReviewStore = project.service()
    }
}
