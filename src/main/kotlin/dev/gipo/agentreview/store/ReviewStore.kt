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

/** Sessions by key, persisted in workspace.xml. */
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

    val sessionCount: Int get() = storage.let { s -> s.sessions.keys.count { s.hasContent(it) } }

    val comments: List<Comment> get() = storage.comments
    val currentKey: String get() = storage.currentKey

    /** Marks, notes or comments. */
    fun hasContent(key: String = currentKey): Boolean = storage.hasContent(key)

    private fun ReviewStorage.hasContent(key: String): Boolean =
        sessions[key]?.isEmpty == false || comments.any { it.sessionKey == key }

    /** Keys of the newest generation of every scope. Only these share comments and marks across scopes. */
    fun liveKeys(): Set<String> = storage.liveKeys()

    private fun ReviewStorage.liveKeys(): Set<String> {
        val live = sessions.values.groupBy { it.scope.key() }.values.mapTo(HashSet()) { g -> g.maxBy { it.generation }.key }
        // A fresh project has no session yet: the default one counts as live.
        if (currentKey !in sessions) live += currentKey
        return live
    }

    /** Newest generation of a scope. */
    fun liveSession(scopeKey: String): ReviewSession? =
        storage.sessions.values.filter { it.scope.key() == scopeKey }.maxByOrNull { it.generation }

    /** Live sessions of other scopes, for hash carry-over. A superseded session inherits nothing. */
    fun otherSessions(): List<ReviewSession> {
        val s = storage
        val live = s.liveKeys()
        if (s.currentKey !in live) return emptyList()
        return s.sessions.filter { (k, _) -> k != s.currentKey && k in live }.values.toList()
    }

    /** Current session first, then the others with content newest first. */
    fun savedSessions(): List<ReviewSession> {
        val s = storage
        val others = s.sessions.filter { (k, _) -> k != s.currentKey && s.hasContent(k) }.values.reversed().sortedByDescending { it.updatedAt }
        return listOf(session) + others
    }

    override fun getState(): State = State().also {
        val s = storage
        val kept = s.sessions
            .filter { (k, _) -> k == s.currentKey || s.hasContent(k) }
            .entries.sortedByDescending { it.value.updatedAt }
            .take(MAX_SESSIONS)
            .associate { it.key to it.value }
        it.json = json.encodeToString(ReviewStorage.serializer(), s.copy(sessions = kept, comments = s.comments.filter { it.sessionKey in kept }))
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
        val moved = s.sessions.flatMap { (key, session) -> session.comments.map { it.copy(sessionKey = key) } }
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

    fun addComment(comment: Comment) = updateStorage { it.copy(comments = it.comments + comment.copy(sessionKey = it.currentKey)) }

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

    /** Switches to the live session of [scope], creating generation 1 when the scope has none. */
    fun setScope(scope: Scope) {
        synchronized(this) {
            val next = liveSession(scope.key())?.copy(scope = scope) ?: ReviewSession(scope = scope)
            storage = storage.copy(sessions = storage.sessions + (next.key to next), currentKey = next.key)
        }
        update { it }
    }

    /** Switches to an exact session, e.g. a superseded generation. Unknown keys are ignored. */
    fun setCurrent(key: String) {
        synchronized(this) {
            if (key !in storage.sessions) return
            storage = storage.copy(currentKey = key)
        }
        update { it }
    }

    /** Next generation of [scope] (the current one by default), empty. The current session stays, superseded. */
    fun newSession(scope: Scope = session.scope) {
        synchronized(this) {
            val generation = (liveSession(scope.key())?.generation ?: 0) + 1
            val next = ReviewSession(scope = scope, generation = generation)
            storage = storage.copy(sessions = storage.sessions + (next.key to next), currentKey = next.key)
        }
        update { it }
    }

    /** Adds the session under its key and switches to it. The comments become its own; known ids are replaced. */
    fun importSession(session: ReviewSession, comments: List<Comment>) {
        synchronized(this) {
            val ids = comments.map { it.id }.toSet()
            val key = session.key
            storage = storage.copy(
                sessions = storage.sessions + (key to session),
                comments = storage.comments.filterNot { it.id in ids } + comments.map { it.copy(sessionKey = key) },
                currentKey = key,
            )
        }
        update { it }
    }

    fun setNotes(notes: String) = update { it.copy(notes = notes) }

    /** Marks, notes and comments of this session. Key and generation stay. */
    fun clear() = updateStorage { s ->
        s.copy(
            comments = s.comments.filterNot { it.sessionKey == s.currentKey },
            sessions = s.sessions + (s.currentKey to ReviewSession(scope = session.scope, generation = session.generation)),
        )
    }

    /** Drops every session and comment. Scope selection is kept. */
    fun clearAll() = updateStorage { s ->
        ReviewStorage(mapOf(s.currentKey to ReviewSession(scope = session.scope, generation = session.generation)), s.currentKey)
    }

    /** Drops one saved session and its comments. The current one stays. */
    fun forgetSession(key: String) {
        synchronized(this) {
            if (key == storage.currentKey) return
            storage = storage.copy(sessions = storage.sessions - key, comments = storage.comments.filterNot { it.sessionKey == key })
        }
        update { it }
    }

    /** Drops every session except the current one, comments included. */
    fun forgetOtherSessions() {
        synchronized(this) {
            val current = storage.currentKey
            storage = storage.copy(
                sessions = storage.sessions.filterKeys { it == current },
                comments = storage.comments.filter { it.sessionKey == current },
            )
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
