package dev.gipo.agentreview.ui

import com.intellij.diff.tools.combined.CombinedDiffRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.gipo.agentreview.settings.AgentReviewSettings
import dev.gipo.agentreview.settings.AgentReviewState
import javax.swing.Icon
import kotlin.reflect.KMutableProperty1

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewToolWindowPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        val toggles = listOf(
            SettingToggle("Open Diff on Single Click", AllIcons.Actions.Preview, AgentReviewState::openDiffOnSingleClick),
            SettingToggle("Mark Reviewed When Diff Opens", AllIcons.Actions.SetDefault, AgentReviewState::autoMarkReviewedOnOpen),
            SettingToggle("Mark Reviewed When Diff Closes", AllIcons.Actions.Exit, AgentReviewState::autoMarkReviewedOnClose),
        )
        val continuous = object : ToggleAction("Continuous Diff (All Files in One View)", "IDE-wide diff setting, also used by the Commit tool window", AllIcons.Actions.ListFiles), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun isSelected(e: AnActionEvent): Boolean = CombinedDiffRegistry.isEnabled()
            override fun setSelected(e: AnActionEvent, state: Boolean) = CombinedDiffRegistry.setCombinedDiffEnabled(state)
        }
        val all = toggles + continuous
        toolWindow.setTitleActions(all)
        toolWindow.setAdditionalGearActions(DefaultActionGroup(all))
    }

    /** Header quick toggle bound to one boolean setting. */
    private class SettingToggle(
        text: String,
        icon: Icon,
        private val property: KMutableProperty1<AgentReviewState, Boolean>,
    ) : ToggleAction(text, text, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = property.get(AgentReviewSettings.getInstance().state)
        override fun setSelected(e: AnActionEvent, state: Boolean) = property.set(AgentReviewSettings.getInstance().state, state)
    }
}
