package dev.gipo.agentreview.scope

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import git4idea.changes.GitChangeUtils
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager

/** Resolves a [Scope] to the list of changes to review. Runs git, call off the EDT. */
object ScopeChanges {
    private const val INDEX_REV = ":0"

    fun collect(project: Project, scope: Scope): List<Change> {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return when (scope.kind) {
            ScopeKind.UNCOMMITTED -> uncommitted(project)
            ScopeKind.STAGED -> repos.flatMap { repo ->
                GitChangeUtils.getStagedChanges(project, repo.root).map { stagedChange(project, it) }
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
            ScopeKind.BRANCH -> branchTree(project, scope.root)
        }.sortedBy { ChangesUtil.getFilePath(it).path }
    }

    /**
     * Every text file the project file index knows under [root] (project-relative, or null for all content),
     * as an "added" change whose content is the working file. No git call: excludes and libraries are the IDE's.
     */
    private fun branchTree(project: Project, root: String?): List<Change> {
        val index = ProjectFileIndex.getInstance(project)
        val files = ArrayList<VirtualFile>()
        val iterator = ContentIterator { vf ->
            if (!vf.isDirectory && !vf.fileType.isBinary && !index.isInLibrary(vf)) files += vf
            true
        }
        runReadAction {
            val dir = root?.let { rootDir(project, it) }
            if (root == null) {
                index.iterateContent(iterator)
            } else if (dir != null) {
                index.iterateContentUnderDirectory(dir, iterator)
            }
        }
        return files.map { Change(null, CurrentContentRevision(VcsUtil.getFilePath(it))) }
    }

    /** [relative] (trailing `/` optional) under the project base, else under the first VCS root. */
    fun rootDir(project: Project, relative: String): VirtualFile? {
        val rel = relative.trim('/')
        val bases = listOfNotNull(project.basePath) +
            GitRepositoryManager.getInstance(project).repositories.map { it.root.path }
        for (base in bases) {
            val vf = LocalFileSystem.getInstance().findFileByPath(if (rel.isEmpty()) base else "$base/$rel")
            if (vf != null && vf.isDirectory) return vf
        }
        return null
    }

    /** `git stash list` of the first repository as (ref, message), newest first. */
    fun stashes(project: Project): List<Pair<String, String>> {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return emptyList()
        val handler = GitLineHandler(project, repo.root, GitCommand.STASH)
        handler.addParameters("list", "--format=%gd%x1f%s")
        handler.setSilent(true)
        return try {
            Git.getInstance().runCommand(handler).output.mapNotNull { line ->
                val sep = line.indexOf('\u001f')
                if (sep < 0) null else line.substring(0, sep) to line.substring(sep + 1)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** A stash is a commit on top of the HEAD it was taken from, so it reviews as the range `ref~1..ref`. */
    fun stashScope(ref: String): Scope = Scope(ScopeKind.RANGE, base = "$ref~1", head = ref, baseLabel = ref)

    /** HEAD vs index, so later unstaged edits do not leak in. */
    private fun stagedChange(project: Project, c: GitChangeUtils.GitDiffChange): Change {
        val before = c.beforePath?.let { GitContentRevision.createRevision(it, GitRevisionNumber.HEAD, project) }
        // ":0" makes git4idea run `git cat-file :0:<path>`, the stage-0 index entry.
        val after = c.afterPath?.let { GitContentRevision.createRevision(it, GitRevisionNumber(INDEX_REV), project) }
        return Change(before, after)
    }

    private fun uncommitted(project: Project): List<Change> {
        val clm = ChangeListManager.getInstance(project)
        val tracked = clm.allChanges.toList()
        val unversioned = clm.unversionedFilesPaths
            .filter { !it.isDirectory }
            .map { Change(null, CurrentContentRevision(it)) }
        return tracked + unversioned
    }

    /** Full hash of HEAD, or null outside git. */
    fun headHash(project: Project): String? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentRevision

    fun currentBranch(project: Project): String? {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        return repo.currentBranchName ?: repo.currentRevision?.take(8)
    }

    /** Local branches first, then remote. Current branch excluded. */
    fun branchNames(project: Project): List<String> {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return emptyList()
        val current = repo.currentBranchName
        val local = repo.branches.localBranches.map { it.name }.filter { it != current }.sorted()
        val remote = repo.branches.remoteBranches.map { it.name }.sorted()
        return local + remote
    }

    /** `git merge-base ref HEAD`, or null when git cannot resolve it. */
    fun mergeBase(project: Project, ref: String): String? {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        return try {
            GitHistoryUtils.getMergeBase(project, repo.root, ref, "HEAD")?.rev
        } catch (e: Exception) {
            null
        }
    }

    /** Content of one side, null when absent or unreadable. */
    fun content(rev: ContentRevision?): CharSequence? = try {
        rev?.content
    } catch (e: Exception) {
        null
    }
}

object ReviewPaths {
    fun relative(project: Project, path: FilePath): String = relative(project, path.path)

    /** Relative to the project root, else to the VCS root, else absolute. Always `/` separated. */
    fun relative(project: Project, absolute: String): String {
        val abs = absolute.replace('\\', '/')
        relativeTo(project.basePath, abs)?.let { return it }
        val root = try {
            ProjectLevelVcsManager.getInstance(project).getVcsRootFor(VcsUtil.getFilePath(abs, java.io.File(abs).isDirectory))?.path
        } catch (e: Exception) {
            null
        }
        relativeTo(root, abs)?.let { return it }
        return abs
    }

    private fun relativeTo(base: String?, abs: String): String? {
        if (base.isNullOrEmpty()) return null
        val b = base.replace('\\', '/').trimEnd('/') + "/"
        return if (abs.startsWith(b)) abs.removePrefix(b) else null
    }

    fun relative(project: Project, change: Change): String = relative(project, ChangesUtil.getFilePath(change))

    /** Exact match, or one is a `/`-suffix of the other (tolerates differing roots). */
    fun matches(a: String, b: String): Boolean =
        a == b || a.endsWith("/$b") || b.endsWith("/$a")
}
