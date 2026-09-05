package dev.gipo.agentreview.actions

import com.intellij.codeInsight.hint.HintManager
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import dev.gipo.agentreview.diff.CommentEditorPopup
import dev.gipo.agentreview.diff.EditorReviewBinding
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.CommentType
import dev.gipo.agentreview.model.ContentHash
import dev.gipo.agentreview.model.ReviewState
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.store.ReviewStore
import dev.gipo.agentreview.ui.Notifications
import javax.swing.JComponent

private fun AnActionEvent.binding(): EditorReviewBinding? {
    if (!EditorReviewBinding.annotationsEnabled) return null
    val editor = getData(DiffDataKeys.CURRENT_EDITOR) ?: getData(CommonDataKeys.EDITOR) ?: return null
    return editor.getUserData(EditorReviewBinding.KEY)
}

/** Off means Nitpick is invisible in editors: shortcuts fired there do nothing. The tool window keeps working. */
internal fun AnActionEvent.hiddenInEditor(): Boolean =
    !EditorReviewBinding.annotationsEnabled && (getData(DiffDataKeys.CURRENT_EDITOR) ?: getData(CommonDataKeys.EDITOR)) != null

/** Path of the file under review: from the diff binding, or the change under the cursor. */
internal fun AnActionEvent.reviewPath(): String? {
    binding()?.let { return it.path }
    val project = project ?: return null
    getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(ChangeDiffRequestProducer.CHANGE_KEY)?.let { return ReviewPaths.relative(project, it) }
    getData(VcsDataKeys.CHANGES)?.firstOrNull()?.let { return ReviewPaths.relative(project, it) }
    return null
}

/** One child per comment of the file under the cursor; opens the editor popup. */
class EditFileCommentsGroup : ActionGroup("Edit Comment", true), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = getChildren(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        if (e.hiddenInEditor()) return emptyArray()
        val path = e.reviewPath() ?: return emptyArray()
        val comments = ReviewChangesModel.getInstance(project).commentsFor(path)
        return comments.map { c ->
            object : AnAction("${c.location()}  ${c.text.lineSequence().first().take(60)}"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val anchor = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) as? JComponent ?: return
                    CommentEditorPopup.show(project, anchor, c.type, c.text) { text, type ->
                        ReviewStore.getInstance(project).updateComment(c.id) { it.copy(text = text, type = type) }
                    }
                }
            }
        }.toTypedArray()
    }
}

abstract class DiffReviewAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.binding() != null
    }
}

class AddCommentAction : DiffReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val binding = e.binding() ?: return
        val (side, start, end) = binding.selectionRange() ?: return
        val snippet = binding.selectedText()
        CommentEditorPopup.showAtCaret(project, binding.editor, CommentType.NOTE, "") { text, type ->
            ReviewStore.getInstance(project).addComment(
                Comment(path = binding.path, side = side, startLine = start, endLine = end, type = type, text = text, snippet = snippet, contentHash = binding.contentHash(side)),
            )
        }
    }
}

class AddFileCommentAction : DiffReviewAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && !e.hiddenInEditor() && e.reviewPath() != null && e.folderPath() == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val path = e.reviewPath() ?: return
        val editor: Editor? = e.binding()?.editor
        val onSave: (String, CommentType) -> Unit = { text, type ->
            ReviewStore.getInstance(project).addComment(Comment(path = path, side = Side.NEW, type = type, text = text))
        }
        if (editor != null) CommentEditorPopup.showAtCaret(project, editor, CommentType.NOTE, "", onSave)
        else e.getData(com.intellij.openapi.actionSystem.PlatformDataKeys.CONTEXT_COMPONENT)?.let {
            CommentEditorPopup.show(project, it as javax.swing.JComponent, CommentType.NOTE, "", onSave)
        }
    }
}

/** Relative path with a trailing `/` when the clicked tree node is a directory. */
internal fun AnActionEvent.folderPath(): String? {
    val project = project ?: return null
    val tree = getData(PlatformDataKeys.CONTEXT_COMPONENT) as? ChangesTree ?: return null
    val node = tree.leadSelectionPath?.lastPathComponent as? ChangesBrowserNode<*> ?: return null
    val filePath = node.userObject as? FilePath ?: return null
    if (!filePath.isDirectory) return null
    return ReviewPaths.relative(project, filePath).trimEnd('/') + "/"
}

