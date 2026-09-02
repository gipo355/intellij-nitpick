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
    ToolWindowManager.getInstance(project).getToolWindow("Agent Review")?.activate(null)
}

class ReviewCommitAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val commits = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits
        e.presentation.isEnabledAndVisible = e.project != null && commits != null && commits.size in 1..2
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commits = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits ?: return
        val scope = if (commits.size == 1) {
            Scope(ScopeKind.COMMIT, head = commits[0].hash.asString())
        } else {
            // Two commits selected: review everything between the older and the newer one.
            Scope(ScopeKind.RANGE, base = commits[1].hash.asString(), head = commits[0].hash.asString())
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
