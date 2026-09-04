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
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gipo.agentreview.model.CommentType
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Comment editor: type chooser, text, Cancel / Save. Ctrl+Enter saves, Esc cancels. */
object CommentEditorPopup {

    fun show(project: Project, anchor: JComponent, type: CommentType, text: String, onSave: (String, CommentType) -> Unit) {
        build(project, type, text, onSave).showUnderneathOf(anchor)
    }

    fun showAtCaret(project: Project, editor: Editor, type: CommentType, text: String, onSave: (String, CommentType) -> Unit) {
        build(project, type, text, onSave).showInBestPositionFor(editor)
    }

    private fun build(project: Project, type: CommentType, text: String, onSave: (String, CommentType) -> Unit): JBPopup {
        // A real editor, so IdeaVim works inside the popup (needs `set ideavimsupport+=dialog`).
        val area = EditorTextField(text, project, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setPlaceholder("What should the agent change here?")
            addSettingsProvider { it.settings.isUseSoftWraps = true }
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(130))
        }
        val typeBox = ComboBox(CommentType.entries.toTypedArray()).apply {
            selectedItem = type
            renderer = listCellRenderer {
                val color = CommentColors.of(value)
                text(value.name.lowercase().replaceFirstChar { it.uppercase() }) { foreground = color }
            }
        }
        val header = JPanel(HorizontalLayout(8)).apply {
            isOpaque = false
            add(JBLabel("Type"))
            add(typeBox)
        }

        val cancel = JButton("Cancel")
        val save = JButton("Save").apply { putClientProperty("JButton.buttonType", "default") }
        val hint = JBLabel("Ctrl+Enter to save · Esc to cancel").apply {
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
                onSave(value, typeBox.selectedItem as CommentType)
                popup.closeOk(null)
            }
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
            .setTitle(if (text.isEmpty()) "New Review Comment" else "Edit Review Comment")
            .createPopup()
        return popup
    }
}
