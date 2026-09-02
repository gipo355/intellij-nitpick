package dev.gipo.agentreview.channels

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class TerminalChannelImpl(private val project: Project) : TerminalChannel {

    private fun widgets(): List<TerminalWidget> = TerminalToolWindowManager.getInstance(project).terminalWidgets.toList()

    private fun TerminalWidget.tabName(): String = terminalTitle.buildTitle()

    override fun tabNames(): List<String> = widgets().map { it.tabName() }

    override fun send(text: String, tabPattern: Regex, submit: Boolean): TerminalChannel.Result {
        val all = widgets()
        if (all.isEmpty()) return TerminalChannel.Result.NoTerminal
        // Prefer a tab running a command (the agent TUI), then any match.
        val matching = all.filter { tabPattern.containsMatchIn(it.tabName()) }
        val target = matching.firstOrNull { it.isCommandRunning() } ?: matching.firstOrNull()
            ?: return TerminalChannel.Result.NoTerminal
        val tty = target.ttyConnector ?: return TerminalChannel.Result.Failed("Terminal '${target.tabName()}' has no TTY yet")
        return try {
            tty.write(TerminalPayload.frame(text, submit))
            target.requestFocus()
            TerminalChannel.Result.Sent(target.tabName())
        } catch (e: Exception) {
            LOG.warn("Terminal write failed", e)
            TerminalChannel.Result.Failed(e.message ?: e.toString())
        }
    }

    companion object {
        private val LOG = logger<TerminalChannelImpl>()
    }
}
