package dev.gipo.agentreview.scope

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.VirtualFile
import dev.gipo.agentreview.model.ContentHash
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import git4idea.changes.GitChangeUtils
import git4idea.repo.GitRepositoryManager

/** Resolves a [Scope] to the list of changes to review. Runs git, call off the EDT. */
object ScopeChanges {

    fun collect(project: Project, scope: Scope): List<Change> {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return when (scope.kind) {
            ScopeKind.UNCOMMITTED -> uncommitted(project)
            ScopeKind.STAGED -> repos.flatMap { repo ->
                val paths = GitChangeUtils.getStagedChanges(project, repo.root).map { it.filePath }
                indexVsHead(project, repo.root, paths)
            }
            ScopeKind.UNSTAGED -> repos.flatMap { repo ->
                val paths = GitChangeUtils.getUnstagedChanges(project, repo.root, null, true).map { it.filePath }
                GitChangeUtils.getLocalChangesDiff(project, repo.root, paths)
            }
            ScopeKind.RANGE -> repos.flatMap { repo ->
                GitChangeUtils.getDiff(repo, scope.base ?: "HEAD", scope.head ?: "HEAD", true).orEmpty()
            }
            ScopeKind.COMMIT -> {
                val hash = scope.head ?: return emptyList()
                repos.flatMap { repo -> GitChangeUtils.getDiff(repo, "$hash~1", hash, true).orEmpty() }
            }
        }.sortedBy { ChangesUtil.getFilePath(it).path }
    }

    private fun indexVsHead(project: Project, root: VirtualFile, paths: List<FilePath>): Collection<Change> {
        if (paths.isEmpty()) return emptyList()
        return GitChangeUtils.getDiffWithWorkingDir(project, root, "HEAD", paths, false, true)
    }

    private fun uncommitted(project: Project): List<Change> {
        val clm = ChangeListManager.getInstance(project)
        val tracked = clm.allChanges.toList()
        val unversioned = clm.unversionedFilesPaths
            .filter { !it.isDirectory }
            .map { Change(null, CurrentContentRevision(it)) }
        return tracked + unversioned
    }

    fun currentBranch(project: Project): String? {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        return repo.currentBranchName ?: repo.currentRevision?.take(8)
    }

    /** Hash of the "after" side; null when the file was deleted. */
    fun afterHash(change: Change): String? {
        val content = try {
            change.afterRevision?.content
        } catch (e: Exception) {
            null
        } ?: return null
        return ContentHash.of(content)
    }
}

object ReviewPaths {
    fun relative(project: Project, path: FilePath): String = relative(project, path.path)

    fun relative(project: Project, absolute: String): String {
        val base = (project.basePath ?: return absolute).trimEnd('/') + "/"
        return if (absolute.startsWith(base)) absolute.removePrefix(base) else absolute
    }

    fun relative(project: Project, change: Change): String = relative(project, ChangesUtil.getFilePath(change))
}
