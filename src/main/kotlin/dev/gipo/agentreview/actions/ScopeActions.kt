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
import dev.gipo.agentreview.model.RepoRange
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.scope.RefInput
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.scope.ScopeChanges
import dev.gipo.agentreview.store.ReviewStore
import dev.gipo.agentreview.ui.Notifications
import git4idea.GitBranch
import git4idea.actions.branch.GitSingleBranchAction
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

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

/**
 * Log selection of one repo as its workspace range, like the log's "Compare Versions": one commit is its own change,
 * two or more are oldest..newest with the oldest as base, not included. Everything git has in between counts, shown in
 * the log or not. When the newest is HEAD the range reaches the working tree.
 */
class AddToWorkspaceAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val commits = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits.orEmpty()
        val root = commits.firstOrNull()?.root
        if (project == null || root == null || commits.any { it.root != root }) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val head = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)?.currentRevision
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = if (head == commits.first().hash.asString()) "Add to Nitpick, with Uncommitted Changes" else "Add to Nitpick"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commits = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits?.takeIf { it.isNotEmpty() } ?: return
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(commits.first().root) ?: return
        // Log rows are newest first.
        val newest = commits.first().hash.asString()
        val oldest = commits.last().hash.asString()
        val single = commits.size == 1
        val toWorkingTree = repo.currentRevision == newest
        AppExecutorUtil.getAppExecutorService().execute {
            val base = if (single) ScopeChanges.parent(project, repo, newest) else oldest
            val onHistory = single || ScopeChanges.isAncestor(project, repo, oldest, newest)
            val count = base?.let { ScopeChanges.commitCount(project, repo, it, newest) }
            val selected = if (single) 1 else commits.size - 1
            ApplicationManager.getApplication().invokeLater({
                if (base == null) {
                    Notifications.warn(project, "Not added to Nitpick", "${newest.take(8)} is a root commit.")
                    return@invokeLater
                }
                if (!onHistory) {
                    Notifications.warn(project, "Not added to Nitpick", "${oldest.take(8)} is not in the history of ${newest.take(8)}. Select commits of one branch.")
                    return@invokeLater
                }
                // A filtered log hides commits that the range still holds.
                if (count != null && count > selected) {
                    Notifications.info(project, "Added $count commits to Nitpick", "${base.take(8)}..${newest.take(8)} holds $count commits, $selected selected above the base. The others are hidden by log filters or come in through merges.")
                }
                val range = RepoRange(
                    ScopeChanges.repoId(project, repo), base, if (toWorkingTree) null else newest,
                    baseRef = if (single) "${newest.take(8)}^" else oldest.take(8), headRef = if (toWorkingTree) null else newest.take(8),
                )
                startReview(project, workspaceScope(project).withRange(range))
            }, ModalityState.nonModal(), project.disposed)
        }
    }
}

/**
 * Branch popups: the branch as the workspace range of each repo that has it, from its merge-base with `origin/HEAD`.
 * The checked-out branch reaches the working tree.
 */
class AddBranchToWorkspaceAction : GitSingleBranchAction() {
    override fun updateIfEnabledAndVisible(e: AnActionEvent, project: Project, repositories: List<GitRepository>, reference: GitBranch) {
        e.presentation.text = "Add Branch '${reference.name}' to Nitpick"
    }

    override fun actionPerformed(e: AnActionEvent, project: Project, repositories: List<GitRepository>, reference: GitBranch) {
        AppExecutorUtil.getAppExecutorService().execute {
            val ranges = ArrayList<RepoRange>()
            val problems = ArrayList<String>()
            for (repo in repositories) {
                val id = ScopeChanges.repoId(project, repo)
                val default = ScopeChanges.defaultBranch(project, repo)
                if (default == null) {
                    problems += "$id: origin/HEAD is not set (git remote set-head origin -a)"
                    continue
                }
                val head = if (repo.currentBranchName == reference.name) null else RefInput(reference.name)
                ScopeChanges.pinRange(project, id, RefInput(default, mergeBase = true), head)
                    .onSuccess { r -> if (r.head != null && r.head == r.base) problems += "$id: ${reference.name} has nothing over $default" else ranges += r }
                    .onFailure { problems += "$id: ${it.message}" }
            }
            ApplicationManager.getApplication().invokeLater({
                if (problems.isNotEmpty()) Notifications.warn(project, "Not added to Nitpick", problems.joinToString("<br>"))
                if (ranges.isNotEmpty()) startReview(project, ranges.fold(workspaceScope(project)) { scope, r -> scope.withRange(r) })
            }, ModalityState.nonModal(), project.disposed)
        }
    }
}

/** The live workspace scope, or an empty one. */
internal fun workspaceScope(project: Project): Scope =
    ReviewStore.getInstance(project).liveSession(Scope(ScopeKind.WORKSPACE).key())?.scope ?: Scope(ScopeKind.WORKSPACE)

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
