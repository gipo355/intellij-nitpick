package dev.gipo.agentreview.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class ScopeKind(val label: String) {
    UNCOMMITTED("Uncommitted changes"),
    STAGED("Staged changes"),
    UNSTAGED("Unstaged changes"),
    RANGE("Commit range"),
    COMMIT("Single commit"),
}

@Serializable
data class Scope(
    val kind: ScopeKind = ScopeKind.UNCOMMITTED,
    /** Base ref for RANGE. */
    val base: String? = null,
    /** Head ref for RANGE, commit hash for COMMIT. */
    val head: String? = null,
) {
    fun describe(): String = when (kind) {
        ScopeKind.UNCOMMITTED -> "uncommitted changes"
        ScopeKind.STAGED -> "staged changes"
        ScopeKind.UNSTAGED -> "unstaged changes"
        ScopeKind.RANGE -> "commits ${base}..${head ?: "HEAD"}"
        ScopeKind.COMMIT -> "commit ${head?.take(8)}"
    }
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

/**
 * Lines are 1-based. Null lines = file-level. Empty path = review-level.
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
) {
    val isFileLevel: Boolean get() = path.isNotEmpty() && startLine == null
    val isReviewLevel: Boolean get() = path.isEmpty()

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
    val comments: List<Comment> = emptyList(),
    /** path -> content hash at the time of marking. */
    val reviewed: Map<String, String> = emptyMap(),
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun commentsFor(path: String): List<Comment> = comments.filter { it.path == path }

    fun reviewState(path: String, currentHash: String?): ReviewState {
        val stored = reviewed[path] ?: return ReviewState.UNREVIEWED
        return if (currentHash == null || stored == currentHash) ReviewState.REVIEWED else ReviewState.STALE
    }
}

enum class ReviewState { UNREVIEWED, REVIEWED, STALE }

/** Review-level first (empty path), then path, then line. */
val commentOrder: Comparator<Comment> = compareBy<Comment> { it.path }
    .thenBy { it.startLine ?: 0 }
    .thenBy { it.endLine ?: 0 }
    .thenBy { it.createdAt }
