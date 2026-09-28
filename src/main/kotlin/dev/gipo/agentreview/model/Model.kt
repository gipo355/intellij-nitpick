package dev.gipo.agentreview.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

enum class ScopeKind(val label: String) {
    UNCOMMITTED("Uncommitted changes"),
    STAGED("Staged changes"),
    UNSTAGED("Unstaged changes"),
    RANGE("Commit range"),
    COMMIT("Single commit"),
    /** The checked-out tree with no diff: every file under [Scope.root] (or the project) at its current content. */
    BRANCH("Current branch"),
    /** One contiguous range per repo, see [Scope.ranges]. */
    WORKSPACE("Workspace"),
    ;

    /** Working-tree diffs follow the changelist manager; ranges, commits and the branch tree refresh on demand. */
    val followsChangeList: Boolean get() = this == UNCOMMITTED || this == STAGED || this == UNSTAGED || this == WORKSPACE
}

/** One repo's part of a WORKSPACE scope. Refs are pinned hashes; the `*Ref` fields keep what the user picked. */
@Serializable
data class RepoRange(
    /** `ReviewPaths.repoId`. */
    val repo: String,
    val base: String,
    /** Null: the working tree. */
    val head: String? = null,
    val baseRef: String? = null,
    val headRef: String? = null,
) {
    val label: String get() = (baseRef ?: shortRef(base)) + ".." + (headRef ?: head?.let(::shortRef) ?: "working tree")
}

/** Hashes to 8 chars, refs untouched. */
private fun shortRef(ref: String): String = if (ref.length >= 8 && ref.all { it.isDigit() || it in 'a'..'f' }) ref.take(8) else ref

@Serializable
data class Scope(
    val kind: ScopeKind = ScopeKind.UNCOMMITTED,
    /** Base ref for RANGE. */
    val base: String? = null,
    /** Head ref for RANGE, commit hash for COMMIT, branch name for BRANCH. */
    val head: String? = null,
    /** Human label for [base] when it is a resolved hash (e.g. `merge-base(main)`). */
    val baseLabel: String? = null,
    /** BRANCH only: project-relative folder (trailing `/`) that limits the tree. Null is the whole project. */
    val root: String? = null,
    /**
     * RANGE, COMMIT, BRANCH in a multi-repo project: id of the git root the refs live in (see `ReviewPaths.repoId`).
     * Null in single-repo projects, and always for working-tree scopes, which span every repo.
     */
    val repo: String? = null,
    /** WORKSPACE only, one per repo, sorted by repo. */
    val ranges: List<RepoRange> = emptyList(),
) {
    /** Session key. Working-tree scopes share one session per kind; ranges, commits and branches get their own. */
    fun key(): String = when (kind) {
        ScopeKind.RANGE -> "range:${base}..${head ?: "HEAD"}" + repoSuffix("|")
        ScopeKind.COMMIT -> "commit:$head" + repoSuffix("|")
        ScopeKind.BRANCH -> "branch:${head ?: "HEAD"}" + (root?.let { "@$it" } ?: "") + repoSuffix("|")
        // Ranges stay out of the key: editing them keeps the session, its marks and comments.
        else -> kind.name.lowercase()
    }

    private fun repoSuffix(sep: String): String = repo?.let { "$sep$it" } ?: ""

    /** Toolbar text: the concrete range or commit, not the kind. */
    fun shortLabel(): String = when (kind) {
        ScopeKind.UNCOMMITTED -> "Uncommitted"
        ScopeKind.STAGED -> "Staged"
        ScopeKind.UNSTAGED -> "Unstaged"
        ScopeKind.RANGE -> {
            val mergeBaseRef = baseLabel?.removeSurrounding("merge-base(", ")")?.takeIf { it != baseLabel }
            if (mergeBaseRef != null) "$mergeBaseRef...${head ?: "HEAD"}" else "${short(base)}..${short(head ?: "HEAD")}"
        }
        ScopeKind.COMMIT -> "commit ${short(head)}"
        ScopeKind.BRANCH -> root?.let { "$it on ${head ?: "HEAD"}" } ?: "Branch ${head ?: "HEAD"}"
        ScopeKind.WORKSPACE -> "Workspace · ${ranges.size} repo" + (if (ranges.size == 1) "" else "s")
    } + (if (kind.followsChangeList) "" else repo?.let { " [$it]" } ?: "")

    private fun short(ref: String?): String = ref?.let(::shortRef) ?: "?"

    /** Adds [range], replacing the range of the same repo. */
    fun withRange(range: RepoRange): Scope = copy(ranges = (ranges.filterNot { it.repo == range.repo } + range).sortedBy { it.repo })

    fun describe(): String = when (kind) {
        ScopeKind.UNCOMMITTED -> "uncommitted changes"
        ScopeKind.STAGED -> "staged changes"
        ScopeKind.UNSTAGED -> "unstaged changes"
        ScopeKind.RANGE -> "commits ${baseLabel ?: base?.take(8)}..${head?.take(8) ?: "HEAD"}"
        ScopeKind.COMMIT -> "commit ${head?.take(8)}"
        ScopeKind.BRANCH -> (root?.let { "$it on " } ?: "") + "branch ${head ?: "HEAD"} (whole tree, no diff)"
        ScopeKind.WORKSPACE -> if (ranges.isEmpty()) "workspace (empty)" else "workspace: " + ranges.joinToString { "${it.repo} ${it.label}" }
    } + (if (kind.followsChangeList) "" else repo?.let { " in $it" } ?: "")
}

