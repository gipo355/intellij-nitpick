package dev.gipo.agentreview.scope

import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffContext
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ContentHash
import dev.gipo.agentreview.model.ReviewState
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.store.ReviewStore
import dev.gipo.agentreview.ui.Notifications

/**
 * One file of the current scope. Content and hashes load on first use and are cached until [invalidate],
 * so a scope of thousands of files costs nothing until a file gets a mark, a comment, or is opened.
 */
class ReviewedChange(
    val path: String,
    val change: Change? = null,
    /** The NEW side is the working file: an open editor's document is the truth, the cached hash may lag. */
    val tracksWorkingFile: Boolean = false,
    private val after: () -> CharSequence? = { null },
    private val before: () -> CharSequence? = { null },
    private val presetHash: String? = null,
    private val presetBeforeHash: String? = null,
) {
    /** Eager form for tests and callers that already hold the content. */
    constructor(
        path: String,
        hash: String?,
        beforeHash: String?,
        content: CharSequence?,
        beforeContent: CharSequence?,
        change: Change? = null,
    ) : this(path, change, false, { content }, { beforeContent }, hash, beforeHash)

    private inner class Cache {
        val hash = lazy { presetHash ?: after()?.let(ContentHash::of) }
        val beforeHash = lazy { presetBeforeHash ?: before()?.let(ContentHash::of) }
        val content = lazy { after() }
        val beforeContent = lazy { before() }
        /** `\r\n` folded, for snippet search. */
        val text = lazy { content.value?.toString()?.replace("\r\n", "\n") }
        val beforeText = lazy { beforeContent.value?.toString()?.replace("\r\n", "\n") }
    }

    @Volatile
    private var cache = Cache()

    val hash: String? get() = cache.hash.value
    val beforeHash: String? get() = cache.beforeHash.value
    val content: CharSequence? get() = cache.content.value
    val beforeContent: CharSequence? get() = cache.beforeContent.value
    val text: String? get() = cache.text.value
    val beforeText: String? get() = cache.beforeText.value
    val isHashed: Boolean get() = cache.hash.isInitialized()

    /** Reads and hashes both sides now (call off the EDT). */
    fun prime() {
        hash
        beforeHash
    }

    /** Forgets cached content and hashes; the next read hits the file again. */
    fun invalidate() {
        cache = Cache()
    }

    override fun toString(): String = "ReviewedChange($path)"
}

interface ChangesListener {
    fun changesUpdated(changes: List<ReviewedChange>)

    /** Same files, some content re-read (a save in branch mode). Repaint, do not rebuild. */
    fun hashesChanged() {}

    companion object {
        val TOPIC: Topic<ChangesListener> = Topic.create("AgentReview.changes", ChangesListener::class.java)
    }
}

/** Cached list of changes for the current scope, indexed by path, with placed comments memoized. */
@Service(Service.Level.PROJECT)
class ReviewChangesModel(private val project: Project) : Disposable {

    @Volatile
    var changes: List<ReviewedChange> = emptyList()
        private set

    @Volatile
    private var byPath: Map<String, ReviewedChange> = emptyMap()

    /** Bumped when [changes] or any cached hash changes; half of the placement cache key. */
    @Volatile
    private var changesVersion = 0L

    private class Placed(val changesVersion: Long, val storeVersion: Long, val all: List<Comment>, val byPath: Map<String, List<Comment>>)

    @Volatile
    private var placed: Placed? = null

    @Volatile
    private var refreshing = false

    @Volatile
    private var refreshPending = false

