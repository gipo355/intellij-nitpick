package dev.gipo.agentreview.terminal

import dev.gipo.agentreview.channels.TerminalChannel
import dev.gipo.agentreview.channels.TerminalPayload

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Reworked terminal (2026.2 default) tabs come from [TerminalToolWindowTabsManager];
 * classic tabs from [TerminalToolWindowManager]. Both are tried.
 */
class TerminalChannelImpl(private val project: Project) : TerminalChannel {

    private sealed interface Target {
        val name: String
        fun send(text: String, submit: Boolean)

        class Reworked(private val view: com.intellij.terminal.frontend.view.TerminalView) : Target {
            override val name: String get() = view.title.buildTitle()
            override fun send(text: String, submit: Boolean) {
                val builder = view.createSendTextBuilder().useBracketedPasteMode()
                if (submit) builder.shouldExecute()
                if (!builder.trySend(text.trimEnd('\n', '\r'))) error("Terminal '$name' rejected the text (session not ready?)")
            }
        }

        class Classic(private val widget: TerminalWidget) : Target {
            override val name: String get() = widget.terminalTitle.buildTitle()
            override fun send(text: String, submit: Boolean) {
                val tty = widget.ttyConnector ?: error("Terminal '$name' has no TTY yet")
                tty.write(TerminalPayload.frame(text, submit))
                widget.requestFocus()
            }
        }
    }

    private fun targets(): List<Target> {
        val reworked = try {
            TerminalToolWindowTabsManager.getInstance(project).tabs.map { Target.Reworked(it.view) }
        } catch (e: Throwable) {
            LOG.debug("Reworked terminal tabs unavailable", e)
            emptyList()
        }
        val classic = try {
            TerminalToolWindowManager.getInstance(project).terminalWidgets.map { Target.Classic(it) }
        } catch (e: Throwable) {
            LOG.debug("Classic terminal widgets unavailable", e)
            emptyList()
        }
        return reworked + classic
    }

    override fun tabNames(): List<String> = targets().map { it.name }

    override fun send(text: String, tabPattern: Regex, submit: Boolean): TerminalChannel.Result {
        val matching = targets().filter { tabPattern.containsMatchIn(it.name) }
        // Prefer a tab that looks like an agent, then any tab renamed away from the shell default.
        val target = matching.firstOrNull { AGENT_TITLE.containsMatchIn(it.name) }
            ?: matching.firstOrNull { !it.name.startsWith("Local") }
            ?: matching.firstOrNull()
            ?: return TerminalChannel.Result.NoTerminal
        return try {
            target.send(text, submit)
            TerminalChannel.Result.Sent(target.name)
        } catch (e: Exception) {
            LOG.warn("Terminal write failed", e)
            TerminalChannel.Result.Failed(e.message ?: e.toString())
        }
    }

    companion object {
        private val LOG = logger<TerminalChannelImpl>()
        private val AGENT_TITLE = Regex("claude|codex|opencode|\\bpi\\b|gemini|aider|copilot", RegexOption.IGNORE_CASE)
    }
}
