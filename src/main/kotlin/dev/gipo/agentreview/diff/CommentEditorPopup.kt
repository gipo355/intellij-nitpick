package dev.gipo.agentreview.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.EditorTextField
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gipo.agentreview.model.CommentType
import dev.gipo.agentreview.settings.AgentReviewSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Multi-line text field IdeaVim accepts. IdeaVim only handles editors from its allowlist, and one
 * entry is "a file named *Dummy.txt", the signal the platform commit message field uses.
 */
internal fun vimReadyTextField(project: Project, text: String): EditorTextField {
    val file = LightVirtualFile("Nitpick Dummy.txt", PlainTextFileType.INSTANCE, text)
    val document = FileDocumentManager.getInstance().getDocument(file) ?: EditorFactory.getInstance().createDocument(text)
    return EditorTextField(document, project, PlainTextFileType.INSTANCE, false, false).apply {
        addSettingsProvider { it.settings.isUseSoftWraps = true }
    }
}

/**
 * Comment editor: type chooser, text, Cancel / Save. Ctrl+Enter saves, Esc cancels, Alt+1..5 pick the type.
 * A new comment starts with the last type used.
 */
object CommentEditorPopup {

    fun show(project: Project, anchor: JComponent, type: CommentType, text: String, onSave: (String, CommentType) -> Unit) {
        build(project, type, text, onSave).showUnderneathOf(anchor)
    }

    fun showAtCaret(project: Project, editor: Editor, type: CommentType, text: String, onSave: (String, CommentType) -> Unit) {
        build(project, type, text, onSave).showInBestPositionFor(editor)
    }

    /** Reply that keeps the comment open: no type chooser. */
    fun showReply(project: Project, anchor: JComponent, onSave: (String) -> Unit) {
        build(project, CommentType.NOTE, "", { text, _ -> onSave(text) }, reply = true).showUnderneathOf(anchor)
    }

    private fun build(project: Project, type: CommentType, text: String, onSave: (String, CommentType) -> Unit, reply: Boolean = false): JBPopup {
        val area = vimReadyTextField(project, text).apply {
            setPlaceholder(if (reply) "Your reply…" else "What should the agent change here?")
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(130))
        }
        val settings = AgentReviewSettings.getInstance().state
        val typeBox = ComboBox(CommentType.entries.toTypedArray()).apply {
            selectedItem = if (text.isEmpty()) settings.lastCommentType else type
            renderer = listCellRenderer {
                val color = CommentColors.of(value)
                text(value.name.lowercase().replaceFirstChar { it.uppercase() }) { foreground = color }
            }
        }
        val header = JPanel(HorizontalLayout(8)).apply {
            isOpaque = false
            add(JBLabel("Type"))
            add(typeBox)
            isVisible = !reply
        }

        val cancel = JButton("Cancel")
        val save = JButton("Save").apply { putClientProperty("JButton.buttonType", "default") }
        val hint = JBLabel(if (reply) "Ctrl+Enter to send · Esc to cancel" else "Ctrl+Enter to save · Esc to cancel · Alt+1..5 type").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }
        val buttons = JPanel(HorizontalLayout(6)).apply {
            isOpaque = false
            add(cancel)
            add(save)
        }
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(hint, BorderLayout.WEST)
            add(buttons, BorderLayout.EAST)
        }
        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10, 12, 10, 12)
            add(header, BorderLayout.NORTH)
            add(area, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }

        lateinit var popup: JBPopup
        val commit = {
            val value = area.text.trim()
            if (value.isNotEmpty()) {
                val chosen = typeBox.selectedItem as CommentType
                if (!reply) settings.lastCommentType = chosen
                onSave(value, chosen)
                popup.closeOk(null)
            }
        }
        CommentType.entries.forEachIndexed { i, t ->
            object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) { typeBox.selectedItem = t }
            }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, KeyEvent.ALT_DOWN_MASK)), panel)
        }
        save.addActionListener { commit() }
        cancel.addActionListener { popup.cancel() }
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = commit()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)), area)

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, area)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnClickOutside(false)
            .setCancelKeyEnabled(true)
            .setTitle(if (reply) "Reply" else if (text.isEmpty()) "New Review Comment" else "Edit Review Comment")
            .createPopup()
        return popup
    }
}
