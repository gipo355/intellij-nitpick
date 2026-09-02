package dev.gipo.agentreview.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class AgentReviewConfigurable : BoundConfigurable("Nitpick") {

    override fun createPanel(): DialogPanel {
        val s = AgentReviewSettings.getInstance().state
        return panel {
            group("Export") {
                row("Intro sentence:") {
                    textField().columns(60).bindText({ s.intro ?: "" }, { s.intro = it })
                }
                row { checkBox("Include code snippets").bindSelected(s::includeSnippets) }
                row("Snippet max lines:") { intTextField(1..200).bindIntText(s::snippetMaxLines) }
                row { checkBox("Include resolved comments").bindSelected(s::includeResolved) }
                row("Review file path (project-relative):") {
                    textField().columns(40).bindText({ s.reviewFilePath ?: "" }, { s.reviewFilePath = it })
                }
            }
            group("Terminal Agent") {
                row("Terminal tab name pattern (regex):") {
                    textField().columns(30).bindText({ s.terminalTabPattern ?: ".*" }, { s.terminalTabPattern = it })
                        .comment("First matching tab wins. Use e.g. <code>claude|codex|opencode|pi</code>.")
                }
                row { checkBox("Submit after pasting (press Enter)").bindSelected(s::terminalAutoSubmit) }
            }
            group("Review Flow") {
                row {
                    checkBox("Open diff on single click in the Nitpick tree").bindSelected(s::openDiffOnSingleClick)
                }
                row {
                    checkBox("Mark file reviewed when its diff is opened").bindSelected(s::autoMarkReviewedOnOpen)
                }
                row {
                    checkBox("Mark file reviewed when its diff is closed").bindSelected(s::autoMarkReviewedOnClose)
                }
            }
        }
    }
}
