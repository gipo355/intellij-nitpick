package dev.gipo.agentreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
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
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.diff.tools.combined.CombinedDiffRegistry
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.options.advanced.AdvancedSettingsChangeListener
import com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.ChangesTreeDiffPreviewHandler
import com.intellij.openapi.vcs.changes.ui.ChangeNodeDecorator
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vcs.changes.ui.TreeHandlerEditorDiffPreview
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.SimpleChangesBrowser
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.PopupHandler
import dev.gipo.agentreview.diff.CommentEditorPopup
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
import dev.gipo.agentreview.settings.AgentReviewSettings
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
    private companion object {
        val LOG = com.intellij.openapi.diagnostic.logger<ReviewToolWindowPanel>()
    }

    private val store = ReviewStore.getInstance(project)
    private val model = ReviewChangesModel.getInstance(project)
    private val browser = object : SimpleChangesBrowser(project, false, false) {
        // Called from the super constructor: no instance state allowed here.
        override fun createPopupMenuActions(): List<AnAction> =
            super.createPopupMenuActions() + Separator.getInstance() + ActionManager.getInstance().getAction("AgentReview.TreePopup")
    }
    private val commentsModel = DefaultListModel<Comment>()
    private val commentsList = JBList(commentsModel)
    private val status = JBLabel()
    private val notes = JBTextArea(6, 20)
    private var suppressNotes = false
    private var shown: List<String> = emptyList()

    init {
        Disposer.register(parent, this)
        browser.setChangeNodeDecorator(ReviewDecorator())
        val handler = GatedPreviewHandler()
        val preview = object : TreeHandlerEditorDiffPreview(browser.viewer, handler) {
            override fun getEditorTabName(wrapper: ChangeViewDiffRequestProcessor.Wrapper?): String =
                "Nitpick" + (wrapper?.presentableName?.let { ": $it" } ?: "")

            override fun handleDoubleClick(e: MouseEvent): Boolean {
                syncPreviewToSelection(handler)
                return super.handleDoubleClick(e)
            }

            override fun handleEnterKey(): Boolean {
                syncPreviewToSelection(handler)
                return super.handleEnterKey()
            }

            /** The combined (continuous) viewer builds its toolbar from context actions, not from action groups. */
            override fun createViewer(): DiffEditorViewer {
                val viewer = super.createViewer()
                if (viewer.javaClass.name.contains("Combined")) {
                    val existing = viewer.context.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS).orEmpty()
                    val ours = ActionManager.getInstance().getAction("AgentReview.DiffToolbar")
                    viewer.context.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, existing + ours)
                }
                return viewer
            }
        }
        Disposer.register(this, preview)
        browser.setShowDiffActionPreview(preview)
        browser.viewer.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || !AgentReviewSettings.getInstance().state.openDiffOnSingleClick) return
                if (browser.viewer.getPathForLocation(e.x, e.y) == null || browser.selectedChanges.isEmpty()) return
                ApplicationManager.getApplication().invokeLater({ preview.openPreview(false) }, project.disposed)
            }
        })
        model.diffOpener = { rc ->
            browser.viewer.setSelectedChanges(listOfNotNull(rc.change))
            val selected = browser.selectedChanges
            LOG.info("diffOpener selected=${selected.size} previewOpen=${preview.isPreviewOpen()} hasContent=${preview.hasContent()}")
            if (selected.isEmpty()) {
                false
            } else {
                syncPreviewToSelection(handler)
                val ok = preview.performDiffAction()
                LOG.info("diffOpener performDiffAction=$ok previewOpen=${preview.isPreviewOpen()}")
                ok || preview.openPreview(true)
            }
        }
        project.messageBus.connect(this).subscribe(AdvancedSettingsChangeListener.TOPIC, object : AdvancedSettingsChangeListener {
            override fun advancedSettingChanged(id: String, oldValue: Any, newValue: Any) {
                if (id != "enable.combined.diff" || !preview.isPreviewOpen()) return
                preview.closePreview()
                handler.passNext = true
                ApplicationManager.getApplication().invokeLater({ preview.openPreview(false) }, project.disposed)
            }
        })
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

        PopupHandler.installPopupMenu(commentsList, commentsPopup(), "AgentReviewComments")

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

        val commentsPane = JPanel(BorderLayout()).apply {
            add(JBLabel("Comments").apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
            add(JBScrollPane(commentsList), BorderLayout.CENTER)
        }
        val notesPane = JPanel(BorderLayout()).apply {
            add(JBLabel("Notes for the agent").apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
            add(JBScrollPane(notes), BorderLayout.CENTER)
        }
        val bottom = OnePixelSplitter(true, "Nitpick.notesSplit", 0.6f).apply {
            firstComponent = commentsPane
            secondComponent = notesPane
        }
        val splitter = OnePixelSplitter(true, "Nitpick.treeSplit", 0.5f).apply {
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
                shown = emptyList()
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
            add(ActionManager.getInstance().getAction("AgentReview.PrevUnreviewed"))
            add(ActionManager.getInstance().getAction("AgentReview.NextUnreviewed"))
            add(object : AnAction("Toggle Reviewed", "Space also toggles the selected file", AllIcons.Actions.Checked), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = toggleSelectedReviewed()
            })
            add(ActionManager.getInstance().getAction("AgentReview.AddFileComment"))
            add(object : ToggleAction("Hide Reviewed Files", "Show only unreviewed and changed files", AllIcons.Actions.ToggleVisibility), DumbAware {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun isSelected(e: AnActionEvent): Boolean = AgentReviewSettings.getInstance().state.hideReviewedFiles
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    AgentReviewSettings.getInstance().state.hideReviewedFiles = state
                    showChanges()
                }
            })
            add(Separator.getInstance())
            add(ActionManager.getInstance().getAction("AgentReview.CopyMarkdown"))
            add(ActionManager.getInstance().getAction("AgentReview.SendToTerminal"))
            add(ActionManager.getInstance().getAction("AgentReview.WriteFile"))
            add(ActionManager.getInstance().getAction("AgentReview.SendGroup"))
            add(Separator.getInstance())
            add(object : AnAction("Reset Reviewed Marks", "Unmark every file, keep comments", AllIcons.Actions.Rollback), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = store.update { it.copy(reviewed = emptyMap()) }
            })
            add(object : AnAction("Clear Resolved Comments", "Delete comments the agent already resolved", AllIcons.Actions.Cancel), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = store.removeComments(model.comments().filter { it.resolved }.map { it.id })
            })
            add(object : AnAction("Clear Session", "Delete this scope's comments and reviewed marks", AllIcons.Actions.GC), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val ok = Messages.showYesNoDialog(project, "Delete all comments and reviewed marks of ${store.session.scope.describe()}?", "Clear Review Session", null)
                    if (ok == Messages.YES) store.clear()
                }
            })
            add(object : AnAction("Clear All Sessions", "Delete comments and marks of every scope in this project", AllIcons.Actions.DeleteTag), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val ok = Messages.showYesNoDialog(project, "Delete every saved review session of this project?", "Clear All Sessions", null)
                    if (ok == Messages.YES) store.clearAll()
                }
            })
            add(object : AnAction("Forget Other Sessions", "Drop saved sessions of other scopes", AllIcons.Actions.ClearCash), DumbAware {
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = store.sessionCount > 1
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = store.forgetOtherSessions()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AgentReviewToolbar", group, true)
        toolbar.targetComponent = browser.viewer
        return toolbar.component
    }

    private fun commentsPopup(): DefaultActionGroup {
        fun selected(): Comment? = commentsList.selectedValue
        fun action(text: String, icon: javax.swing.Icon?, enabled: (Comment) -> Boolean = { true }, run: (Comment) -> Unit) =
            object : AnAction(text, null, icon), DumbAware {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selected()?.let(enabled) ?: false
                }
                override fun actionPerformed(e: AnActionEvent) {
                    selected()?.let(run)
                }
            }
        return DefaultActionGroup(
            action("Open in Diff", AllIcons.Actions.Diff, { !it.isReviewLevel }) { open(it) },
            action("Edit…", AllIcons.Actions.Edit) { c ->
                CommentEditorPopup.show(project, commentsList, c.type, c.text) { text, type ->
                    store.updateComment(c.id) { it.copy(text = text, type = type) }
                }
            },
            object : AnAction("Resolve", null, AllIcons.RunConfigurations.TestPassed), DumbAware {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    val c = selected()
                    e.presentation.isEnabled = c != null
                    e.presentation.text = if (c?.resolved == true) "Reopen" else "Resolve"
                }
                override fun actionPerformed(e: AnActionEvent) {
                    selected()?.let { c -> store.updateComment(c.id) { it.copy(resolved = !it.resolved) } }
                }
            },
            action("Copy Location", AllIcons.Actions.Copy) {
                CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(it.location()))
            },
            Separator.getInstance(),
            action("Delete", AllIcons.Actions.GC) { store.removeComment(it.id) },
        )
    }

    /** Reviewed files drop out when the toggle is on. Stale files stay. */
    private fun showChanges() {
        val hide = AgentReviewSettings.getInstance().state.hideReviewedFiles
        val visible = model.changes.filter { !hide || model.state(it) != ReviewState.REVIEWED }
        val paths = visible.map { it.path }
        if (paths == shown) return
        shown = paths
        browser.setChangesToDisplay(visible.mapNotNull { it.change })
    }

    private fun refreshUi() {
        val session = store.session
        showChanges()
        browser.viewer.repaint()
        commentsModel.clear()
        val comments = model.comments()
        comments.sortedWith(commentOrder).forEach { commentsModel.addElement(it) }
        if (notes.text != session.notes) {
            suppressNotes = true
            notes.text = session.notes
            suppressNotes = false
        }
        val changes = model.changes
        val reviewed = changes.count { model.state(it) == ReviewState.REVIEWED }
        val stale = changes.count { model.state(it) == ReviewState.STALE }
        val open = comments.count { !it.resolved }
        status.text = buildString {
            append("${changes.size} files · $reviewed reviewed")
            if (stale > 0) append(" · $stale stale")
            append(" · $open open comments")
            append(" · ${session.scope.describe()}")
            val others = store.sessionCount - (if (session.isEmpty) 0 else 1)
            if (others > 0) append(" · $others other session${if (others > 1) "s" else ""} saved")
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

    override fun dispose() {
        model.diffOpener = null
    }

    /**
     * An open preview only updates from tree selection events. Re-fire the selection with the
     * gate open so this explicit open lands even when single-click following is off.
     */
    private fun syncPreviewToSelection(handler: GatedPreviewHandler) {
        val selected = browser.selectedChanges
        if (selected.isEmpty()) return
        handler.passNext = false
        browser.viewer.selectionModel.clearSelection()
        handler.passNext = true
        browser.viewer.setSelectedChanges(selected)
    }

    /**
     * Follows tree selection only when "open diff on single click" is on, or for one explicit
     * open (double-click, Enter, navigation). Otherwise the preview keeps showing what it shows.
     */
    private inner class GatedPreviewHandler : ChangesTreeDiffPreviewHandler() {
        @Volatile
        var passNext = false
        private var cached: List<ChangeViewDiffRequestProcessor.Wrapper> = emptyList()

        private fun wrap(data: VcsTreeModelData): List<ChangeViewDiffRequestProcessor.Wrapper> =
            data.iterateUserObjects(Change::class.java).map { ChangeViewDiffRequestProcessor.ChangeWrapper(it) }.toList()

        override fun iterateSelectedChanges(tree: ChangesTree): Iterable<ChangeViewDiffRequestProcessor.Wrapper> {
            val current = wrap(VcsTreeModelData.selected(tree))
            if (AgentReviewSettings.getInstance().state.openDiffOnSingleClick) {
                cached = current
            } else if (passNext && current.isNotEmpty()) {
                passNext = false
                cached = current
            }
            return cached
        }

        override fun iterateAllChanges(tree: ChangesTree): Iterable<ChangeViewDiffRequestProcessor.Wrapper> =
            wrap(VcsTreeModelData.all(tree))

        override fun selectChange(tree: ChangesTree, change: ChangeViewDiffRequestProcessor.Wrapper) {
            (change.userObject as? Change)?.let { tree.setSelectedChanges(listOf(it)) }
        }
    }

    private inner class ReviewDecorator : ChangeNodeDecorator {
        override fun decorate(change: Change, component: SimpleColoredComponent, isShowFlatten: Boolean) {
            val path = ReviewPaths.relative(project, change)
            val rc = model.find(path)
            val state = rc?.let { model.state(it) } ?: ReviewState.UNREVIEWED
            val open = model.commentsFor(path).count { !it.resolved }
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
            if (value.outdated) append("  outdated", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
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
