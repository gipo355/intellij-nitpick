package dev.gipo.agentreview.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gipo.agentreview.model.Author
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.CommentType
import dev.gipo.agentreview.store.ReviewStore
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Read-only comment card rendered under the commented line. */
class CommentInlayPanel(project: Project, comment: Comment, onChanged: () -> Unit) : JPanel(BorderLayout()) {

    init {
        val store = ReviewStore.getInstance(project)
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(4, 8),
        )
        background = if (comment.resolved) RESOLVED_BG else UIUtil.getPanelBackground()

        val header = JPanel(HorizontalLayout(8)).apply { isOpaque = false }
        val badge = badgeText(comment)
        if (badge.isNotEmpty()) header.add(JBLabel(badge).apply { foreground = badgeColor(comment.type) })
        header.add(JBLabel(if (comment.author == Author.AGENT) "agent" else "you").apply { foreground = UIUtil.getContextHelpForeground() })
        if (comment.resolved) header.add(JBLabel("resolved", AllIcons.RunConfigurations.TestPassed, SwingConstants.LEFT))
        add(header, BorderLayout.NORTH)

        val body = JBLabel("<html>${escape(comment.text).replace("\n", "<br>")}</html>").apply {
            border = JBUI.Borders.emptyTop(2)
        }
        val center = JPanel(BorderLayout()).apply { isOpaque = false; add(body, BorderLayout.CENTER) }
        comment.reply?.takeIf { it.isNotBlank() }?.let {
            center.add(JBLabel("<html><i>agent: ${escape(it)}</i></html>").apply {
                border = JBUI.Borders.emptyTop(4)
            }, BorderLayout.SOUTH)
        }
        add(center, BorderLayout.CENTER)

        val actions = JPanel(HorizontalLayout(4)).apply { isOpaque = false }
        actions.add(link("Edit") {
            CommentEditorPopup.show(project, this, comment.type, comment.text) { text, type ->
                store.updateComment(comment.id) { it.copy(text = text, type = type) }
            }
        })
        actions.add(link(if (comment.resolved) "Reopen" else "Resolve") {
            store.updateComment(comment.id) { it.copy(resolved = !it.resolved) }
            onChanged()
        })
        actions.add(link("Delete") { store.removeComment(comment.id) })
        add(actions, BorderLayout.SOUTH)
    }

    private fun link(text: String, action: () -> Unit): JButton = JButton(text).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        putClientProperty("JButton.buttonType", "borderless")
        foreground = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
        addActionListener { action() }
    }

    private fun badgeText(c: Comment): String = if (c.type.marker.isEmpty()) "" else "[${c.type.marker}]"

    private fun badgeColor(type: CommentType): java.awt.Color = when (type) {
        CommentType.ISSUE -> JBColor.RED
        CommentType.QUESTION -> JBColor.BLUE
        CommentType.NIT -> JBColor.GRAY
        CommentType.PRAISE -> JBColor(0x2E7D32, 0x81C784)
        CommentType.NOTE -> JBColor.foreground()
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private val RESOLVED_BG = JBColor(0xEEF7EE, 0x2C382C)
    }
}
