package dev.gipo.agentreview.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.gipo.agentreview.export.ExportOptions

class AgentReviewState : BaseState() {
    var intro by string(ExportOptions.DEFAULT_INTRO)
    var includeSnippets by property(true)
    var snippetMaxLines by property(12)
    var includeResolved by property(false)
    var terminalTabPattern by string(".*")
    var terminalAutoSubmit by property(false)
    var reviewFilePath by string(".agent-review/REVIEW.md")
    var autoMarkReviewedOnClose by property(false)
    var autoMarkReviewedOnOpen by property(false)
}

@State(name = "AgentReviewSettings", storages = [Storage("agentReview.xml")])
class AgentReviewSettings : SimplePersistentStateComponent<AgentReviewState>(AgentReviewState()) {

    fun exportOptions(branch: String?): ExportOptions = ExportOptions(
        intro = state.intro ?: ExportOptions.DEFAULT_INTRO,
        includeSnippets = state.includeSnippets,
        snippetMaxLines = state.snippetMaxLines,
        includeResolved = state.includeResolved,
        branch = branch,
    )

    companion object {
        fun getInstance(): AgentReviewSettings = service()
    }
}
