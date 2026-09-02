package dev.gipo.agentreview.model

import java.security.MessageDigest

object ContentHash {
    /** Line endings are normalized so documents and file contents hash alike. */
    fun of(content: CharSequence): String {
        val normalized = content.toString().replace("\r\n", "\n").replace('\r', '\n')
        val md = MessageDigest.getInstance("SHA-1")
        md.update(normalized.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
