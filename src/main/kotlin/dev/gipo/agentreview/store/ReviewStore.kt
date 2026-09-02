package dev.gipo.agentreview.store

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
import dev.gipo.agentreview.model.Scope
import kotlinx.serialization.json.Json

interface ReviewListener {
    fun sessionChanged(session: ReviewSession)

    companion object {
        val TOPIC: Topic<ReviewListener> = Topic.create("AgentReview.session", ReviewListener::class.java)
    }
}

/** One review session per project, persisted in workspace.xml. */
@State(name = "AgentReviewSession", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewStore(private val project: Project) : PersistentStateComponent<ReviewStore.State> {

    class State {
        var json: String = ""
    }

    @Volatile
    var session: ReviewSession = ReviewSession()
        private set

    override fun getState(): State = State().also { it.json = json.encodeToString(ReviewSession.serializer(), session) }

    override fun loadState(state: State) {
        if (state.json.isBlank()) return
        session = try {
            json.decodeFromString(ReviewSession.serializer(), state.json)
        } catch (e: Exception) {
            LOG.warn("Discarding unreadable review session", e)
            ReviewSession()
        }
    }

    @Synchronized
    fun update(transform: (ReviewSession) -> ReviewSession) {
        session = transform(session)
        project.messageBus.syncPublisher(ReviewListener.TOPIC).sessionChanged(session)
    }

    fun addComment(comment: Comment) = update { it.copy(comments = it.comments + comment) }

    fun updateComment(id: String, transform: (Comment) -> Comment) =
        update { s -> s.copy(comments = s.comments.map { if (it.id == id) transform(it) else it }) }

    fun removeComment(id: String) = update { s -> s.copy(comments = s.comments.filterNot { it.id == id }) }

    fun setReviewed(path: String, hash: String?) = update { s ->
        if (hash == null) s.copy(reviewed = s.reviewed - path) else s.copy(reviewed = s.reviewed + (path to hash))
    }

    fun setScope(scope: Scope) = update { it.copy(scope = scope) }

    fun setNotes(notes: String) = update { it.copy(notes = notes) }

    fun clear() = update { ReviewSession(scope = it.scope) }

    companion object {
        private val LOG = logger<ReviewStore>()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun getInstance(project: Project): ReviewStore = project.service()
    }
}
