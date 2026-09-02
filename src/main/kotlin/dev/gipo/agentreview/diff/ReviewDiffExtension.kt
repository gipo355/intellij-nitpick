package dev.gipo.agentreview.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.settings.AgentReviewSettings
import dev.gipo.agentreview.store.ReviewStore
import dev.gipo.agentreview.model.ContentHash
import com.intellij.diff.util.Side as DiffSide

class ReviewDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        val path = resolvePath(project, request) ?: return

        when (viewer) {
            is TwosideTextDiffViewer -> {
                EditorReviewBinding(project, viewer.editor1, path, SingleSideMapper(Side.OLD), Side.OLD, viewer)
                EditorReviewBinding(project, viewer.editor2, path, SingleSideMapper(Side.NEW), Side.NEW, viewer)
                installAutoReviewed(project, viewer, path) { viewer.editor2.document.charsSequence }
            }
            is OnesideTextDiffViewer -> {
                EditorReviewBinding(project, viewer.editor, path, SingleSideMapper(Side.NEW), Side.NEW, viewer)
            }
            is UnifiedDiffViewer -> {
                EditorReviewBinding(project, viewer.editor, path, UnifiedMapper(viewer), Side.NEW, viewer)
                installAutoReviewed(project, viewer, path) { viewer.getContent(DiffSide.RIGHT).document.charsSequence }
            }
        }
    }

    private fun installAutoReviewed(project: Project, viewer: FrameDiffTool.DiffViewer, path: String, content: () -> CharSequence) {
        val settings = AgentReviewSettings.getInstance().state
        if (!settings.autoMarkReviewedOnClose && !settings.autoMarkReviewedOnOpen) return
        val hash = try {
            ContentHash.of(content())
        } catch (e: Exception) {
            return
        }
        if (settings.autoMarkReviewedOnOpen) ReviewStore.getInstance(project).setReviewed(path, hash)
        if (settings.autoMarkReviewedOnClose) {
            com.intellij.openapi.util.Disposer.register(viewer) {
                if (!project.isDisposed) ReviewStore.getInstance(project).setReviewed(path, hash)
            }
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
