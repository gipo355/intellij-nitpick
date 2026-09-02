package dev.gipo.agentreview.diff

import com.intellij.ui.JBColor
import dev.gipo.agentreview.model.CommentType
import java.awt.Color

object CommentColors {
    fun of(type: CommentType): Color = when (type) {
        CommentType.ISSUE -> JBColor(0xD32F2F, 0xEF5350)
        CommentType.QUESTION -> JBColor(0x1976D2, 0x64B5F6)
        CommentType.NIT -> JBColor(0x757575, 0x9E9E9E)
        CommentType.PRAISE -> JBColor(0x2E7D32, 0x81C784)
        CommentType.NOTE -> JBColor(0xF9A825, 0xFFD54F)
    }

    val resolved: Color = JBColor(0x2E7D32, 0x81C784)
}