/** Every selected change; a folder node in the tree selects all files under it. */
private fun AnActionEvent.reviewPaths(): List<String> {
    val project = project ?: return emptyList()
    if (binding() == null) {
        getData(VcsDataKeys.CHANGES)?.takeIf { it.size > 1 }?.let { changes -> return changes.map { ReviewPaths.relative(project, it) } }
    }
    return listOfNotNull(reviewPath())
}

class AddFolderCommentAction : DiffReviewAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.folderPath() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val path = e.folderPath() ?: return
        val anchor = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) as? JComponent ?: return
        CommentEditorPopup.show(project, anchor, CommentType.NOTE, "") { text, type ->
            ReviewStore.getInstance(project).addComment(Comment(path = path, side = Side.NEW, type = type, text = text))
        }
    }
}

class ToggleReviewedAction : DiffReviewAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val paths = if (e.hiddenInEditor()) emptyList() else e.reviewPaths()
        e.presentation.isEnabledAndVisible = project != null && paths.isNotEmpty()
        if (project == null || paths.isEmpty()) return
        val model = ReviewChangesModel.getInstance(project)
        val allReviewed = paths.all { p -> model.find(p)?.let { model.state(it) } == ReviewState.REVIEWED }
        val suffix = if (paths.size > 1) " (${paths.size} files)" else ""
        e.presentation.text = (if (allReviewed) "Unmark Reviewed" else "Mark Reviewed") + suffix
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val paths = e.reviewPaths()
        val newState = if (paths.size > 1) alignReviewed(project, paths)
        else toggleReviewed(project, paths.singleOrNull() ?: return, e.binding())
        e.binding()?.editor?.let { HintManager.getInstance().showInformationHint(it, "${paths.single()}: ${newState.name.lowercase()}") }
    }

    companion object {
        /** Unmarks all when every path is reviewed, otherwise marks all. Returns the new common state. */
        fun alignReviewed(project: Project, paths: List<String>): ReviewState {
            val store = ReviewStore.getInstance(project)
            val model = ReviewChangesModel.getInstance(project)
            val hashes = paths.associateWith { model.find(it)?.hash }
            val allReviewed = hashes.all { (p, h) -> store.session.reviewState(p, h) == ReviewState.REVIEWED }
            for ((p, h) in hashes) store.setReviewed(p, if (allReviewed) null else h ?: "")
            return if (allReviewed) ReviewState.UNREVIEWED else ReviewState.REVIEWED
        }

        /** Returns the new state. Hash comes from the model, else from the NEW editor document. */
        fun toggleReviewed(project: Project, path: String, binding: EditorReviewBinding?, fallbackHash: (() -> String?)? = null): ReviewState {
            val store = ReviewStore.getInstance(project)
            val model = ReviewChangesModel.getInstance(project)
            val rc = model.find(path)
            val docHash = binding?.takeIf { it.primarySide == Side.NEW }?.let { ContentHash.of(it.editor.document.charsSequence) }
            // Branch mode: the open document is the truth, the model's cached hash may predate unsaved edits.
            val hash = when {
                rc == null -> docHash
                rc.tracksWorkingFile -> docHash ?: rc.hash
                else -> rc.hash ?: docHash
            } ?: fallbackHash?.invoke()
            val current = store.session.reviewState(path, hash)
            return if (current == ReviewState.REVIEWED) {
                store.setReviewed(path, null); ReviewState.UNREVIEWED
            } else {
                // Unknown hash is stored as "": reviewed until a real hash disagrees only if it was never known.
                store.setReviewed(path, hash ?: ""); ReviewState.REVIEWED
            }
        }
    }
}

class NextUnreviewedAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && !e.hiddenInEditor()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val model = ReviewChangesModel.getInstance(project)
        val next = model.nextUnreviewed(e.reviewPath())
        if (next == null) {
            Notifications.info(project, "All files reviewed", "Nothing left in the current scope.")
            return
        }
        model.openDiff(next)
    }
}

class PrevUnreviewedAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && !e.hiddenInEditor()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val model = ReviewChangesModel.getInstance(project)
        val prev = model.prevUnreviewed(e.reviewPath())
        if (prev == null) {
            Notifications.info(project, "All files reviewed", "Nothing left in the current scope.")
            return
        }
        model.openDiff(prev)
    }
}
