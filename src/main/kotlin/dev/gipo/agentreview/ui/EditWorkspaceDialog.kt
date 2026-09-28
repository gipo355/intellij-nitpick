package dev.gipo.agentreview.ui

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import dev.gipo.agentreview.model.RepoRange
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.scope.RefInput
import dev.gipo.agentreview.scope.ScopeChanges
import javax.swing.JComponent

/** One row per repo. Empty base skips the repo, empty head is the working tree. Refs pin to hashes on OK. */
class EditWorkspaceDialog(private val project: Project, private val current: Scope, repoIds: List<String>) : DialogWrapper(project) {

    private class Row(val repo: String, val existing: RepoRange?) {
        val baseText = existing?.let { it.baseRef ?: it.base }.orEmpty()
        val headText = existing?.let { it.headRef ?: it.head }.orEmpty()
        val base = JBTextField(baseText, 18).apply { emptyText.text = "skip" }
        val head = JBTextField(headText, 18).apply { emptyText.text = "working tree" }
        val unchanged: Boolean get() = existing != null && base.text.trim() == baseText && head.text.trim() == headText
    }

    private val rows = repoIds.map { id -> Row(id, current.ranges.firstOrNull { it.repo == id }) }

    var result: Scope? = null
        private set

    init {
        title = "Edit Nitpick Workspace"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            comment("Base: any ref. <code>HEAD</code> = uncommitted only, <code>HEAD~3</code>, <code>main...</code> = merge-base with main.<br>Head: empty = working tree.")
        }
        row("") {
            label("Base")
            label("Head")
        }
        for (r in rows) {
            row(r.repo) {
                cell(r.base)
                cell(r.head)
            }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent? = rows.firstOrNull()?.base

    override fun doOKAction() {
        val wanted = rows.mapNotNull { r -> RefInput.parse(r.base.text)?.let { Triple(r, it, RefInput.parse(r.head.text)) } }
        val pinned = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<Result<RepoRange>>, RuntimeException>(
            {
                wanted.map { (r, base, head) ->
                    if (r.unchanged) Result.success(r.existing!!) else ScopeChanges.pinRange(project, r.repo, base, head)
                }
            },
            "Resolving Refs", false, project,
        )
        val errors = wanted.zip(pinned).mapNotNull { (w, res) ->
            res.exceptionOrNull()?.let { ValidationInfo("${w.first.repo}: ${it.message}", w.first.base) }
        }
        if (errors.isNotEmpty()) {
            setErrorInfoAll(errors)
            return
        }
        result = current.copy(ranges = pinned.map { it.getOrThrow() }.sortedBy { it.repo })
        super.doOKAction()
    }
}
