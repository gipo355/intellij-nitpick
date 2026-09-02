package dev.gipo.agentreview.channels

import com.intellij.openapi.project.Project

/** Implemented in the optional terminal module. */
interface TerminalChannel {
    /** Names of terminal tabs that could receive the review. */
    fun tabNames(): List<String>

    /** Pastes [text] into the tab matching [tabPattern] (first match) and optionally submits. */
    fun send(text: String, tabPattern: Regex, submit: Boolean): Result

    sealed interface Result {
        data class Sent(val tabName: String) : Result
        data object NoTerminal : Result
        data class Failed(val message: String) : Result
    }

    companion object {
        fun getInstance(project: Project): TerminalChannel? = project.getService(TerminalChannel::class.java)
    }
}
