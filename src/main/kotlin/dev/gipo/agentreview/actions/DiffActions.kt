package dev.gipo.agentreview.actions

import com.intellij.codeInsight.hint.HintManager
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.vcs.VcsDataKeys
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
    val editor = getData(DiffDataKeys.CURRENT_EDITOR) ?: getData(CommonDataKeys.EDITOR) ?: return null
    return editor.getUserData(EditorReviewBinding.KEY)
}

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
        val path = e.reviewPath() ?: return emptyArray()
        val comments = ReviewStore.getInstance(project).session.commentsFor(path)
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
                Comment(path = binding.path, side = side, startLine = start, endLine = end, type = type, text = text, snippet = snippet),
            )
        }
    }
}

class AddFileCommentAction : DiffReviewAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.reviewPath() != null
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

class ToggleReviewedAction : DiffReviewAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val path = e.reviewPath()
        e.presentation.isEnabledAndVisible = project != null && path != null
        if (project == null || path == null) return
        val model = ReviewChangesModel.getInstance(project)
        val reviewed = model.find(path)?.let { model.state(it) } == ReviewState.REVIEWED
        e.presentation.text = if (reviewed) "Unmark Reviewed" else "Mark Reviewed"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val path = e.reviewPath() ?: return
        val newState = toggleReviewed(project, path, e.binding())
        e.binding()?.editor?.let { HintManager.getInstance().showInformationHint(it, "$path: ${newState.name.lowercase()}") }
    }

    companion object {
        /** Returns the new state. Hash comes from the model, else from the NEW editor document. */
        fun toggleReviewed(project: Project, path: String, binding: EditorReviewBinding?, fallbackHash: (() -> String?)? = null): ReviewState {
            val store = ReviewStore.getInstance(project)
            val model = ReviewChangesModel.getInstance(project)
            val hash = model.find(path)?.hash
                ?: binding?.takeIf { it.primarySide == Side.NEW }?.let { ContentHash.of(it.editor.document.charsSequence) }
                ?: fallbackHash?.invoke()
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
        e.presentation.isEnabledAndVisible = e.project != null
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
        e.presentation.isEnabledAndVisible = e.project != null
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
