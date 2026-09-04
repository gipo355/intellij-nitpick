package dev.gipo.agentreview

import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ContentHash
import dev.gipo.agentreview.scope.CommentPlacer
import dev.gipo.agentreview.scope.ReviewedChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPlacerTest {
    private val content = "a\nb\nc\nd\n"

    @Test
    fun relocateFindsUniqueSnippet() {
        assertEquals(2 to 2, CommentPlacer.relocate("b", "a\nb\nc\n"))
        assertEquals(3 to 4, CommentPlacer.relocate("c\nd", "x\nx\nc\nd\n"))
        assertNull(CommentPlacer.relocate("x", "x\nx\n"))
        assertNull(CommentPlacer.relocate("zzz", content))
        assertNull(CommentPlacer.relocate("  ", content))
    }

    @Test
    fun folderCommentShowsWhenAChangeLiesUnderIt() {
        val inside = Comment(path = "src/app/", text = "f")
        val outside = Comment(path = "docs/", text = "g")
        val changes = listOf(ReviewedChange(path = "src/app/a.kt", hash = "h", beforeHash = null, content = null, beforeContent = null))
        val placed = CommentPlacer.place(listOf(inside, outside), changes, currentKey = "k")
        assertEquals(listOf(inside.id), placed.map { it.id })
        assertFalse(placed.single().outdated)
        assertEquals("src/app/", inside.location())
        assertTrue(inside.isFolderLevel && inside.isFileLevel)
    }

    @Test
    fun placeKeepsSameHashRelocatesOrMarksOutdated() {
        val h = ContentHash.of(content)
        val same = Comment(path = "a.kt", startLine = 2, endLine = 2, contentHash = h, snippet = "b")
        val moved = Comment(path = "a.kt", startLine = 9, endLine = 9, contentHash = "old", snippet = "c")
        val gone = Comment(path = "a.kt", startLine = 1, endLine = 1, contentHash = "old", snippet = "zzz")
        val elsewhere = Comment(path = "other.kt", startLine = 1, contentHash = "old", snippet = "a")
        val review = Comment(path = "", text = "n", scopeKey = "k")
        val foreignReview = Comment(path = "", text = "n", scopeKey = "other")
        val placed = CommentPlacer.place(
            listOf(same, moved, gone, elsewhere, review, foreignReview),
            listOf(ReviewedChange(path = "a.kt", hash = h, beforeHash = null, content = content, beforeContent = null)),
            currentKey = "k",
        )
        assertEquals(listOf(same.id, moved.id, gone.id, review.id), placed.map { it.id })
        assertEquals(2, placed[0].startLine)
        assertEquals(3, placed[1].startLine)
        assertFalse(placed[1].outdated)
        assertTrue(placed[2].outdated)
        assertEquals(1, placed[2].startLine)
    }
}
