package dev.gipo.agentreview.diff

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.gipo.agentreview.model.CommentType

/** Text left in a comment editor that closed without Save, keyed by what was being edited. Memory only. */
@Service(Service.Level.PROJECT)
class CommentDrafts {
    data class Draft(val text: String, val type: CommentType)

    private val drafts = HashMap<String, Draft>()

    @Synchronized
    fun get(key: String): Draft? = drafts[key]

    /** Blank text, or text equal to [original], is no draft. */
    @Synchronized
    fun put(key: String, text: String, type: CommentType, original: String) {
        if (text.isBlank() || text == original) drafts.remove(key) else drafts[key] = Draft(text, type)
    }

    @Synchronized
    fun clear(key: String) {
        drafts.remove(key)
    }

    companion object {
        fun getInstance(project: Project): CommentDrafts = project.service()
    }
}
