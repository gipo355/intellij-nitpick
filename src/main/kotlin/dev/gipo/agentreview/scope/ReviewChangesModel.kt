package dev.gipo.agentreview.scope

import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.LocalFileSystem
import dev.gipo.agentreview.ui.Notifications
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffContext
import com.intellij.util.messages.Topic
import dev.gipo.agentreview.model.ReviewState
import dev.gipo.agentreview.store.ReviewStore

data class ReviewedChange(val change: Change, val path: String, val hash: String?)

interface ChangesListener {
    fun changesUpdated(changes: List<ReviewedChange>)

    companion object {
        val TOPIC: Topic<ChangesListener> = Topic.create("AgentReview.changes", ChangesListener::class.java)
    }
}

/** Cached list of changes for the current scope, with content hashes. */
@Service(Service.Level.PROJECT)
class ReviewChangesModel(private val project: Project) : Disposable {

    @Volatile
    var changes: List<ReviewedChange> = emptyList()
        private set

    @Volatile
    private var refreshing = false

    @Volatile
    private var refreshPending = false

    fun refresh() {
        if (refreshing) {
            refreshPending = true
            return
        }
        refreshing = true
        val scope = ReviewStore.getInstance(project).session.scope
        object : Task.Backgroundable(project, "Collecting changes for review", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = try {
                    ScopeChanges.collect(project, scope).map {
                        ReviewedChange(it, ReviewPaths.relative(project, it), ScopeChanges.afterHash(it))
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to collect changes", e)
                    emptyList()
                }
                changes = result
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) project.messageBus.syncPublisher(ChangesListener.TOPIC).changesUpdated(result)
                }, project.disposed)
            }

            override fun onFinished() {
                refreshing = false
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                }
            }
        }.queue()
    }

    fun find(path: String): ReviewedChange? =
        changes.firstOrNull { it.path == path } ?: changes.firstOrNull { ReviewPaths.matches(it.path, path) }

    fun state(rc: ReviewedChange): ReviewState = ReviewStore.getInstance(project).session.reviewState(rc.path, rc.hash)

    /** Next unreviewed change after [afterPath] (wrapping), or null when all are reviewed. */
    fun nextUnreviewed(afterPath: String?): ReviewedChange? = unreviewedFrom(afterPath, 1)

    fun prevUnreviewed(beforePath: String?): ReviewedChange? = unreviewedFrom(beforePath, -1)

    private fun unreviewedFrom(path: String?, step: Int): ReviewedChange? {
        val list = changes
        if (list.isEmpty()) return null
        val start = list.indexOfFirst { it.path == path }.let { if (it < 0 && step < 0) 0 else it }
        for (i in 1..list.size) {
            val candidate = list[Math.floorMod(start + i * step, list.size)]
            if (state(candidate) != ReviewState.REVIEWED) return candidate
        }
        return null
    }

    /** Installed by the tool window: opens the change in its single reusable preview tab. */
    @Volatile
    var diffOpener: ((ReviewedChange) -> Boolean)? = null

    /** Consumed by the diff binding of [path] on its next render. */
    @Volatile
    var pendingScroll: PendingScroll? = null

    data class PendingScroll(val path: String, val side: dev.gipo.agentreview.model.Side, val line: Int)

    fun openDiff(rc: ReviewedChange, line: Int? = null, side: Side = Side.RIGHT) {
        if (line != null) {
            pendingScroll = PendingScroll(rc.path, if (side == Side.LEFT) dev.gipo.agentreview.model.Side.OLD else dev.gipo.agentreview.model.Side.NEW, line)
        }
        val opened = diffOpener?.invoke(rc)
        LOG.info("openDiff path=${rc.path} line=$line opener=${diffOpener != null} result=$opened")
        if (opened == true) return
        val all = changes.map { it.change }
        val index = all.indexOf(rc.change).coerceAtLeast(0)
        val context = ShowDiffContext()
        if (line != null) context.putChangeContext(rc.change, DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(side, line - 1))
        ShowDiffAction.showDiffForChange(project, all, index, context)
    }

    /** Opens the diff when the file is in scope, else the file itself. */
    fun navigate(path: String, line: Int?, side: Side = Side.RIGHT) {
        val rc = find(path)
        LOG.info("navigate path=$path line=$line inScope=${rc != null} changes=${changes.size}")
        if (rc != null) {
            openDiff(rc, line, side)
            return
        }
        val base = project.basePath
        val vf = LocalFileSystem.getInstance().findFileByPath(if (path.startsWith("/")) path else "$base/$path")
        if (vf == null) {
            Notifications.warn(project, "File not found", path)
            return
        }
        OpenFileDescriptor(project, vf, (line ?: 1) - 1, 0).navigate(true)
        Notifications.info(project, "Not in the current scope", "$path is not part of ${ReviewStore.getInstance(project).session.scope.describe()}. Opened the file instead of a diff.")
    }

    override fun dispose() {}

    companion object {
        private val LOG = logger<ReviewChangesModel>()
        fun getInstance(project: Project): ReviewChangesModel = project.service()
    }
}
