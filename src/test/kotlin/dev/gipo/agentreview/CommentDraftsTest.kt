package dev.gipo.agentreview

import dev.gipo.agentreview.diff.CommentDrafts
import dev.gipo.agentreview.model.CommentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentDraftsTest {

    @Test
    fun keepsUnsavedText() {
        val drafts = CommentDrafts()
        drafts.put("k", "half written", CommentType.ISSUE, original = "")
        assertEquals(CommentDrafts.Draft("half written", CommentType.ISSUE), drafts.get("k"))
        drafts.clear("k")
        assertNull(drafts.get("k"))
    }

    @Test
    fun blankOrUnchangedTextIsNoDraft() {
        val drafts = CommentDrafts()
        drafts.put("k", "x", CommentType.NOTE, original = "")
        drafts.put("k", "  ", CommentType.NOTE, original = "")
        assertNull(drafts.get("k"))
        drafts.put("k", "same", CommentType.NOTE, original = "same")
        assertNull(drafts.get("k"))
    }
}
