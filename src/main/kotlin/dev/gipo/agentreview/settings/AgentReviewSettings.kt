package dev.gipo.agentreview.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.gipo.agentreview.export.ExportOptions
import dev.gipo.agentreview.model.ReviewState

enum class AutoMark(val label: String) { OFF("Off"), ON_OPEN("When diff opens"), ON_CLOSE("When diff closes") }

/** Tree filter. Stale files count as unreviewed. */
enum class FileFilter(val label: String) {
    ALL("All Files"),
    UNREVIEWED("Unreviewed Only"),
    REVIEWED("Reviewed Only");

    fun shows(state: ReviewState): Boolean = when (this) {
        ALL -> true
        UNREVIEWED -> state != ReviewState.REVIEWED
        REVIEWED -> state == ReviewState.REVIEWED
    }
}

class AgentReviewState : BaseState() {
    var intro by string(ExportOptions.DEFAULT_INTRO)
    var includeSnippets by property(true)
    var snippetMaxLines by property(12)
    var includeResolved by property(false)
    var mentionMcp by property(true)
    var terminalTabPattern by string(".*")
    var terminalAutoSubmit by property(false)
    var reviewFilePath by string(".agent-review/REVIEW.md")
    // BaseState.enum() is inline bytecode targeting a newer JVM; a string property compiles everywhere.
    var autoMarkName by string(AutoMark.OFF.name)
    var autoMark: AutoMark
        get() = AutoMark.entries.firstOrNull { it.name == autoMarkName } ?: AutoMark.OFF
        set(value) { autoMarkName = value.name }
    /** Pre-0.2.1 flags, read once for migration. */
    var autoMarkReviewedOnClose by property(false)
    var autoMarkReviewedOnOpen by property(false)
    var openDiffOnSingleClick by property(false)
    /** Pre-0.3.0 flag, read once for migration. */
    var hideReviewedFiles by property(false)
    var fileFilterName by string(FileFilter.ALL.name)
    var fileFilter: FileFilter
        get() = FileFilter.entries.firstOrNull { it.name == fileFilterName } ?: FileFilter.ALL
        set(value) { fileFilterName = value.name }
}

@State(name = "AgentReviewSettings", storages = [Storage("agentReview.xml")])
class AgentReviewSettings : SimplePersistentStateComponent<AgentReviewState>(AgentReviewState()) {

    override fun loadState(state: AgentReviewState) {
        super.loadState(state)
        if (state.autoMark == AutoMark.OFF) {
            if (state.autoMarkReviewedOnOpen) state.autoMark = AutoMark.ON_OPEN
            else if (state.autoMarkReviewedOnClose) state.autoMark = AutoMark.ON_CLOSE
        }
        state.autoMarkReviewedOnOpen = false
        state.autoMarkReviewedOnClose = false
        if (state.hideReviewedFiles) state.fileFilter = FileFilter.UNREVIEWED
        state.hideReviewedFiles = false
    }

    fun exportOptions(branch: String?): ExportOptions = ExportOptions(
        intro = state.intro ?: ExportOptions.DEFAULT_INTRO,
        includeSnippets = state.includeSnippets,
        snippetMaxLines = state.snippetMaxLines,
        includeResolved = state.includeResolved,
        branch = branch,
        mcpHint = state.mentionMcp,
    )

    companion object {
        fun getInstance(): AgentReviewSettings = service()
    }
}
