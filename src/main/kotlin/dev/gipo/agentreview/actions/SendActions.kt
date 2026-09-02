package dev.gipo.agentreview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import dev.gipo.agentreview.channels.AiAssistantChannel
import dev.gipo.agentreview.channels.ClipboardChannel
import dev.gipo.agentreview.channels.CopilotChannel
import dev.gipo.agentreview.channels.FileChannel
import dev.gipo.agentreview.channels.ReviewExport
import dev.gipo.agentreview.channels.TerminalChannel
import dev.gipo.agentreview.settings.AgentReviewSettings
import dev.gipo.agentreview.ui.Notifications

abstract class SendReviewAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && isAvailable(e)
    }

    protected open fun isAvailable(e: AnActionEvent): Boolean = true
}

class CopyReviewAction : SendReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClipboardChannel.send(project, ReviewExport.markdown(project))
    }
}

class WriteReviewFileAction : SendReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileChannel.write(project, ReviewExport.markdown(project), ReviewExport.json(project))
    }
}

class SendToTerminalAction : SendReviewAction() {
    override fun isAvailable(e: AnActionEvent): Boolean = TerminalChannel.getInstance(e.project ?: return false) != null

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val channel = TerminalChannel.getInstance(project) ?: return
        val settings = AgentReviewSettings.getInstance().state
        val pattern = try {
            Regex(settings.terminalTabPattern ?: ".*")
        } catch (ex: Exception) {
            Regex(".*")
        }
        val text = ReviewExport.markdown(project)
        when (val r = channel.send(text, pattern, settings.terminalAutoSubmit)) {
            is TerminalChannel.Result.Sent -> Notifications.info(project, "Review sent to terminal", "Tab: ${r.tabName}")
            TerminalChannel.Result.NoTerminal -> {
                ClipboardChannel.send(project, text)
                Notifications.warn(project, "No matching terminal tab", "Copied to clipboard instead. Tabs: ${channel.tabNames().joinToString().ifEmpty { "none" }}")
            }
            is TerminalChannel.Result.Failed -> {
                ClipboardChannel.send(project, text)
                Notifications.warn(project, "Terminal send failed", "${r.message}. Copied to clipboard instead.")
            }
        }
    }
}

class SendToCopilotAction : SendReviewAction() {
    override fun isAvailable(e: AnActionEvent): Boolean = CopilotChannel.isAvailable()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = ReviewExport.markdown(project)
        if (!CopilotChannel.send(project, text, e.dataContext)) {
            ClipboardChannel.send(project, text)
            Notifications.warn(project, "Copilot Chat unavailable", "Copied to clipboard instead.")
        }
    }
}

class SendToAiAssistantAction : SendReviewAction() {
    override fun isAvailable(e: AnActionEvent): Boolean = AiAssistantChannel.isAvailable()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = ReviewExport.markdown(project)
        ClipboardChannel.send(project, text)
        if (!AiAssistantChannel.send(project, text, e.dataContext)) {
            Notifications.warn(project, "AI Assistant pre-fill failed", "The review is on the clipboard. Paste it into the chat.")
        }
    }
}
