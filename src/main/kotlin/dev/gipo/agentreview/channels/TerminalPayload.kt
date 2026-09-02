package dev.gipo.agentreview.channels

/** Frames text for a TUI: bracketed paste keeps newlines from submitting. */
object TerminalPayload {
    private const val ESC = '\u001b'
    const val PASTE_START = "$ESC[200~"
    const val PASTE_END = "$ESC[201~"

    fun frame(text: String, submit: Boolean): String {
        val body = text.trimEnd('\n', '\r')
        val sb = StringBuilder().append(PASTE_START).append(body).append(PASTE_END)
        if (submit) sb.append('\r')
        return sb.toString()
    }
}
