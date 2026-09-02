package dev.gipo.agentreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.concurrency.AppExecutorUtil
import dev.gipo.agentreview.scope.ScopeChanges
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangeNodeDecorator
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.SimpleChangesBrowser
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.gipo.agentreview.actions.ToggleReviewedAction
import dev.gipo.agentreview.model.Author
import dev.gipo.agentreview.model.Comment
import dev.gipo.agentreview.model.ReviewSession
import dev.gipo.agentreview.model.ReviewState
import dev.gipo.agentreview.model.Scope
import dev.gipo.agentreview.model.ScopeKind
import dev.gipo.agentreview.model.Side
import dev.gipo.agentreview.model.commentOrder
import dev.gipo.agentreview.scope.ChangesListener
import dev.gipo.agentreview.scope.ReviewChangesModel
import dev.gipo.agentreview.scope.ReviewPaths
import dev.gipo.agentreview.scope.ReviewedChange
import dev.gipo.agentreview.store.ReviewListener
import dev.gipo.agentreview.store.ReviewStore
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import com.intellij.diff.util.Side as DiffSide

class ReviewToolWindowPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true), Disposable {

    private val store = ReviewStore.getInstance(project)
    private val model = ReviewChangesModel.getInstance(project)
    private val browser = SimpleChangesBrowser(project, false, false)
    private val commentsModel = DefaultListModel<Comment>()
    private val commentsList = JBList(commentsModel)
    private val status = JBLabel()
    private val notes = JBTextArea(2, 20)
    private var suppressNotes = false

