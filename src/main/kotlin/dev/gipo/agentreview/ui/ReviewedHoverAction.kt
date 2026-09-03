package dev.gipo.agentreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewNodeAction
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.HoverIcon
import dev.gipo.agentreview.actions.ToggleReviewedAction
import dev.gipo.agentreview.model.ContentHash
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.scope.ScopeChanges

/** "Mark reviewed" hover icon on file rows of the Commit tool window. */
class ReviewedHoverAction(private val project: Project) : ChangesViewNodeAction {

    override fun createNodeHoverIcon(node: ChangesBrowserNode<*>): HoverIcon? {
        val change = node.userObject as? Change ?: return null
        return object : HoverIcon(AllIcons.Actions.Checked, "Toggle reviewed (Nitpick)") {
            override fun invokeAction(node: ChangesBrowserNode<*>) {
                ToggleReviewedAction.toggleReviewed(project, ReviewPaths.relative(project, change), null) { ScopeChanges.content(change.afterRevision)?.let(ContentHash::of) }
            }
        }
    }
}
