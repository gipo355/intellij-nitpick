package dev.gipo.agentreview.scope

import com.intellij.openapi.application.ReadAction
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
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/** Resolves a [Scope] to the list of changes to review. Runs git, call off the EDT. */
object ScopeChanges {
    private const val INDEX_REV = ":0"

    fun collect(project: Project, scope: Scope): List<Change> {
        val all = GitRepositoryManager.getInstance(project).repositories
        // A named repo that is gone (imported session, folder moved) yields nothing rather than another repo's diff.
        val repos = if (scope.repo == null) all else listOfNotNull(repository(project, scope.repo))
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
            ScopeKind.BRANCH -> branchTree(project, scope.root ?: scope.repo)
        }.sortedBy { ChangesUtil.getFilePath(it).path }
    }

    /** [id] null: the first repository, as in a single-repo project. Else the one with that [ReviewPaths.repoId], or null. */
    fun repository(project: Project, id: String?): GitRepository? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (id == null) return repos.firstOrNull()
        return repos.firstOrNull { repoId(project, it) == id }
    }

    fun repoId(project: Project, repo: GitRepository): String = ReviewPaths.repoId(project.basePath, repo.root.path)

    /** Repo ids, sorted. Empty or one: the project is single-repo and scopes carry no repo. */
    fun repoIds(project: Project): List<String> =
        GitRepositoryManager.getInstance(project).repositories.map { repoId(project, it) }.sorted()

    /**
     * Every text file the project file index knows under [root] (project-relative, or null for all content),
     * as an "added" change whose content is the working file. No git call: excludes and libraries are the IDE's.
     */
    private fun branchTree(project: Project, root: String?): List<Change> {
        val index = ProjectFileIndex.getInstance(project)
        // Cancellable: a write action restarts the walk instead of waiting, so the list is built per attempt.
        val files = ReadAction.nonBlocking<List<VirtualFile>> {
            val found = ArrayList<VirtualFile>()
            val iterator = ContentIterator { vf ->
                if (!vf.isDirectory && !vf.fileType.isBinary && !index.isInLibrary(vf)) found += vf
                true
            }
            val dir = root?.let { rootDir(project, it) }
            if (root == null) {
                index.iterateContent(iterator)
            } else if (dir != null) {
                index.iterateContentUnderDirectory(dir, iterator)
            }
            found
        }.executeSynchronously()
        return files.map { Change(null, CurrentContentRevision(VcsUtil.getFilePath(it))) }
    }

    /** [relative] (trailing `/` optional) as a directory, see [ReviewPaths.candidates]. */
    fun rootDir(project: Project, relative: String): VirtualFile? = find(project, relative.trim('/'))?.takeIf { it.isDirectory }

    /** First existing file among [ReviewPaths.candidates]. */
    fun find(project: Project, relative: String): VirtualFile? {
        val roots = GitRepositoryManager.getInstance(project).repositories.map { it.root.path }
        for (path in ReviewPaths.candidates(project.basePath, roots, relative)) {
            LocalFileSystem.getInstance().findFileByPath(path)?.let { return it }
        }
        return null
    }

    /** `git stash list` of [repoId] as (ref, message), newest first. */
    fun stashes(project: Project, repoId: String?): List<Pair<String, String>> {
        val repo = repository(project, repoId) ?: return emptyList()
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
    fun headHash(project: Project, repoId: String?): String? = repository(project, repoId)?.currentRevision

    /** Checked-out branch; null on a detached HEAD or outside git. */
    fun currentBranchName(project: Project, repoId: String?): String? = repository(project, repoId)?.currentBranchName

    fun currentBranch(project: Project, repoId: String?): String? {
        val repo = repository(project, repoId) ?: return null
        return repo.currentBranchName ?: repo.currentRevision?.take(8)
    }

    /** Local branches first, then remote. Current branch excluded. */
    fun branchNames(project: Project, repoId: String?): List<String> {
        val repo = repository(project, repoId) ?: return emptyList()
        val current = repo.currentBranchName
        val local = repo.branches.localBranches.map { it.name }.filter { it != current }.sorted()
        val remote = repo.branches.remoteBranches.map { it.name }.sorted()
        return local + remote
    }

    /** `git merge-base ref HEAD`, or null when git cannot resolve it. */
    fun mergeBase(project: Project, repoId: String?, ref: String): String? {
        val repo = repository(project, repoId) ?: return null
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

    /**
     * Under the project base: relative to it. Else relative to its VCS root, prefixed with the `repoId` when the
     * project has several repos. Else absolute. Always `/` separated.
     */
    fun relative(project: Project, absolute: String): String {
        val abs = absolute.replace('\\', '/')
        relativeTo(project.basePath, abs)?.let { return it }
        val root = try {
            ProjectLevelVcsManager.getInstance(project).getVcsRootFor(VcsUtil.getFilePath(abs, java.io.File(abs).isDirectory))?.path
        } catch (e: Exception) {
            null
        }
        // Single repo: paths stay as before this field existed, so old marks keep their keys.
        if (root != null && GitRepositoryManager.getInstance(project).repositories.size <= 1) relativeTo(root, abs)?.let { return it }
        return relative(project.basePath, root, abs)
    }

    fun relative(base: String?, root: String?, abs: String): String {
        relativeTo(base, abs)?.let { return it }
        if (root != null) relativeTo(root, abs)?.let { return repoId(base, root) + "/" + it }
        return abs
    }

    /**
     * Stable name of a git root: its path under the project base, else its folder name. The prefix of every path
     * in that repo when the repo lies outside the base, and the value of [Scope.repo].
     */
    fun repoId(base: String?, root: String): String {
        val r = root.replace('\\', '/').trimEnd('/')
        return relativeTo(base, r) ?: r.substringAfterLast('/')
    }

    /** Where a relative path may live: under the base, under the repo it names, then under every repo (pre-repoId paths). */
    fun candidates(base: String?, roots: List<String>, rel: String): List<String> {
        val out = ArrayList<String>()
        if (!base.isNullOrEmpty()) out += "$base/$rel"
        for (root in roots) {
            val id = repoId(base, root)
            if (rel == id) out += root
            else if (rel.startsWith("$id/")) out += "$root/" + rel.removePrefix("$id/")
        }
        for (root in roots) out += "$root/$rel"
        return out
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