    init {
        Disposer.register(parent, this)
        browser.setChangeNodeDecorator(ReviewDecorator())
        browser.viewer.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE) {
                    toggleSelectedReviewed()
                    e.consume()
                }
            }
        })

        commentsList.cellRenderer = CommentRenderer()
        commentsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        commentsList.emptyText.text = "No comments yet. Open a diff and press Alt+Shift+C."
        commentsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) commentsList.selectedValue?.let { open(it) }
            }
        })
        commentsList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val c = commentsList.selectedValue ?: return
                when (e.keyCode) {
                    KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> store.removeComment(c.id)
                    KeyEvent.VK_ENTER -> open(c)
                    KeyEvent.VK_R -> store.updateComment(c.id) { it.copy(resolved = !it.resolved) }
                }
            }
        })

        notes.lineWrap = true
        notes.wrapStyleWord = true
        notes.emptyText.text = "Review-level notes for the agent…"
        notes.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = push()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = push()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = push()
            private fun push() {
                if (!suppressNotes) store.setNotes(notes.text)
            }
        })

        val bottom = JPanel(BorderLayout()).apply {
            add(JBLabel("Comments").apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
            add(JBScrollPane(commentsList), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(JBLabel("Notes").apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
                add(JBScrollPane(notes), BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }
        val splitter = OnePixelSplitter(true, 0.55f).apply {
            firstComponent = browser
            secondComponent = bottom
        }
        status.border = JBUI.Borders.empty(2, 8)
        status.foreground = UIUtil.getContextHelpForeground()

        toolbar = createToolbar()
        setContent(JPanel(BorderLayout()).apply {
            add(splitter, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        })

        val bus = project.messageBus.connect(this)
        bus.subscribe(ReviewListener.TOPIC, object : ReviewListener {
            override fun sessionChanged(session: ReviewSession) = refreshUi()
        })
        bus.subscribe(ChangesListener.TOPIC, object : ChangesListener {
            override fun changesUpdated(changes: List<ReviewedChange>) {
                browser.setChangesToDisplay(changes.map { it.change })
                refreshUi()
            }
        })
        refreshUi()
        model.refresh()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(ScopeCombo())
            add(object : AnAction("Refresh", "Re-collect changes", AllIcons.Actions.Refresh), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = model.refresh()
            })
            add(ActionManager.getInstance().getAction("AgentReview.NextUnreviewed"))
            add(object : AnAction("Toggle Reviewed", "Space also toggles the selected file", AllIcons.Actions.Checked), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = toggleSelectedReviewed()
            })
            add(Separator.getInstance())
            add(ActionManager.getInstance().getAction("AgentReview.CopyMarkdown"))
            add(ActionManager.getInstance().getAction("AgentReview.SendToTerminal"))
            add(ActionManager.getInstance().getAction("AgentReview.WriteFile"))
            add(ActionManager.getInstance().getAction("AgentReview.SendGroup"))
            add(Separator.getInstance())
            add(object : AnAction("Clear Resolved Comments", "Delete comments the agent already resolved", AllIcons.Actions.Cancel), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = store.update { s -> s.copy(comments = s.comments.filterNot { it.resolved }) }
            })
            add(object : AnAction("Clear Session", "Delete all comments and reviewed marks", AllIcons.Actions.GC), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val ok = Messages.showYesNoDialog(project, "Delete all comments and reviewed marks?", "Clear Review Session", null)
                    if (ok == Messages.YES) store.clear()
                }
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AgentReviewToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun refreshUi() {
        val session = store.session
        browser.viewer.repaint()
        commentsModel.clear()
        session.comments.sortedWith(commentOrder).forEach { commentsModel.addElement(it) }
        if (notes.text != session.notes) {
            suppressNotes = true
            notes.text = session.notes
            suppressNotes = false
        }
        val changes = model.changes
        val reviewed = changes.count { model.state(it) == ReviewState.REVIEWED }
        val stale = changes.count { model.state(it) == ReviewState.STALE }
        val open = session.comments.count { !it.resolved }
        status.text = buildString {
            append("${changes.size} files · $reviewed reviewed")
            if (stale > 0) append(" · $stale stale")
            append(" · $open open comments")
            append(" · ${session.scope.describe()}")
        }
    }

    private fun toggleSelectedReviewed() {
        for (change in browser.selectedChanges) {
            ToggleReviewedAction.toggleReviewed(project, ReviewPaths.relative(project, change), null)
        }
    }

    private fun open(c: Comment) {
        if (c.isReviewLevel) return
        val side = if (c.side == Side.OLD) DiffSide.LEFT else DiffSide.RIGHT
        model.navigate(c.path, c.startLine, side)
    }

    override fun dispose() {}

    private inner class ReviewDecorator : ChangeNodeDecorator {
        override fun decorate(change: Change, component: SimpleColoredComponent, isShowFlatten: Boolean) {
            val path = ReviewPaths.relative(project, change)
            val rc = model.find(path)
            val state = rc?.let { model.state(it) } ?: ReviewState.UNREVIEWED
            val open = store.session.commentsFor(path).count { !it.resolved }
            if (open > 0) component.append("  $open ✎", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            when (state) {
                ReviewState.REVIEWED -> component.append("  ✓", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, com.intellij.ui.JBColor(0x2E7D32, 0x81C784)))
                ReviewState.STALE -> component.append("  ⟳ changed", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                ReviewState.UNREVIEWED -> Unit
            }
        }

        override fun preDecorate(change: Change, renderer: ChangesBrowserNodeRenderer, showFlatten: Boolean) {}
    }

    private class CommentRenderer : ColoredListCellRenderer<Comment>() {
        override fun customizeCellRenderer(list: JList<out Comment>, value: Comment, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = if (value.resolved) AllIcons.RunConfigurations.TestPassed else AllIcons.Toolwindows.ToolWindowMessages
            if (value.type.marker.isNotEmpty()) append("[${value.type.marker}] ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append(value.location(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ")
            val attrs = if (value.resolved) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
            append(value.text.lineSequence().first().take(120), attrs)
            if (value.author == Author.AGENT) append("  (agent)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
    }

    private inner class ScopeCombo : ComboBoxAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.text = store.session.scope.kind.label
        }

        override fun createPopupActionGroup(button: JComponent, context: com.intellij.openapi.actionSystem.DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            for (kind in listOf(ScopeKind.UNCOMMITTED, ScopeKind.STAGED, ScopeKind.UNSTAGED)) {
                group.add(object : AnAction(kind.label), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) = setScope(Scope(kind))
                })
            }
            group.add(Separator.getInstance())
            group.add(object : AnAction("Compare with Branch…", "Review everything on HEAD since it diverged from a branch (merge-base)", null), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = chooseBranch(e)
            })
            group.add(object : AnAction("Commit Range…", "Enter base..head, e.g. main..HEAD or abc123..def456", null), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val current = store.session.scope.let { if (it.kind == ScopeKind.RANGE) "${it.baseLabel ?: it.base}..${it.head ?: "HEAD"}" else "main..HEAD" }
                    val input = Messages.showInputDialog(project, "Range as base..head (three dots = since merge-base):", "Review Commit Range", null, current, null)
                        ?.trim()?.takeIf { it.isNotEmpty() } ?: return
                    val threeDot = input.contains("...")
                    val parts = input.split("...", "..").map { it.trim() }
                    val base = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return
                    val head = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "HEAD"
                    if (threeDot) setMergeBaseScope(base, head) else setScope(Scope(ScopeKind.RANGE, base = base, head = head))
                }
            })
            group.add(object : AnAction("Single Commit…"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val hash = Messages.showInputDialog(project, "Commit hash or ref:", "Review Commit", null, store.session.scope.head ?: "HEAD", null)
                        ?: return
                    setScope(Scope(ScopeKind.COMMIT, head = hash.trim()))
                }
            })
            return group
        }

        private fun setScope(scope: Scope) {
            store.setScope(scope)
            model.refresh()
        }

        private fun chooseBranch(e: AnActionEvent) {
            val component = e.inputEvent?.component ?: this@ReviewToolWindowPanel
            runInBackground({ ScopeChanges.branchNames(project) }) { names ->
                    if (names.isEmpty()) {
                        Notifications.warn(project, "No branches found", "Is this a git repository?")
                        return@runInBackground
                    }
                    JBPopupFactory.getInstance().createPopupChooserBuilder(names)
                        .setTitle("Review Changes Since Branch")
                        .setNamerForFiltering { it }
                        .setItemChosenCallback { setMergeBaseScope(it, "HEAD") }
                        .createPopup()
                        .showUnderneathOf(component)
                }
        }

        /** base = merge-base(ref, head), like a pull request diff. */
        private fun setMergeBaseScope(ref: String, head: String) {
            runInBackground({ ScopeChanges.mergeBase(project, ref) }) { mb ->
                    if (mb == null) {
                        Notifications.warn(project, "Cannot resolve merge-base", "git merge-base $ref $head failed. Using $ref directly.")
                        setScope(Scope(ScopeKind.RANGE, base = ref, head = head))
                    } else {
                        setScope(Scope(ScopeKind.RANGE, base = mb, head = head, baseLabel = "merge-base($ref)"))
                    }
                }
        }

        private fun <T> runInBackground(compute: () -> T, onDone: (T) -> Unit) {
            AppExecutorUtil.getAppExecutorService().execute {
                val result = compute()
                ApplicationManager.getApplication().invokeLater({ onDone(result) }, ModalityState.defaultModalityState(), project.disposed)
            }
        }
    }
}
