package dev.gipo.agentreview.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.settings.AgentReviewSettings
import dev.gipo.agentreview.settings.AutoMark
import dev.gipo.agentreview.store.ReviewStore
import dev.gipo.agentreview.model.ContentHash
import com.intellij.diff.util.Side as DiffSide

class ReviewDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        val path = resolvePath(project, request) ?: return

        val bindings = when (viewer) {
            is TwosideTextDiffViewer -> listOf(
                EditorReviewBinding(project, viewer.editor1, path, SingleSideMapper(Side.OLD), Side.OLD, viewer),
                EditorReviewBinding(project, viewer.editor2, path, SingleSideMapper(Side.NEW), Side.NEW, viewer),
            )
            is OnesideTextDiffViewer -> listOf(
                EditorReviewBinding(project, viewer.editor, path, SingleSideMapper(Side.NEW), Side.NEW, viewer),
            )
            is UnifiedDiffViewer -> listOf(
                EditorReviewBinding(project, viewer.editor, path, UnifiedMapper(viewer), Side.NEW, viewer),
            )
            else -> return
        }
        // The unified document and line convertors only exist after the first rediff.
        viewer.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() = bindings.forEach { it.render() }
        })
        val isDeleted = request.getUserData(ChangeDiffRequestProducer.CHANGE_KEY)?.afterRevision == null &&
            request.getUserData(ChangeDiffRequestProducer.CHANGE_KEY) != null
        val newContent: (() -> CharSequence)? = when (viewer) {
            is TwosideTextDiffViewer -> ({ viewer.editor2.document.charsSequence })
            is UnifiedDiffViewer -> ({ viewer.getContent(DiffSide.RIGHT).document.charsSequence })
            is OnesideTextDiffViewer -> if (isDeleted) null else ({ viewer.editor.document.charsSequence })
            else -> null
        }
        val combined = context.javaClass.name.contains("Combined")
        installAutoReviewed(project, viewer, path, newContent, combined, bindings.map { it.editor })
    }

    /**
     * Hash preference: the scope model (matches the tree), then the visible document, then "unknown".
     * In the combined (continuous) viewer many blocks exist at once, so "opened" means the block got
     * focus and "closed" means it lost it.
     */
    private fun installAutoReviewed(
        project: Project,
        viewer: DiffViewerBase,
        path: String,
        content: (() -> CharSequence)?,
        combined: Boolean,
        editors: List<com.intellij.openapi.editor.ex.EditorEx>,
    ) {
        val settings = AgentReviewSettings.getInstance().state
        if (settings.autoMark == AutoMark.OFF) return
        val hash = ReviewChangesModel.getInstance(project).find(path)?.hash
            ?: content?.let { c ->
                try {
                    ContentHash.of(c())
                } catch (e: Exception) {
                    null
                }
            }
            ?: ""
        val mark = { if (!project.isDisposed) ReviewStore.getInstance(project).setReviewed(path, hash) }
        if (!combined) {
            if (settings.autoMark == AutoMark.ON_OPEN) mark()
            if (settings.autoMark == AutoMark.ON_CLOSE) com.intellij.openapi.util.Disposer.register(viewer) { mark() }
            return
        }
        var visited = false
        val listener = object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) {
                visited = true
                if (AgentReviewSettings.getInstance().state.autoMark == AutoMark.ON_OPEN) mark()
            }

            override fun focusLost(e: java.awt.event.FocusEvent) {
                if (visited && AgentReviewSettings.getInstance().state.autoMark == AutoMark.ON_CLOSE) mark()
            }
        }
        editors.forEach { it.contentComponent.addFocusListener(listener) }
        com.intellij.openapi.util.Disposer.register(viewer) {
            editors.forEach { if (!it.isDisposed) it.contentComponent.removeFocusListener(listener) }
            if (visited && AgentReviewSettings.getInstance().state.autoMark == AutoMark.ON_CLOSE) mark()
        }
    }

    private fun resolvePath(project: Project, request: DiffRequest): String? {
        request.getUserData(ChangeDiffRequestProducer.CHANGE_KEY)?.let { return ReviewPaths.relative(project, it) }
        val contents = (request as? ContentDiffRequest)?.contents ?: return null
        for (c in contents.asReversed()) {
            val file = when (c) {
                is FileContent -> c.file
                is DocumentContent -> c.highlightFile
                else -> null
            } ?: continue
            if (file.isInLocalFileSystem) return ReviewPaths.relative(project, file.path)
        }
        return null
    }

    /** Unified view: editor lines are "oneside" lines. */
    private class UnifiedMapper(private val viewer: UnifiedDiffViewer) : LineMapper {
        override fun toEditor(side: Side, line: Int): Int? {
            val diffSide = if (side == Side.OLD) DiffSide.LEFT else DiffSide.RIGHT
            return try {
                viewer.transferLineToOneside(diffSide, line - 1).takeIf { it >= 0 }
            } catch (e: Exception) {
                null
            }
        }

        override fun fromEditor(editorLine: Int): Pair<Side, Int>? {
            val pair = try {
                viewer.transferLineFromOneside(editorLine)
            } catch (e: Exception) {
                return null
            }
            val lines = pair.first
            val master = pair.second
            val side = if (master == DiffSide.LEFT) Side.OLD else Side.NEW
            val line = lines[master.index]
            return side to line + 1
        }
    }
}
