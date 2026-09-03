package dev.gipo.agentreview.channels

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.gipo.agentreview.export.JsonExporter
import dev.gipo.agentreview.export.MarkdownExporter
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.scope.ScopeChanges
import dev.gipo.agentreview.settings.AgentReviewSettings
import dev.gipo.agentreview.store.ReviewStore
import dev.gipo.agentreview.ui.Notifications
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

/** Builds the export text with the user's settings. */
object ReviewExport {
    fun markdown(project: Project): String {
        val session = ReviewStore.getInstance(project).session
        val branch = try {
            ScopeChanges.currentBranch(project)
        } catch (e: Exception) {
            null
        }
        val comments = ReviewChangesModel.getInstance(project).comments()
        return MarkdownExporter.export(session, comments, AgentReviewSettings.getInstance().exportOptions(branch))
    }

    fun json(project: Project): String {
        val session = ReviewStore.getInstance(project).session
        val branch = try {
            ScopeChanges.currentBranch(project)
        } catch (e: Exception) {
            null
        }
        val comments = ReviewChangesModel.getInstance(project).comments()
        return JsonExporter.encode(JsonExporter.session(session, comments, AgentReviewSettings.getInstance().state.includeResolved, branch))
    }
}

object ClipboardChannel {
    fun send(project: Project, text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Notifications.info(project, "Review copied", "Paste it into your agent's prompt.")
    }
}

object FileChannel {
    private val LOG = logger<FileChannel>()

    /** Writes REVIEW.md and REVIEW.json next to it. Returns the markdown path. */
    fun write(project: Project, markdown: String, json: String): Path? {
        val base = project.basePath ?: return null
        val rel = AgentReviewSettings.getInstance().state.reviewFilePath?.takeIf { it.isNotBlank() } ?: ".agent-review/REVIEW.md"
        val mdPath = Path.of(base).resolve(rel)
        val jsonPath = mdPath.resolveSibling(mdPath.fileName.toString().substringBeforeLast('.') + ".json")
        return try {
            Files.createDirectories(mdPath.parent)
            Files.writeString(mdPath, markdown)
            Files.writeString(jsonPath, json)
            ApplicationManager.getApplication().invokeLater {
                WriteAction.run<RuntimeException> {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(mdPath.parent)?.refresh(true, false)
                }
            }
            Notifications.info(project, "Review written", "$rel\nTell your agent to read it.")
            mdPath
        } catch (e: Exception) {
            LOG.warn("Cannot write review file", e)
            Notifications.warn(project, "Cannot write review file", e.message ?: e.toString())
            null
        }
    }
}

/** GitHub Copilot Chat via its public CopilotChatService, resolved reflectively. */
object CopilotChannel {
    private val LOG = logger<CopilotChannel>()
    /** An action registered by the Copilot plugin; its class loader reaches the plugin's classes. */
    private const val ANCHOR_ACTION = "copilot.chat.show"

    fun isAvailable(): Boolean = ActionManager.getInstance().getAction(ANCHOR_ACTION) != null

    fun send(project: Project, text: String, dataContext: DataContext): Boolean {
        val loader = ActionManager.getInstance().getAction(ANCHOR_ACTION)?.javaClass?.classLoader ?: return false
        return try {
            val serviceClass = Class.forName("com.github.copilot.api.CopilotChatService", true, loader)
            val service = project.getService(serviceClass) ?: return false
            val builderClass = Class.forName("com.github.copilot.api.QueryOptionBuilder", true, loader)
            val withInput = builderClass.getMethod("withInput", String::class.java)
            val withNewSession = builderClass.getMethod("withNewSession")
            val configure: (Any) -> Unit = { b ->
                withInput.invoke(b, text)
                withNewSession.invoke(b)
            }
            val query = serviceClass.getMethod("query", DataContext::class.java, Function1::class.java)
            query.invoke(service, dataContext, configure)
            true
        } catch (e: Throwable) {
            LOG.warn("Copilot channel failed", e)
            false
        }
    }
}

/** JetBrains AI Assistant: open the chat and pre-fill its input (internal API, best effort). */
object AiAssistantChannel {
    private val LOG = logger<AiAssistantChannel>()
    private const val SHOW_CHAT_ACTION = "AIAssistant.ToolWindow.ShowOrFocus"
    private val LOADER_ANCHORS = listOf(
        "AIAssistantAddToChatAction", "AIAssistantAskInChatAction", "AIAssistant.NewChatInEditor", SHOW_CHAT_ACTION,
    )

    fun isAvailable(): Boolean = ActionManager.getInstance().getAction(SHOW_CHAT_ACTION) != null

    fun send(project: Project, text: String, dataContext: DataContext): Boolean {
        val am = ActionManager.getInstance()
        val action = am.getAction(SHOW_CHAT_ACTION) ?: return false
        am.tryToExecute(action, null, null, ActionPlaces.UNKNOWN, true)
        // The facade lives in a content module; only loaders of that module (or its dependants) see it.
        val facadeClass = LOADER_ANCHORS.mapNotNull { am.getAction(it)?.javaClass?.classLoader }
            .firstNotNullOfOrNull { loader ->
                try {
                    Class.forName("com.intellij.ml.llm.core.AIAContentFacade", true, loader)
                } catch (e: ClassNotFoundException) {
                    null
                }
            } ?: run {
            LOG.warn("AIAContentFacade not reachable from any anchor action")
            return false
        }
        return try {
            val companion = facadeClass.getField("Companion").get(null)
            val facade = companion.javaClass.getMethod("getInstance", Project::class.java).invoke(companion, project)
            val panel = facadeClass.getMethod("getPanel").invoke(facade)
            panel.javaClass.getMethod("openNewChat").invoke(panel)
            panel.javaClass.getMethod("setChatText", String::class.java).invoke(panel, text)
            true
        } catch (e: Throwable) {
            LOG.warn("AI Assistant channel failed", e)
            false
        }
    }
}
