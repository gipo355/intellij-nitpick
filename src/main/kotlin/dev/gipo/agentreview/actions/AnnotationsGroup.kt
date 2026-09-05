package dev.gipo.agentreview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import dev.gipo.agentreview.diff.EditorReviewBinding

/** A group injected into diff viewers and editors; gone as a whole while editor annotations are off. */
class AnnotationsGroup : DefaultActionGroup() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = EditorReviewBinding.annotationsEnabled
    }
}
