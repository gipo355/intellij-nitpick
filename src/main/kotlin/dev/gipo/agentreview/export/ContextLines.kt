package dev.gipo.agentreview.export

/** Numbered lines around a 1-based line range, for agents that want the anchor without a file read. */
object ContextLines {
    fun around(content: CharSequence, startLine: Int, endLine: Int, extra: Int): String {
        val lines = content.lines()
        val first = (startLine - extra).coerceAtLeast(1)
        val last = (endLine + extra).coerceAtMost(lines.size)
        if (first > last) return ""
        val width = last.toString().length
        return (first..last).joinToString("\n") { n -> n.toString().padStart(width) + ": " + lines[n - 1] }
    }
}
