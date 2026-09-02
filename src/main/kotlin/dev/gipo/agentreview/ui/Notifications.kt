package dev.gipo.agentreview.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifications {
    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Agent Review")

    fun info(project: Project, title: String, content: String) =
        group().createNotification(title, content, NotificationType.INFORMATION).notify(project)

    fun warn(project: Project, title: String, content: String) =
        group().createNotification(title, content, NotificationType.WARNING).notify(project)
}
