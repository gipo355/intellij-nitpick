package dev.gipo.agentreview.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import javax.swing.Icon

/** GitHub-style "+" gutter icon on the hovered line. Click = add comment there. */
class AddCommentGutterHover(
    private val editor: EditorEx,
    parent: Disposable,
    private val onClick: (line: Int) -> Unit,
) : Disposable {

    private var highlighter: RangeHighlighter? = null
    private var hoveredLine = -1

    init {
        Disposer.register(parent, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val line = if (e.area == EditorMouseEventArea.EDITING_AREA || isGutter(e.area)) e.logicalPosition.line else -1
                setHovered(line)
            }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(e: EditorMouseEvent) = setHovered(-1)
        }, this)
    }

    private fun isGutter(area: EditorMouseEventArea?): Boolean =
        area == EditorMouseEventArea.LINE_MARKERS_AREA || area == EditorMouseEventArea.LINE_NUMBERS_AREA ||
            area == EditorMouseEventArea.ANNOTATIONS_AREA || area == EditorMouseEventArea.FOLDING_OUTLINE_AREA

    private fun setHovered(line: Int) {
        if (line == hoveredLine) return
        hoveredLine = line
        highlighter?.let { editor.markupModel.removeHighlighter(it) }
        highlighter = null
        val doc = editor.document
        if (line < 0 || line >= doc.lineCount || editor.isDisposed) return
        val h = editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(line), doc.getLineEndOffset(line),
            HighlighterLayer.LAST, null, HighlighterTargetArea.LINES_IN_RANGE,
        )
        h.gutterIconRenderer = PlusIcon(line)
        highlighter = h
    }

    private inner class PlusIcon(private val line: Int) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.InlineAdd
        override fun getTooltipText(): String = "Add review comment"
        override fun isNavigateAction(): Boolean = true
        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = onClick(line)
        }

        override fun equals(other: Any?): Boolean = other is PlusIcon && other.line == line
        override fun hashCode(): Int = line
    }

    override fun dispose() {
        highlighter?.let { if (!editor.isDisposed) editor.markupModel.removeHighlighter(it) }
        highlighter = null
    }
}
