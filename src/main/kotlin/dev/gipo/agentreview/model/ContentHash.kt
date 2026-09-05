package dev.gipo.agentreview.model

import java.nio.CharBuffer
import java.security.MessageDigest

object ContentHash {
    /**
     * SHA-1 (first 16 hex chars) of the UTF-8 text with `\r\n` and `\r` folded to `\n`, so documents and
     * file contents hash alike. One normalizing pass into a char array, one encode: no String copies.
     */
    fun of(content: CharSequence): String {
        val len = content.length
        val out = CharArray(len)
        var n = 0
        var i = 0
        while (i < len) {
            val c = content[i]
            if (c == '\r') {
                out[n++] = '\n'
                if (i + 1 < len && content[i + 1] == '\n') i++
            } else {
                out[n++] = c
            }
            i++
        }
        val md = MessageDigest.getInstance("SHA-1")
        md.update(Charsets.UTF_8.encode(CharBuffer.wrap(out, 0, n)))
        val digest = md.digest()
        val sb = StringBuilder(16)
        for (k in 0 until 8) {
            val b = digest[k].toInt() and 0xff
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
