package dev.gipo.agentreview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.vcs.log.VcsLogDataKeys
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.store.ReviewStore

internal fun startReview(project: Project, scope: Scope) {
    ReviewStore.getInstance(project).setScope(scope)
    ReviewChangesModel.getInstance(project).refresh()
    ToolWindowManager.getInstance(project).getToolWindow("Nitpick")?.activate(null)
}

class ReviewCommitAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val commits = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits
        val n = commits?.size ?: 0
        e.presentation.isEnabledAndVisible = e.project != null && n >= 1
        e.presentation.text = when {
            n <= 1 -> "Review Commit with Nitpick"
            else -> "Review Range of $n Commits with Nitpick"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commits = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits ?: return
        // Log rows are newest first. Two or more commits: diff oldest vs newest,
        // same semantics as the log's "Compare Versions".
        val newest = commits.first().hash.asString()
        val oldest = commits.last().hash.asString()
        val scope = if (commits.size == 1) {
            Scope(ScopeKind.COMMIT, head = newest)
        } else {
            Scope(ScopeKind.RANGE, base = oldest, head = newest)
        }
        startReview(project, scope)
    }
}

class ReviewUncommittedAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        startReview(e.project ?: return, Scope(ScopeKind.UNCOMMITTED))
    }
}
