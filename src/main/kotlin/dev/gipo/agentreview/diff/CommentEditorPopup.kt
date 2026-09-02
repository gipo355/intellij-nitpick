package dev.gipo.agentreview.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import dev.gipo.agentreview.model.CommentType
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Text area + type combo. Ctrl+Enter saves, Escape cancels. */
object CommentEditorPopup {

    fun show(project: Project, anchor: JComponent, type: CommentType, text: String, onSave: (String, CommentType) -> Unit) {
        val popup = build(type, text, onSave)
        popup.showUnderneathOf(anchor)
    }

    fun showAtCaret(project: Project, editor: Editor, type: CommentType, text: String, onSave: (String, CommentType) -> Unit) {
        val popup = build(type, text, onSave)
        popup.showInBestPositionFor(editor)
    }

    private fun build(type: CommentType, text: String, onSave: (String, CommentType) -> Unit): JBPopup {
        val area = JBTextArea(text, 5, 60).apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Comment for the agent… (Ctrl+Enter to save)"
        }
        val typeBox = ComboBox(CommentType.entries.toTypedArray()).apply { selectedItem = type }
        val save = JButton("Save")
        val header = JPanel(HorizontalLayout(8)).apply {
            add(JBLabel("Type:"))
            add(typeBox)
        }
        val footer = JPanel(BorderLayout()).apply {
            add(JBLabel("Ctrl+Enter to save, Esc to cancel").apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }, BorderLayout.WEST)
            add(save, BorderLayout.EAST)
        }
        val panel = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(area).apply { preferredSize = Dimension(JBUI.scale(480), JBUI.scale(120)) }, BorderLayout.CENTER)
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
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "save")
        area.actionMap.put("save", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = commit()
        })
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, area)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnClickOutside(false)
            .setCancelKeyEnabled(true)
            .setTitle("Review Comment")
            .createPopup()
        return popup
    }
}
