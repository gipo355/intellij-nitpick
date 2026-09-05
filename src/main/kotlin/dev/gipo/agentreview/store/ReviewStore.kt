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

    /** Bumped on every change of [storage]; cache key for derived views (placed comments). */
    @Volatile
    var version: Long = 0L
        private set

    val session: ReviewSession
        get() = storage.sessions[storage.currentKey] ?: ReviewSession()

    val sessionCount: Int get() = storage.sessions.count { !it.value.isEmpty }

    val comments: List<Comment> get() = storage.comments
    val currentKey: String get() = storage.currentKey

    /** Other sessions' reviewed marks, for hash carry-over. */
    fun otherSessions(): List<ReviewSession> = storage.sessions.filterKeys { it != storage.currentKey }.values.toList()

    /** Current session first, then the non-empty others newest first. */
    fun savedSessions(): List<ReviewSession> {
        val s = storage
        val others = s.sessions.filter { (k, v) -> k != s.currentKey && !v.isEmpty }.values.reversed().sortedByDescending { it.updatedAt }
        return listOf(session) + others
    }

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
            val loaded = if (state.json.contains("\"sessions\"")) {
                json.decodeFromString(ReviewStorage.serializer(), state.json)
            } else {
                // Pre-0.2 format: a single session.
                val legacy = json.decodeFromString(ReviewSession.serializer(), state.json)
                ReviewStorage(mapOf(legacy.scope.key() to legacy), legacy.scope.key())
            }
            migrate(loaded)
        } catch (e: Exception) {
            LOG.warn("Discarding unreadable review state", e)
            ReviewStorage()
        }
        version++
    }

    /** Pre-0.2.1: comments lived inside sessions. */
    private fun migrate(s: ReviewStorage): ReviewStorage {
        if (s.sessions.values.all { it.comments.isEmpty() }) return s
        val moved = s.sessions.flatMap { (key, session) -> session.comments.map { it.copy(scopeKey = key) } }
        return s.copy(
            comments = s.comments + moved.filter { m -> s.comments.none { it.id == m.id } },
            sessions = s.sessions.mapValues { it.value.copy(comments = emptyList()) },
        )
    }

    fun update(transform: (ReviewSession) -> ReviewSession) {
        val updated = synchronized(this) {
            val next = transform(session).copy(updatedAt = System.currentTimeMillis())
            storage = storage.copy(sessions = storage.sessions + (storage.currentKey to next))
            version++
            next
        }
        val app = ApplicationManager.getApplication()
        // Listeners touch editors and Swing; MCP calls arrive on a coroutine thread.
        if (app.isDispatchThread) publish(updated) else app.invokeLater({ publish(updated) }, project.disposed)
    }

    private fun publish(updated: ReviewSession) {
        if (!project.isDisposed) project.messageBus.syncPublisher(ReviewListener.TOPIC).sessionChanged(updated)
    }

    private fun updateStorage(transform: (ReviewStorage) -> ReviewStorage) {
        synchronized(this) { storage = transform(storage); version++ }
        update { it }
    }

    fun addComment(comment: Comment) = updateStorage { it.copy(comments = it.comments + comment.copy(scopeKey = it.currentKey)) }

    fun updateComment(id: String, transform: (Comment) -> Comment) =
        updateStorage { s -> s.copy(comments = s.comments.map { if (it.id == id) transform(it) else it }) }

    /** Full id, or a unique prefix of at least 4 chars. */
    fun findComment(idOrPrefix: String): Comment? {
        comments.firstOrNull { it.id == idOrPrefix }?.let { return it }
        if (idOrPrefix.length < 4) return null
        return comments.filter { it.id.startsWith(idOrPrefix) }.singleOrNull()
    }

    fun removeComment(id: String) = removeComments(setOf(id))

    fun removeComments(ids: Collection<String>) = updateStorage { s -> s.copy(comments = s.comments.filterNot { it.id in ids }) }

    fun setReviewed(path: String, hash: String?) = update { s ->
        if (hash == null) s.copy(reviewed = s.reviewed - path) else s.copy(reviewed = s.reviewed + (path to hash))
    }

    /** Switches to the session of [scope], creating it when needed. */
    fun setScope(scope: Scope) {
        synchronized(this) {
            val key = scope.key()
            val existing = storage.sessions[key]?.copy(scope = scope) ?: ReviewSession(scope = scope)
            storage = storage.copy(sessions = storage.sessions + (key to existing), currentKey = key)
        }
        update { it }
    }

    /** Adds the session under its scope key and switches to it. Comments with known ids are replaced. */
    fun importSession(session: ReviewSession, comments: List<Comment>) {
        synchronized(this) {
            val ids = comments.map { it.id }.toSet()
            val key = session.scope.key()
            storage = storage.copy(
                sessions = storage.sessions + (key to session),
                comments = storage.comments.filterNot { it.id in ids } + comments,
                currentKey = key,
            )
        }
        update { it }
    }

    fun setNotes(notes: String) = update { it.copy(notes = notes) }

    /** Marks and notes of this scope, plus comments written in it. */
    fun clear() = updateStorage { s ->
        s.copy(
            comments = s.comments.filterNot { it.scopeKey == s.currentKey },
            sessions = s.sessions + (s.currentKey to ReviewSession(scope = session.scope)),
        )
    }

    /** Drops every session and comment. Scope selection is kept. */
    fun clearAll() = updateStorage { s -> ReviewStorage(mapOf(s.currentKey to ReviewSession(scope = session.scope)), s.currentKey) }

    /** Drops one saved session. The current one stays. Comments are project-wide and are kept. */
    fun forgetSession(key: String) {
        synchronized(this) {
            if (key == storage.currentKey) return
            storage = storage.copy(sessions = storage.sessions - key)
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