enum class Side { OLD, NEW }

enum class CommentType(val marker: String) {
    NOTE(""),
    ISSUE("ISSUE"),
    QUESTION("QUESTION"),
    NIT("NIT"),
    PRAISE("PRAISE"),
}

enum class Author { USER, AGENT }

/** One message in a comment's conversation. */
@Serializable
data class ThreadEntry(
    val author: Author = Author.AGENT,
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Lines are 1-based. Null lines = file-level. Empty path = review-level. Path ending in `/` = folder-level.
 */
@Serializable
data class Comment(
    val id: String = UUID.randomUUID().toString(),
    val path: String = "",
    val side: Side = Side.NEW,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val type: CommentType = CommentType.NOTE,
    val text: String = "",
    val snippet: String? = null,
    val author: Author = Author.USER,
    val createdAt: Long = System.currentTimeMillis(),
    val resolved: Boolean = false,
    /** Agent reply set when it resolves the comment. */
    val reply: String? = null,
    /** Resolved as "won't fix": the agent pushed back instead of changing code. */
    val wontFix: Boolean = false,
    /** Replies that leave the comment open, in order. */
    val thread: List<ThreadEntry> = emptyList(),
    /** Hash of the commented side's file content at creation. Null: never relocated. */
    val contentHash: String? = null,
    /** Key of the owning session. Wire name kept so pre-0.5 data loads. */
    @SerialName("scopeKey")
    val sessionKey: String = "",
    /** Runtime only: the commented text is gone from the file in the current scope. */
    val outdated: Boolean = false,
) {
    val hasAgentReply: Boolean get() = !reply.isNullOrBlank() || thread.any { it.author == Author.AGENT }
    /** Creation or latest thread message, for "what changed since" polling. */
    val lastActivity: Long get() = maxOf(createdAt, thread.maxOfOrNull { it.createdAt } ?: 0L)
    val isFileLevel: Boolean get() = path.isNotEmpty() && startLine == null
    val isReviewLevel: Boolean get() = path.isEmpty()
    /** Path ends with `/`: the comment is about a directory. */
    val isFolderLevel: Boolean get() = path.endsWith("/")

    /** `path:42`, `path:5-7`, `path:~12` (old side), `path`, or `review`. */
    fun location(): String {
        if (isReviewLevel) return "review"
        val start = startLine ?: return path
        val end = endLine ?: start
        val tilde = if (side == Side.OLD) "~" else ""
        return if (end > start) "$path:$tilde$start-$tilde$end" else "$path:$tilde$start"
    }
}

@Serializable
data class ReviewSession(
    val scope: Scope = Scope(),
    /** Nth review of the same scope. "New Session" bumps it; the older ones stay as superseded rounds. */
    val generation: Int = 1,
    /** Pre-0.2.1. Moved to [ReviewStorage.comments] on load. */
    val comments: List<Comment> = emptyList(),
    /** path -> content hash at the time of marking. */
    val reviewed: Map<String, String> = emptyMap(),
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean get() = reviewed.isEmpty() && notes.isBlank()

    /** Scope key, `#n` appended past the first generation. */
    val key: String get() = scope.key() + (if (generation > 1) "#$generation" else "")

    /** ` #n` for display, empty for the first generation. */
    val generationLabel: String get() = if (generation > 1) " #$generation" else ""

    fun reviewState(path: String, currentHash: String?): ReviewState {
        val stored = reviewed[path] ?: return ReviewState.UNREVIEWED
        if (stored.isEmpty() || currentHash == null || stored == currentHash) return ReviewState.REVIEWED
        return ReviewState.STALE
    }
}

enum class ReviewState { UNREVIEWED, REVIEWED, STALE }

/** Review-level first (empty path), then path, then line. */
val commentOrder: Comparator<Comment> = compareBy<Comment> { it.path }
    .thenBy { it.startLine ?: 0 }
    .thenBy { it.endLine ?: 0 }
    .thenBy { it.createdAt }

/**
 * All sessions of a project, keyed by [ReviewSession.key]. Comments belong to a session; the live ones (newest
 * generation of each scope) share them across scopes, superseded ones keep theirs to themselves.
 */
@Serializable
data class ReviewStorage(
    val sessions: Map<String, ReviewSession> = emptyMap(),
    val currentKey: String = Scope().key(),
    val comments: List<Comment> = emptyList(),
)
