package dev.gipo.agentreview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.vcs.log.VcsLogDataKeys
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.scope.ScopeChanges
import dev.gipo.agentreview.store.ReviewStore
import git4idea.GitBranch
import git4idea.actions.branch.GitSingleBranchAction
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository

internal fun startReview(project: Project, scope: Scope) {
    dev.gipo.agentreview.diff.BranchEditorBinder.getInstance(project)
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
        // Multi-repo project: the log row knows its root, so the scope names it.
        val repo = if (ScopeChanges.repoIds(project).size > 1) ReviewPaths.repoId(project.basePath, commits.first().root.path) else null
        val scope = if (commits.size == 1) {
            Scope(ScopeKind.COMMIT, head = newest, repo = repo)
        } else {
            Scope(ScopeKind.RANGE, base = oldest, head = newest, repo = repo)
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

/** Branch popups (Git menu, Branches tool window, log labels): review what the branch adds over HEAD, from their merge-base. */
class ReviewBranchAction : GitSingleBranchAction() {
    override fun updateIfEnabledAndVisible(e: AnActionEvent, project: Project, repositories: List<GitRepository>, reference: GitBranch) {
        e.presentation.text = "Review Branch '${reference.name}' with Nitpick"
    }

    override fun actionPerformed(e: AnActionEvent, project: Project, repositories: List<GitRepository>, reference: GitBranch) {
        val repo = repositories.firstOrNull() ?: return
        val repoId = if (ScopeChanges.repoIds(project).size > 1) ScopeChanges.repoId(project, repo) else null
        val ref = reference.name
        val current = repo.currentBranchName ?: "HEAD"
        AppExecutorUtil.getAppExecutorService().execute {
            val mb = try {
                GitHistoryUtils.getMergeBase(project, repo.root, "HEAD", ref)?.rev
            } catch (ex: Exception) {
                null
            }
            val scope = if (mb == null) Scope(ScopeKind.RANGE, base = "HEAD", head = ref, repo = repoId)
            else Scope(ScopeKind.RANGE, base = mb, head = ref, baseLabel = "merge-base($current)", repo = repoId)
            ApplicationManager.getApplication().invokeLater({ startReview(project, scope) }, ModalityState.nonModal(), project.disposed)
        }
    }
}