    private val vfsRefresh = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        // Branch mode has no changelist to follow: files appear and vanish through the VFS, saves change hashes.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onVfsEvents(events)
        })
    }

    val isBranchMode: Boolean get() = ReviewStore.getInstance(project).session.scope.kind == ScopeKind.BRANCH

    fun refresh() {
        if (refreshing) {
            refreshPending = true
            return
        }
        refreshing = true
        val store = ReviewStore.getInstance(project)
        val scope = store.session.scope
        object : Task.Backgroundable(project, "Collecting changes for review", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = try {
                    val working = scope.kind == ScopeKind.BRANCH
                    val list = ScopeChanges.collect(project, scope).map { c ->
                        val afterRev = c.afterRevision
                        val beforeRev = c.beforeRevision
                        ReviewedChange(
                            ReviewPaths.relative(project, c), c, working,
                            { ScopeChanges.content(afterRev) }, { ScopeChanges.content(beforeRev) },
                        )
                    }
                    prime(list, working, store)
                    list
                } catch (e: Exception) {
                    LOG.warn("Failed to collect changes", e)
                    emptyList()
                }
                publishChanges(result)
                inheritReviewedMarks(result)
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

    private fun publishChanges(result: List<ReviewedChange>) {
        changes = result
        byPath = result.associateBy { it.path }
        changesVersion++
        placed = null
    }

    /**
     * Diff scopes hash everything up front, off the EDT, as before. The branch tree hashes only files that
     * carry a mark in any session or a line comment: everything else is UNREVIEWED without a read.
     */
    private fun prime(list: List<ReviewedChange>, working: Boolean, store: ReviewStore) {
        if (!working) {
            list.forEach { it.prime() }
            return
        }
        val marked = HashSet<String>()
        store.session.reviewed.keys.forEach { marked += it }
        store.otherSessions().forEach { marked += it.reviewed.keys }
        store.comments.forEach { if (!it.isReviewLevel && !it.isFolderLevel) marked += it.path }
        if (marked.isEmpty()) return
        for (rc in list) {
            if (rc.path in marked || marked.any { ReviewPaths.matches(it, rc.path) }) rc.prime()
        }
    }

    /** A file marked reviewed in another scope at the same content counts as reviewed here. */
    private fun inheritReviewedMarks(result: List<ReviewedChange>) {
        val store = ReviewStore.getInstance(project)
        val mine = store.session.reviewed
        val others = store.otherSessions()
        if (others.isEmpty()) return
        val inherited = result.mapNotNull { rc ->
            if (mine.containsKey(rc.path)) return@mapNotNull null
            val elsewhere = others.mapNotNull { it.reviewed[rc.path] }
            if (elsewhere.isEmpty()) return@mapNotNull null
            val hash = rc.hash ?: return@mapNotNull null
            if (hash in elsewhere) rc.path to hash else null
        }
        if (inherited.isNotEmpty()) store.update { s -> s.copy(reviewed = s.reviewed + inherited) }
    }

    private fun onVfsEvents(events: List<VFileEvent>) {
        if (project.isDisposed || !isBranchMode) return
        var structural = false
        var touched = false
        val index = ProjectFileIndex.getInstance(project)
        for (e in events) {
            when (e) {
                is VFileContentChangeEvent -> {
                    val rc = find(ReviewPaths.relative(project, e.path)) ?: continue
                    rc.invalidate()
                    touched = true
                }
                is VFileDeleteEvent -> if (find(ReviewPaths.relative(project, e.path)) != null) structural = true
                is VFileCreateEvent, is VFileMoveEvent -> if (e.file?.let { inContent(index, it) } == true) structural = true
                is VFilePropertyChangeEvent -> if (e.isRename && e.file.let { inContent(index, it) }) structural = true
            }
        }
        when {
            structural -> {
                vfsRefresh.cancelAllRequests()
                vfsRefresh.addRequest({ refresh() }, 1000)
            }
            touched -> {
                changesVersion++
                placed = null
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) project.messageBus.syncPublisher(ChangesListener.TOPIC).hashesChanged()
                }, project.disposed)
            }
        }
    }

    private fun inContent(index: ProjectFileIndex, file: VirtualFile): Boolean = try {
        index.isInContent(file)
    } catch (e: Exception) {
        false
    }

    /** Comments visible in the current scope, with lines moved to where their text is now. Memoized. */
    fun comments(): List<Comment> = placedNow().all

    private fun placedNow(): Placed {
        val store = ReviewStore.getInstance(project)
        val cv = changesVersion
        val sv = store.version
        placed?.let { if (it.changesVersion == cv && it.storeVersion == sv) return it }
        val all = CommentPlacer.place(store.comments, changes, store.currentKey)
        val fresh = Placed(cv, sv, all, all.filter { !it.isReviewLevel }.groupBy { it.path })
        placed = fresh
        return fresh
    }

    /** Outside the scope (Git log, commit dialog) comments render at their stored lines. */
    fun commentsFor(path: String): List<Comment> {
        val rc = find(path)
        if (rc == null) {
            return ReviewStore.getInstance(project).comments.filter { !it.isReviewLevel && ReviewPaths.matches(it.path, path) }
        }
        val p = placedNow()
        p.byPath[path]?.let { return it }
        p.byPath[rc.path]?.let { return it }
        return p.all.filter { !it.isReviewLevel && ReviewPaths.matches(it.path, path) }
    }

    fun find(path: String): ReviewedChange? =
        byPath[path] ?: changes.firstOrNull { ReviewPaths.matches(it.path, path) }

    /** Reads the hash only for files that carry a mark, so unmarked files in a big tree cost nothing. */
    fun state(rc: ReviewedChange): ReviewState {
        val session = ReviewStore.getInstance(project).session
        val stored = session.reviewed[rc.path] ?: return ReviewState.UNREVIEWED
        if (stored.isEmpty()) return ReviewState.REVIEWED
        return session.reviewState(rc.path, rc.hash)
    }

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

    /** Diff scopes open the diff. Branch mode opens the source file itself and annotates that. */
    fun openDiff(rc: ReviewedChange, line: Int? = null, side: Side = Side.RIGHT) {
        if (isBranchMode) {
            if (!openSource(rc.path, line)) Notifications.warn(project, "File not found", rc.path)
            return
        }
        if (line != null) {
            pendingScroll = PendingScroll(rc.path, if (side == Side.LEFT) dev.gipo.agentreview.model.Side.OLD else dev.gipo.agentreview.model.Side.NEW, line)
        }
        val opened = diffOpener?.invoke(rc)
        LOG.info("openDiff path=${rc.path} line=$line opener=${diffOpener != null} result=$opened")
        if (opened == true) return
        val change = rc.change ?: return
        val all = changes.mapNotNull { it.change }
        val index = all.indexOf(change).coerceAtLeast(0)
        val context = ShowDiffContext()
        if (line != null) context.putChangeContext(change, DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(side, line - 1))
        ShowDiffAction.showDiffForChange(project, all, index, context)
    }

    /** Opens the working file of [path] in a regular editor, at [line] when given. */
    fun openSource(path: String, line: Int? = null, focus: Boolean = true): Boolean {
        val vf = findFile(path) ?: return false
        pendingScroll = null
        val descriptor = if (line != null) OpenFileDescriptor(project, vf, line - 1, 0) else OpenFileDescriptor(project, vf)
        descriptor.navigate(focus)
        return true
    }

    fun findFile(path: String): VirtualFile? {
        if (path.startsWith("/")) return LocalFileSystem.getInstance().findFileByPath(path)
        val base = project.basePath
        (find(path)?.change?.afterRevision?.file?.virtualFile)?.let { return it }
        return LocalFileSystem.getInstance().findFileByPath("$base/$path")
    }

    /** Opens the diff when the file is in scope, else the file itself. */
    fun navigate(path: String, line: Int?, side: Side = Side.RIGHT) {
        val rc = find(path)
        LOG.info("navigate path=$path line=$line inScope=${rc != null} changes=${changes.size}")
        if (rc != null) {
            openDiff(rc, line, side)
            return
        }
        if (!openSource(path, line)) {
            Notifications.warn(project, "File not found", path)
            return
        }
        Notifications.info(project, "Not in the current scope", "$path is not part of ${ReviewStore.getInstance(project).session.scope.describe()}. Opened the file instead of a diff.")
    }

    override fun dispose() {}

    companion object {
        private val LOG = logger<ReviewChangesModel>()
        fun getInstance(project: Project): ReviewChangesModel = project.service()
    }
}
