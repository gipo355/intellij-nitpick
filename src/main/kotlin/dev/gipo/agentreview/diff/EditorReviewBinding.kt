package dev.gipo.agentreview.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ContentHash
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.scope.ChangesListener
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.scope.ReviewedChange
import dev.gipo.agentreview.settings.AgentReviewSettings
import dev.gipo.agentreview.store.ReviewListener
import dev.gipo.agentreview.store.ReviewStore
import javax.swing.Icon

/** Maps a comment's (side, 1-based line) to a 0-based editor line and back. */
interface LineMapper {
    fun toEditor(side: Side, line: Int): Int?
    fun fromEditor(editorLine: Int): kotlin.Pair<Side, Int>?
}

/** Identity mapper for a two-side or one-side editor bound to a single [Side]. */
class SingleSideMapper(private val side: Side) : LineMapper {
    override fun toEditor(side: Side, line: Int): Int? = if (side == this.side) line - 1 else null
    override fun fromEditor(editorLine: Int): kotlin.Pair<Side, Int> = side to editorLine + 1
}

/** One per diff editor. Renders comments and handles creation. */
class EditorReviewBinding(
    val project: Project,
    val editor: EditorEx,
    val path: String,
    val mapper: LineMapper,
    val primarySide: Side,
    parent: Disposable,
) : Disposable {

    private val store = ReviewStore.getInstance(project)
    private val inlays = mutableListOf<Inlay<*>>()
    private val highlighters = mutableListOf<RangeHighlighter>()

    /** What the last render drew, and on which document stamp; the same again skips the inlay rebuild. */
    private var rendered: List<Comment>? = null
    private var renderedStamp = -1L

    init {
        editor.putUserData(KEY, this)
        Disposer.register(parent, this)
        val bus = project.messageBus.connect(this)
        bus.subscribe(ReviewListener.TOPIC, object : ReviewListener {
            override fun sessionChanged(session: ReviewSession) = render()
        })
        bus.subscribe(ChangesListener.TOPIC, object : ChangesListener {
            override fun changesUpdated(changes: List<ReviewedChange>) = render()
            override fun hashesChanged() = render()
        })
        render(force = true)
        AddCommentGutterHover(editor, this) { line -> addCommentAt(line) }
    }

    /** Opens the comment editor for a 0-based editor line (gutter "+" click). */
    fun addCommentAt(editorLine: Int) {
        val (side, line) = mapper.fromEditor(editorLine) ?: return
        val doc = editor.document
        val snippet = if (editorLine < doc.lineCount) doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(editorLine), doc.getLineEndOffset(editorLine))) else ""
        editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(editorLine, 0))
        CommentEditorPopup.showAtCaret(project, editor, dev.gipo.agentreview.model.CommentType.NOTE, "") { text, type ->
            store.addComment(Comment(path = path, side = side, startLine = line, endLine = line, type = type, text = text, snippet = snippet, contentHash = contentHash(side)))
        }
    }

    /**
     * Hash of the side's file as the scope model sees it, else of this editor's document. When the model
     * tracks the working file (branch mode) the document wins: it is what the user is looking at.
     */
    fun contentHash(side: Side): String? {
        val rc = ReviewChangesModel.getInstance(project).find(path)
        return when {
            rc != null && !(rc.tracksWorkingFile && side == primarySide) -> if (side == Side.NEW) rc.hash else rc.beforeHash
            side == primarySide -> ContentHash.of(editor.document.charsSequence)
            else -> null
        }
    }

    /** Redraws comments. Without [force], nothing happens when neither the placed comments nor the document changed. */
    fun render(force: Boolean = false) {
        if (editor.isDisposed) return
        consumePendingScroll()
        if (!annotationsEnabled) {
            clear()
            rendered = null
            return
        }
        val comments = ReviewChangesModel.getInstance(project).commentsFor(path)
        // A reload from disk (agent edit, checkout) moves the inlays' markers: a new stamp means redraw.
        val stamp = editor.document.modificationStamp
        if (!force && comments == rendered && stamp == renderedStamp) return
        rendered = comments
        renderedStamp = stamp
        clear()
        val doc = editor.document
        for (c in comments) {
            if (c.outdated) continue
            val startLine = c.startLine
            if (startLine == null) {
                // File-level card above line 1, on the NEW side only.
                if (primarySide == Side.NEW) addInlay(0, c)
                continue
            }
            val start = mapper.toEditor(c.side, startLine) ?: continue
            val end = mapper.toEditor(c.side, c.endLine ?: startLine) ?: start
            if (start >= doc.lineCount) continue
            val endClamped = end.coerceIn(start, doc.lineCount - 1)
            highlight(start, endClamped, c)
            addInlay(doc.getLineEndOffset(endClamped), c)
        }
    }

    private fun addInlay(offset: Int, c: Comment) {
        val panel = CommentInlayPanel(project, c) { rerender() }
        val props = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(), null, true, false, 0, offset,
        )
        EditorEmbeddedComponentManager.getInstance().addComponent(editor, panel, props)?.let { inlays += it }
    }

    private fun rerender() = render(force = true)

    private fun consumePendingScroll() {
        val model = dev.gipo.agentreview.scope.ReviewChangesModel.getInstance(project)
        val pending = model.pendingScroll ?: return
        if (!dev.gipo.agentreview.scope.ReviewPaths.matches(pending.path, path)) return
        val line = mapper.toEditor(pending.side, pending.line) ?: return
        if (line >= editor.document.lineCount) return
        model.pendingScroll = null
        val pos = com.intellij.openapi.editor.LogicalPosition(line, 0)
        editor.caretModel.moveToLogicalPosition(pos)
        editor.scrollingModel.scrollTo(pos, com.intellij.openapi.editor.ScrollType.CENTER)
    }

    private fun highlight(start: Int, end: Int, c: Comment) {
        val doc = editor.document
        val attrs = TextAttributes().apply {
            backgroundColor = if (c.resolved) RESOLVED_BG else COMMENT_BG
            effectType = EffectType.BOXED
        }
        val h = editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(start), doc.getLineEndOffset(end),
            HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.LINES_IN_RANGE,
        )
        h.gutterIconRenderer = CommentGutterIcon(c)
        highlighters += h
    }

    private fun clear() {
        inlays.forEach { Disposer.dispose(it) }
        inlays.clear()
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
    }

    /** Caret line (or selection) as comment coordinates. */
    fun selectionRange(): Triple<Side, Int, Int>? {
        val sel = editor.selectionModel
        val doc = editor.document
        val startLine: Int
        val endLine: Int
        if (sel.hasSelection()) {
            startLine = doc.getLineNumber(sel.selectionStart)
            val endOffset = sel.selectionEnd
            val rawEnd = doc.getLineNumber(endOffset)
            endLine = if (rawEnd > startLine && doc.getLineStartOffset(rawEnd) == endOffset) rawEnd - 1 else rawEnd
        } else {
            startLine = editor.caretModel.logicalPosition.line
            endLine = startLine
        }
        val (side, s) = mapper.fromEditor(startLine) ?: return null
        val (_, e) = mapper.fromEditor(endLine) ?: return null
        return Triple(side, s, e)
    }

    fun selectedText(): String {
        val sel = editor.selectionModel
        if (sel.hasSelection()) return sel.selectedText ?: ""
        val doc = editor.document
        val line = editor.caretModel.logicalPosition.line
        return doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)))
    }

    override fun dispose() {
        clear()
        editor.putUserData(KEY, null)
    }

    private class CommentGutterIcon(private val comment: Comment) : GutterIconRenderer() {
        override fun getIcon(): Icon = if (comment.resolved) AllIcons.RunConfigurations.TestPassed else AllIcons.Toolwindows.ToolWindowMessages
        override fun getTooltipText(): String = comment.text
        override fun equals(other: Any?): Boolean = other is CommentGutterIcon && other.comment.id == comment.id
        override fun hashCode(): Int = comment.id.hashCode()
    }

    companion object {
        val KEY: Key<EditorReviewBinding> = Key.create("AgentReview.EditorBinding")

        /** Global switch for everything a binding draws or offers: cards, gutter "+", toolbar and popup entries. */
        val annotationsEnabled: Boolean get() = AgentReviewSettings.getInstance().state.editorAnnotations

        fun setAnnotationsEnabled(enabled: Boolean) {
            AgentReviewSettings.getInstance().state.editorAnnotations = enabled
            // Branch mode: off drops the editor bindings altogether, on rebinds. Diff viewers keep theirs and redraw.
            ProjectManager.getInstance().openProjects.forEach { if (!it.isDisposed) BranchEditorBinder.getInstance(it).sync() }
            EditorFactory.getInstance().allEditors.forEach { it.getUserData(KEY)?.render(force = true) }
        }
        private val COMMENT_BG = JBColor(0xFFF4D6, 0x4A4429)
        private val RESOLVED_BG = JBColor(0xE6F4E6, 0x2F3F2F)
    }
}
