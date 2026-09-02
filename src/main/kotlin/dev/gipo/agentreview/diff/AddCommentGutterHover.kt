package dev.gipo.agentreview.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorGutterFreePainterAreaState
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent

/** GitHub-style "+" in the gutter of the hovered line. Click = add comment there. */
class AddCommentGutterHover(
    private val editor: EditorEx,
    parent: Disposable,
    private val onClick: (line: Int) -> Unit,
) : Disposable {

    private var highlighter: RangeHighlighter? = null
    private var hoveredLine = -1

    init {
        com.intellij.openapi.util.Disposer.register(parent, this)
        editor.gutterComponentEx.setLeftFreePaintersAreaState(EditorGutterFreePainterAreaState.SHOW)
        editor.gutterComponentEx.reserveLeftFreePaintersAreaWidth(this, ICON_WIDTH)
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
        h.lineMarkerRenderer = Renderer(line)
        highlighter = h
    }

    private inner class Renderer(private val line: Int) : LineMarkerRendererEx, ActiveGutterRenderer {
        override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT

        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            val icon = AllIcons.General.InlineAdd
            val x = r.x + (r.width - icon.iconWidth) / 2
            val y = r.y + (r.height - icon.iconHeight) / 2
            icon.paintIcon(editor.component, g, x, y)
        }

        override fun getTooltipText(): String = "Add review comment"
        override fun canDoAction(editor: Editor, e: MouseEvent): Boolean = true
        override fun doAction(editor: Editor, e: MouseEvent) {
            e.consume()
            onClick(line)
        }

        override fun calcBounds(editor: Editor, lineNum: Int, preferredBounds: Rectangle): Rectangle = preferredBounds
    }

    override fun dispose() {
        highlighter?.let { if (!editor.isDisposed) editor.markupModel.removeHighlighter(it) }
        highlighter = null
    }

    companion object {
        private val ICON_WIDTH = JBUI.scale(14)
    }
}
