package dev.gipo.agentreview.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.store.ReviewListener
import dev.gipo.agentreview.store.ReviewStore

/**
 * Branch mode annotates source files in their own editor tabs. This binds [EditorReviewBinding] to every main
 * editor of a project file while the scope is [ScopeKind.BRANCH] and removes all of them the moment the scope
 * changes to anything else, so ordinary coding never sees comment cards or the gutter "+".
 */
@Service(Service.Level.PROJECT)
class BranchEditorBinder(private val project: Project) : Disposable {

    private val bound = HashMap<Editor, Disposable>()

    @Volatile
    private var active = false

    init {
        project.messageBus.connect(this).subscribe(ReviewListener.TOPIC, object : ReviewListener {
            override fun sessionChanged(session: ReviewSession) = sync()
        })
        sync()
    }

    /** Binds or unbinds every open editor to match the current scope. EDT. */
    fun sync() {
        val wanted = ReviewStore.getInstance(project).session.scope.kind == ScopeKind.BRANCH
        if (wanted == active) return
        active = wanted
        if (wanted) {
            EditorFactory.getInstance().allEditors.forEach { bind(it) }
        } else {
            bound.keys.toList().forEach { unbind(it) }
        }
    }

    fun onEditorCreated(editor: Editor) {
        if (active) bind(editor)
    }

    fun onEditorReleased(editor: Editor) = unbind(editor)

    private fun bind(editor: Editor) {
        if (editor !is EditorEx || editor.isDisposed || editor.project != project) return
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        if (bound.containsKey(editor) || editor.getUserData(EditorReviewBinding.KEY) != null) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!isProjectFile(file)) return
        val path = ReviewPaths.relative(project, file.path)
        val parent = Disposer.newDisposable("Nitpick branch editor $path")
        Disposer.register(this, parent)
        bound[editor] = parent
        EditorReviewBinding(project, editor, path, SingleSideMapper(Side.NEW), Side.NEW, parent)
    }

    private fun unbind(editor: Editor) {
        val parent = bound.remove(editor) ?: return
        Disposer.dispose(parent)
    }

    private fun isProjectFile(file: VirtualFile): Boolean {
        if (!file.isInLocalFileSystem) return false
        return try {
            ProjectFileIndex.getInstance(project).isInContent(file)
        } catch (e: Exception) {
            false
        }
    }

    override fun dispose() {
        bound.clear()
    }

    /** Application listener; routes to the editor's project service. */
    class Listener : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            val project = event.editor.project ?: return
            if (project.isDisposed) return
            getInstance(project).onEditorCreated(event.editor)
        }

        override fun editorReleased(event: EditorFactoryEvent) {
            val project = event.editor.project ?: return
            if (project.isDisposed) return
            project.getServiceIfCreated(BranchEditorBinder::class.java)?.onEditorReleased(event.editor)
        }
    }

    companion object {
        fun getInstance(project: Project): BranchEditorBinder = project.service()
    }
}
