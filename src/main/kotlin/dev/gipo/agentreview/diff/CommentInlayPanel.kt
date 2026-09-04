package dev.gipo.agentreview.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gipo.agentreview.model.Author
import dev.gipo.agentreview.model.ThreadEntry
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.store.ReviewStore
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.text.DateFormat
import java.util.Date
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Comment card rendered under the commented line: accent bar, header, body, actions. */
class CommentInlayPanel(project: Project, private val comment: Comment, onChanged: () -> Unit) : JPanel(BorderLayout()) {

    private val accent: Color = if (comment.resolved) CommentColors.resolved else CommentColors.of(comment.type)

    init {
        val store = ReviewStore.getInstance(project)
        isOpaque = false
        border = JBUI.Borders.empty(4, 2, 6, 8)

        val card = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 14, 8, 12)
        }

        val header = JPanel(HorizontalLayout(10)).apply { isOpaque = false }
        val who = if (comment.author == Author.AGENT) "Agent" else "You"
        val whoIcon = if (comment.author == Author.AGENT) AllIcons.Nodes.Plugin else AllIcons.General.User
        header.add(JBLabel(who, whoIcon, SwingConstants.LEFT).apply { font = JBUI.Fonts.label().asBold() })
        if (comment.type.marker.isNotEmpty()) {
            header.add(JBLabel(comment.type.marker).apply {
                foreground = CommentColors.of(comment.type)
                font = JBUI.Fonts.smallFont().asBold()
            })
        }
        if (comment.resolved) {
            header.add(JBLabel(if (comment.wontFix) "Won't fix" else "Resolved", AllIcons.RunConfigurations.TestPassed, SwingConstants.LEFT).apply {
                foreground = CommentColors.resolved
                font = JBUI.Fonts.smallFont()
            })
        }
        header.add(JBLabel(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(comment.createdAt))).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        })
        card.add(header, BorderLayout.NORTH)

        val body = JPanel(BorderLayout(0, 4)).apply { isOpaque = false }
        body.add(JBLabel("<html><div style='width:${JBUI.scale(520)}px'>${escape(comment.text).replace("\n", "<br>")}</div></html>"), BorderLayout.CENTER)
        val replies = comment.thread.map { (if (it.author == Author.AGENT) "Agent: " else "You: ") + it.text } +
            listOfNotNull(comment.reply?.takeIf { r -> r.isNotBlank() }?.let { "Agent: $it" })
        if (replies.isNotEmpty()) {
            body.add(JBLabel("<html><div style='width:${JBUI.scale(520)}px'><i>${replies.joinToString("<br>") { escape(it) }}</i></div></html>").apply {
                foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.SOUTH)
        }
        card.add(body, BorderLayout.CENTER)

        val actions = JPanel(HorizontalLayout(14)).apply { isOpaque = false }
        actions.add(link("Edit") {
            CommentEditorPopup.show(project, this, comment.type, comment.text) { text, type ->
                store.updateComment(comment.id) { it.copy(text = text, type = type) }
            }
        })
        actions.add(link("Reply") {
            CommentEditorPopup.showReply(project, this) { text ->
                store.updateComment(comment.id) { it.copy(thread = it.thread + ThreadEntry(Author.USER, text)) }
            }
        })
        actions.add(link(if (comment.resolved) "Reopen" else "Resolve") {
            store.updateComment(comment.id) { it.copy(resolved = !it.resolved, wontFix = false) }
            onChanged()
        })
        actions.add(link("Delete") { store.removeComment(comment.id) })
        card.add(actions, BorderLayout.SOUTH)

        add(card, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val insets = insets
            val x = insets.left
            val y = insets.top
            val w = width - insets.left - insets.right
            val h = height - insets.top - insets.bottom
            val arc = JBUI.scale(10)
            g2.color = CARD_BG
            g2.fillRoundRect(x, y, w, h, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
            g2.color = accent
            g2.fillRoundRect(x, y, JBUI.scale(4), h, JBUI.scale(4), JBUI.scale(4))
        } finally {
            g2.dispose()
        }
    }

    private fun link(text: String, action: () -> Unit): ActionLink = ActionLink(text) { action() }.apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private val CARD_BG = JBColor(0xFFFFFF, 0x2B2D30)
    }
}
