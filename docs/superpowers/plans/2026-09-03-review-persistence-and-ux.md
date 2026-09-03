# Review persistence and UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement GitHub issues #2, #3, #4, #5, #6 plus the tree context menu, the MCP hint in the prompt, the auto-mark switcher and auto refresh. Bump to 0.2.1 and open a PR.

**Architecture:** Comments move out of per-scope sessions into one project-wide list. Each comment is anchored to the text it was written on (path, side, line, content hash, snippet). The changes model computes, per scope, where that text now is: same line, relocated line, or outdated. Nothing is ever mutated by a scope change. Sessions keep scope, reviewed marks and notes. Reviewed marks carry over between sessions by content hash.

**Tech Stack:** Kotlin 2.3, IntelliJ Platform 262, kotlinx.serialization, JUnit 4, `./gradlew test`.

**Spec:** the GitHub issues #2–#6 of gipo355/intellij-nitpick and the conversation of 2026-09-03. Decisions: relocation is snippet based (no git blob reads, no diff engine), marks shared by hash, one "hide reviewed" toggle, internal JSON export format.

## Global Constraints

- Short sentences in docs and comments. Comments only where code needs clarification.
- No new abstractions for single use. Match existing style.
- `./gradlew test` must pass after every task. `./gradlew verifyPlugin` must stay Compatible at the end.
- Descriptor stays in the new format (no `<depends>`).
- `ReviewStore.update` keeps publishing on the EDT.
- Legacy JSON (per-session `comments`) must still load.

---

### Task 1: Auto-mark switcher (Off / On Open / On Close)

**Files:**
- Modify: `src/main/kotlin/dev/gipo/agentreview/settings/AgentReviewSettings.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/settings/AgentReviewConfigurable.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/ui/ReviewToolWindowFactory.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/diff/ReviewDiffExtension.kt`

**Produces:** `enum class AutoMark { OFF, ON_OPEN, ON_CLOSE }`, `AgentReviewState.autoMark`.

- [ ] **Step 1: Replace the two booleans with one enum**

In `AgentReviewSettings.kt`:

```kotlin
enum class AutoMark(val label: String) { OFF("Off"), ON_OPEN("When diff opens"), ON_CLOSE("When diff closes") }

class AgentReviewState : BaseState() {
    ...
    var autoMark by enum(AutoMark.OFF)
    /** Pre-0.2.1 flags, read once for migration. */
    var autoMarkReviewedOnClose by property(false)
    var autoMarkReviewedOnOpen by property(false)
    ...
}
```

In `AgentReviewSettings` add:

```kotlin
override fun loadState(state: AgentReviewState) {
    super.loadState(state)
    if (state.autoMark == AutoMark.OFF) {
        if (state.autoMarkReviewedOnOpen) state.autoMark = AutoMark.ON_OPEN
        else if (state.autoMarkReviewedOnClose) state.autoMark = AutoMark.ON_CLOSE
    }
    state.autoMarkReviewedOnOpen = false
    state.autoMarkReviewedOnClose = false
}
```

- [ ] **Step 2: Settings page**

Replace the two checkbox rows with:

```kotlin
row("Mark file reviewed automatically:") {
    comboBox(AutoMark.entries, SimpleListCellRenderer.create("") { it.label }).bindItem({ s.autoMark }, { s.autoMark = it ?: AutoMark.OFF })
}
```

Imports: `com.intellij.ui.SimpleListCellRenderer`, `com.intellij.ui.dsl.builder.bindItem`.

- [ ] **Step 3: Header toggles become a radio group**

In `ReviewToolWindowFactory.kt` remove the two `SettingToggle`s for auto-mark and add:

```kotlin
private class AutoMarkChoice(private val value: AutoMark) : ToggleAction(value.label), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean = AgentReviewSettings.getInstance().state.autoMark == value
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) AgentReviewSettings.getInstance().state.autoMark = value
    }
}
```

and a popup group:

```kotlin
val autoMark = DefaultActionGroup("Auto-Mark Reviewed", AutoMark.entries.map { AutoMarkChoice(it) }).apply {
    isPopup = true
    templatePresentation.icon = AllIcons.Actions.SetDefault
    templatePresentation.description = "Mark a file reviewed when its diff opens or closes"
}
val all = toggles + autoMark + continuous
```

- [ ] **Step 4: Diff extension reads the enum**

In `installAutoReviewed` replace every `settings.autoMarkReviewedOnOpen` with `settings.autoMark == AutoMark.ON_OPEN` and `...OnClose` with `== AutoMark.ON_CLOSE`. The early return becomes `if (settings.autoMark == AutoMark.OFF) return`.

- [ ] **Step 5: Build and test**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git commit -am "Replace auto-mark checkboxes with one Off/Open/Close setting"
```

---

### Task 2: File actions from the tree, file comment in the toolbar, file-level card

**Files:**
- Modify: `src/main/kotlin/dev/gipo/agentreview/actions/DiffActions.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/ui/ReviewToolWindowPanel.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/diff/EditorReviewBinding.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Produces:** `AnActionEvent.reviewPath()` also resolves from `VcsDataKeys.CHANGES`. New action `AgentReview.EditFileComments` (popup group).

- [ ] **Step 1: Path fallback from the tree selection**

In `DiffActions.kt`:

```kotlin
internal fun AnActionEvent.reviewPath(): String? {
    binding()?.let { return it.path }
    val project = project ?: return null
    getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(ChangeDiffRequestProducer.CHANGE_KEY)?.let { return ReviewPaths.relative(project, it) }
    getData(VcsDataKeys.CHANGES)?.firstOrNull()?.let { return ReviewPaths.relative(project, it) }
    return null
}
```

Import `com.intellij.openapi.vcs.VcsDataKeys`.

- [ ] **Step 2: Toggle text follows state**

In `ToggleReviewedAction.update`:

```kotlin
override fun update(e: AnActionEvent) {
    val project = e.project
    val path = e.reviewPath()
    e.presentation.isEnabledAndVisible = project != null && path != null
    if (project == null || path == null) return
    val model = ReviewChangesModel.getInstance(project)
    val reviewed = model.find(path)?.let { model.state(it) } == ReviewState.REVIEWED
    e.presentation.text = if (reviewed) "Unmark Reviewed" else "Mark Reviewed"
}
```

- [ ] **Step 3: Edit-comments submenu action**

Add to `DiffActions.kt`:

```kotlin
/** One child per comment of the file under the cursor; opens the editor popup. */
class EditFileCommentsGroup : ActionGroup("Edit Comment", true), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = getChildren(e).isNotEmpty()
    }
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val path = e.reviewPath() ?: return emptyArray()
        val comments = ReviewChangesModel.getInstance(project).commentsFor(path)
        return comments.map { c ->
            object : AnAction("${c.location()}  ${c.text.lineSequence().first().take(60)}"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val anchor = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) as? JComponent ?: return
                    CommentEditorPopup.show(project, anchor, c.type, c.text) { text, type ->
                        ReviewStore.getInstance(project).updateComment(c.id) { it.copy(text = text, type = type) }
                    }
                }
            }
        }.toTypedArray()
    }
}
```

Until Task 6 exists, `ReviewChangesModel.commentsFor(path)` does not exist. For this task use `ReviewStore.getInstance(project).session.commentsFor(path)` and switch in Task 6.

- [ ] **Step 4: Register in plugin.xml**

Inside `AgentReview.DiffActions` after `AgentReview.AddFileComment` add:

```xml
<group id="AgentReview.EditFileComments" class="dev.gipo.agentreview.actions.EditFileCommentsGroup" text="Edit Comment" popup="true"/>
```

Give `AgentReview.AddFileComment` an icon: `icon="AllIcons.FileTypes.Text"`.

In `AgentReview.DiffToolbar` add `<reference ref="AgentReview.AddFileComment"/>` after `AgentReview.AddComment`.

Add a new group for the tree:

```xml
<group id="AgentReview.TreePopup">
    <reference ref="AgentReview.ToggleReviewed"/>
    <reference ref="AgentReview.AddFileComment"/>
    <reference ref="AgentReview.EditFileComments"/>
</group>
```

- [ ] **Step 5: Tree context menu and toolbar button**

In `ReviewToolWindowPanel.kt` replace `SimpleChangesBrowser(project, false, false)` with:

```kotlin
private val browser = object : SimpleChangesBrowser(project, false, false) {
    // Called from the super constructor: no instance state allowed here.
    override fun createPopupMenuActions(): List<AnAction> =
        super.createPopupMenuActions() + Separator.getInstance() + ActionManager.getInstance().getAction("AgentReview.TreePopup")
}
```

In `createToolbar()` after the "Toggle Reviewed" action add `add(ActionManager.getInstance().getAction("AgentReview.AddFileComment"))`. The toolbar's target component is the panel, whose data context includes the tree's `VcsDataKeys.CHANGES` only when the tree has focus. To make it work from the toolbar set `toolbar.targetComponent = browser.viewer`.

- [ ] **Step 6: File-level card above line 1**

In `EditorReviewBinding.render` change the filter and loop:

```kotlin
val comments = session.commentsFor(path)
for (c in comments) {
    val doc = editor.document
    if (c.startLine == null) {
        if (c.side != primarySide && primarySide != Side.NEW) continue
        addInlay(0, c)
        continue
    }
    ...existing line logic, ending with addInlay(doc.getLineEndOffset(endClamped), c)
}
```

and extract:

```kotlin
private fun addInlay(offset: Int, c: Comment) {
    val panel = CommentInlayPanel(project, c) { rerender() }
    val props = EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), null, true, false, 0, offset)
    EditorEmbeddedComponentManager.getInstance().addComponent(editor, panel, props)?.let { inlays += it }
}
```

File-level cards render only on the NEW-side editor (two-side) or the single editor, so the twoside viewer shows one card, not two.

- [ ] **Step 7: Build and test**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git commit -am "File actions in the tree context menu; file comment button and inline card"
```

---

### Task 3: MCP hint in the generated prompt

**Files:**
- Modify: `src/main/kotlin/dev/gipo/agentreview/export/MarkdownExporter.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/settings/AgentReviewSettings.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/settings/AgentReviewConfigurable.kt`
- Test: `src/test/kotlin/dev/gipo/agentreview/MarkdownExporterTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
@Test
fun mentionsMcpToolsWhenEnabled() {
    val out = MarkdownExporter.export(session, ExportOptions(mcpHint = true))
    assertTrue(out, out.contains("agent_review_list_comments"))
    assertTrue(out, out.contains("agent_review_resolve_comment"))
    val silent = MarkdownExporter.export(session, ExportOptions(mcpHint = false))
    assertFalse(silent, silent.contains("agent_review"))
}
```

Existing exact-shape test: pass `ExportOptions(branch = "main", mcpHint = false)`.

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests '*MarkdownExporterTest*'`
Expected: compile error, `mcpHint` unknown.

- [ ] **Step 3: Implement**

`ExportOptions` gets `val mcpHint: Boolean = true` and:

```kotlin
const val MCP_HINT = "If you have the agent_review MCP tools: call agent_review_list_comments for ids, " +
    "fix each item, then agent_review_resolve_comment with a one-line reply. " +
    "Ask with agent_review_add_comment when a comment is unclear. Otherwise reply here with what you changed per item."
```

In `export`, after the scope line and its blank line:

```kotlin
if (options.mcpHint) sb.append(ExportOptions.MCP_HINT).append("\n\n")
```

Settings: `var mentionMcp by property(true)`; `exportOptions` passes `mcpHint = state.mentionMcp`; configurable row `checkBox("Tell the agent to use the MCP tools when available").bindSelected(s::mentionMcp)` in the Export group.

- [ ] **Step 4: Test, commit**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

```bash
git commit -am "Prompt tells the agent to use the MCP tools when available"
```

---

### Task 4: Hide reviewed files toggle

**Files:**
- Modify: `src/main/kotlin/dev/gipo/agentreview/settings/AgentReviewSettings.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/ui/ReviewToolWindowPanel.kt`

- [ ] **Step 1: Setting**

`var hideReviewedFiles by property(false)`.

- [ ] **Step 2: Filtered display**

In the panel add:

```kotlin
private var shown: List<String> = emptyList()

/** Reviewed files drop out when the toggle is on. Stale files stay. */
private fun showChanges() {
    val hide = AgentReviewSettings.getInstance().state.hideReviewedFiles
    val visible = model.changes.filter { !hide || model.state(it) != ReviewState.REVIEWED }
    val paths = visible.map { it.path }
    if (paths == shown) return
    shown = paths
    browser.setChangesToDisplay(visible.map { it.change })
}
```

`changesUpdated` calls `showChanges()` instead of `setChangesToDisplay`, and `refreshUi()` calls `showChanges()` first. Reset `shown = emptyList()` in `changesUpdated` before calling so a refresh always repaints.

Toolbar, after Toggle Reviewed:

```kotlin
add(object : ToggleAction("Hide Reviewed Files", "Show only unreviewed and changed files", AllIcons.Actions.ToggleVisibility), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean = AgentReviewSettings.getInstance().state.hideReviewedFiles
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AgentReviewSettings.getInstance().state.hideReviewedFiles = state
        showChanges()
    }
})
```

- [ ] **Step 3: Test, commit**

Run: `./gradlew test`

```bash
git commit -am "Toolbar toggle hides reviewed files in the tree"
```

---

### Task 5: Bigger notes field

**Files:**
- Modify: `src/main/kotlin/dev/gipo/agentreview/ui/ReviewToolWindowPanel.kt`

- [ ] **Step 1: Splitter between comments and notes**

`private val notes = JBTextArea(6, 20)`. Replace the `bottom` panel with:

```kotlin
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
```

Give the outer splitter a key too: `OnePixelSplitter(true, "Nitpick.treeSplit", 0.5f)`.

- [ ] **Step 2: Test, commit**

Run: `./gradlew test`

```bash
git commit -am "Resizable notes pane"
```

---

### Task 6: Project-wide comments with per-scope placement (issue #5)

**Files:**
- Modify: `src/main/kotlin/dev/gipo/agentreview/model/Model.kt`
- Create: `src/main/kotlin/dev/gipo/agentreview/scope/CommentPlacer.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/store/ReviewStore.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/scope/ScopeChanges.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/scope/ReviewChangesModel.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/diff/EditorReviewBinding.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/actions/DiffActions.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/ui/ReviewToolWindowPanel.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/export/MarkdownExporter.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/export/JsonExporter.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/channels/Channels.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/mcp/AgentReviewToolset.kt`
- Test: `src/test/kotlin/dev/gipo/agentreview/CommentPlacerTest.kt`, `ModelTest.kt`, `MarkdownExporterTest.kt`, `DiffBindingTest.kt`

**Produces:**
- `Comment.contentHash: String?`, `Comment.scopeKey: String`, `Comment.outdated: Boolean`
- `ReviewStorage.comments: List<Comment>`
- `ReviewStore.comments`, `addComment`, `updateComment`, `removeComment`, `removeComments(ids)`, `otherSessions()`
- `ReviewedChange(change, path, hash, beforeHash, content, beforeContent)`
- `ReviewChangesModel.comments(): List<Comment>` (placed), `commentsFor(path)`
- `CommentPlacer.place(...)`, `CommentPlacer.relocate(snippet, content)`
- `MarkdownExporter.export(session, comments, options)`; `JsonExporter.comments(comments, includeResolved)`, `JsonExporter.session(session, comments, includeResolved, branch)`

- [ ] **Step 1: Failing placer tests**

`CommentPlacerTest.kt`:

```kotlin
class CommentPlacerTest {
    private val content = "a\nb\nc\nd\n"

    @Test
    fun relocateFindsUniqueSnippet() {
        assertEquals(2 to 2, CommentPlacer.relocate("b", "a\nb\nc\n"))
        assertEquals(3 to 4, CommentPlacer.relocate("c\nd", "x\nx\nc\nd\n"))
        assertNull(CommentPlacer.relocate("x", "x\nx\n"))       // not unique
        assertNull(CommentPlacer.relocate("zzz", content))       // gone
        assertNull(CommentPlacer.relocate("  ", content))        // blank
    }

    @Test
    fun placeKeepsSameHashRelocatesOrMarksOutdated() {
        val h = ContentHash.of(content)
        val same = Comment(path = "a.kt", startLine = 2, endLine = 2, contentHash = h, snippet = "b")
        val moved = Comment(path = "a.kt", startLine = 9, endLine = 9, contentHash = "old", snippet = "c")
        val gone = Comment(path = "a.kt", startLine = 1, endLine = 1, contentHash = "old", snippet = "zzz")
        val elsewhere = Comment(path = "other.kt", startLine = 1, contentHash = "old", snippet = "a")
        val review = Comment(path = "", text = "n", scopeKey = "k")
        val foreignReview = Comment(path = "", text = "n", scopeKey = "other")
        val placed = CommentPlacer.place(
            listOf(same, moved, gone, elsewhere, review, foreignReview),
            listOf(ReviewedChange(path = "a.kt", hash = h, beforeHash = null, content = content, beforeContent = null)),
            currentKey = "k",
        )
        assertEquals(listOf(same.id, moved.id, gone.id, review.id), placed.map { it.id })
        assertEquals(2, placed[0].startLine)
        assertEquals(3, placed[1].startLine)
        assertFalse(placed[1].outdated)
        assertTrue(placed[2].outdated)
        assertEquals(1, placed[2].startLine)
    }
}
```

`ReviewedChange` needs a test-friendly constructor: make `change: Change?` nullable with default null. The placer only reads path, hashes, contents.

- [ ] **Step 2: Model**

`Comment` new fields (all serializable with defaults):

```kotlin
/** Hash of the commented side's file content at creation. Null: never relocated. */
val contentHash: String? = null,
/** Session key the comment was written in. Only review-level comments are filtered by it. */
val scopeKey: String = "",
/** Runtime only: the commented text is gone from the file in the current scope. */
val outdated: Boolean = false,
```

`ReviewSession.comments` stays as a legacy field, doc `/** Pre-0.2.1. Moved to [ReviewStorage.comments] on load. */`. Remove `commentsFor`. `isEmpty` = `reviewed.isEmpty() && notes.isBlank()`.

`ReviewStorage` gets `val comments: List<Comment> = emptyList()`.

`ReviewedChange` becomes:

```kotlin
data class ReviewedChange(
    val path: String,
    val hash: String?,
    val beforeHash: String?,
    val content: CharSequence?,
    val beforeContent: CharSequence?,
    val change: Change? = null,
)
```

- [ ] **Step 3: Placer**

`scope/CommentPlacer.kt`:

```kotlin
/** Where a comment shows in the current scope. Comments are anchored to text, not to a scope. */
object CommentPlacer {

    fun place(all: List<Comment>, changes: List<ReviewedChange>, currentKey: String): List<Comment> = all.mapNotNull { c ->
        if (c.isReviewLevel) return@mapNotNull c.takeIf { it.scopeKey == currentKey }
        val rc = changes.firstOrNull { it.path == c.path } ?: changes.firstOrNull { ReviewPaths.matches(it.path, c.path) }
            ?: return@mapNotNull null
        val start = c.startLine ?: return@mapNotNull c
        val hash = if (c.side == Side.NEW) rc.hash else rc.beforeHash
        if (c.contentHash == null || c.contentHash == hash) return@mapNotNull c
        val content = (if (c.side == Side.NEW) rc.content else rc.beforeContent) ?: return@mapNotNull c.copy(outdated = true)
        val found = relocate(c.snippet ?: "", content) ?: return@mapNotNull c.copy(outdated = true)
        c.copy(startLine = found.first, endLine = found.second)
    }

    /** 1-based first..last line of the unique occurrence of [snippet] in [content], else null. */
    fun relocate(snippet: String, content: CharSequence): Pair<Int, Int>? {
        val needle = snippet.trimEnd('\n', '\r')
        if (needle.isBlank()) return null
        val text = content.toString().replace("\r\n", "\n")
        val first = text.indexOf(needle)
        if (first < 0 || text.indexOf(needle, first + 1) >= 0) return null
        val startLine = text.substring(0, first).count { it == '\n' } + 1
        val endLine = startLine + needle.count { it == '\n' }
        return startLine to endLine
    }
}
```

- [ ] **Step 4: Store**

`ReviewStore`:

```kotlin
val comments: List<Comment> get() = storage.comments
val currentKey: String get() = storage.currentKey

/** Other sessions' reviewed marks, for hash carry-over. */
fun otherSessions(): List<ReviewSession> = storage.sessions.filterKeys { it != storage.currentKey }.values.toList()
```

`loadState`: after decoding, migrate:

```kotlin
storage = migrate(storage)

private fun migrate(s: ReviewStorage): ReviewStorage {
    if (s.sessions.values.all { it.comments.isEmpty() }) return s
    val moved = s.sessions.flatMap { (key, session) -> session.comments.map { it.copy(scopeKey = key) } }
    return s.copy(
        comments = s.comments + moved.filter { m -> s.comments.none { it.id == m.id } },
        sessions = s.sessions.mapValues { it.value.copy(comments = emptyList()) },
    )
}
```

The legacy single-session branch also goes through `migrate`.

`update` keeps its signature for sessions. Add a storage-level variant:

```kotlin
private fun updateStorage(transform: (ReviewStorage) -> ReviewStorage) {
    synchronized(this) { storage = transform(storage) }
    update { it }
}

fun addComment(comment: Comment) = updateStorage { it.copy(comments = it.comments + comment.copy(scopeKey = it.currentKey)) }
fun updateComment(id: String, transform: (Comment) -> Comment) =
    updateStorage { s -> s.copy(comments = s.comments.map { if (it.id == id) transform(it) else it }) }
fun removeComment(id: String) = removeComments(setOf(id))
fun removeComments(ids: Collection<String>) = updateStorage { s -> s.copy(comments = s.comments.filterNot { it.id in ids }) }

/** Marks and notes of this scope, plus comments written in it. */
fun clear() = updateStorage { s ->
    s.copy(comments = s.comments.filterNot { it.scopeKey == s.currentKey }, sessions = s.sessions + (s.currentKey to ReviewSession(scope = session.scope)))
}
fun clearAll() = updateStorage { s -> ReviewStorage(mapOf(s.currentKey to ReviewSession(scope = session.scope)), s.currentKey) }
```

`getState` keeps `comments` (it is part of storage; `storage.copy(sessions = kept)` already carries it). `sessionCount` unchanged.

- [ ] **Step 5: Changes model**

`ScopeChanges`: replace `afterHash` with

```kotlin
/** Content of one side, null when absent or unreadable. */
fun content(rev: ContentRevision?): CharSequence? = try { rev?.content } catch (e: Exception) { null }
```

`ReviewChangesModel.refresh` builds:

```kotlin
ScopeChanges.collect(project, scope).map {
    val after = ScopeChanges.content(it.afterRevision)
    val before = ScopeChanges.content(it.beforeRevision)
    ReviewedChange(ReviewPaths.relative(project, it), after?.let(ContentHash::of), before?.let(ContentHash::of), after, before, it)
}
```

then, still in the background task, carry marks over:

```kotlin
val store = ReviewStore.getInstance(project)
val mine = store.session.reviewed
val inherited = result.mapNotNull { rc ->
    val hash = rc.hash ?: return@mapNotNull null
    if (mine.containsKey(rc.path)) return@mapNotNull null
    if (store.otherSessions().any { it.reviewed[rc.path] == hash }) rc.path to hash else null
}
if (inherited.isNotEmpty()) store.update { s -> s.copy(reviewed = s.reviewed + inherited) }
```

Add:

```kotlin
/** Comments visible in the current scope, with lines moved to where their text is now. */
fun comments(): List<Comment> {
    val store = ReviewStore.getInstance(project)
    return CommentPlacer.place(store.comments, changes, store.currentKey)
}

fun commentsFor(path: String): List<Comment> = comments().filter { ReviewPaths.matches(it.path, path) }
```

`openDiff` uses `rc.change!!` (in-model changes always have one); `navigate` unchanged. `all.indexOf(rc.change)` → `changes.map { it.change }` unchanged semantics.

- [ ] **Step 6: Comment creation records the hash**

`EditorReviewBinding`:

```kotlin
/** Hash of the side's file as the scope model sees it, else of this editor's document. */
fun contentHash(side: Side): String? {
    val rc = dev.gipo.agentreview.scope.ReviewChangesModel.getInstance(project).find(path)
    return when {
        rc != null -> if (side == Side.NEW) rc.hash else rc.beforeHash
        side == primarySide -> ContentHash.of(editor.document.charsSequence)
        else -> null
    }
}
```

Both `addCommentAt` and `AddCommentAction` pass `contentHash = binding.contentHash(side)`. The render uses `model.commentsFor(path)` and skips `c.outdated`. The binding's listener ignores the `session` argument: `sessionChanged(session) = render()`.

MCP `agent_review_add_comment`: `contentHash = ReviewChangesModel.getInstance(project).find(path)?.hash` when `line > 0`. `agent_review_list_comments` / `get_review` / `status` read `ReviewChangesModel.comments()`; `resolve` checks `store.comments`.

- [ ] **Step 7: UI and exporters read placed comments**

Panel: `refreshUi` fills the list from `model.comments()`, `ReviewDecorator` counts `model.commentsFor(path)`, "Clear Resolved Comments" calls `store.removeComments(model.comments().filter { it.resolved }.map { it.id })`, `open(c)` unchanged. `CommentRenderer`: after the location `if (value.outdated) append("  outdated", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)`. `EditFileCommentsGroup` switches to `model.commentsFor`.

`MarkdownExporter.export(session, comments, options)`: comments come in; `appendItem` writes `` `loc` `` then ` (outdated)` when `c.outdated`. `JsonExporter.comments(comments, includeResolved)` and `session(session, comments, includeResolved, branch)`; the JSON comment gets `put("outdated", c.outdated)`. `ReviewExport` passes `ReviewChangesModel.getInstance(project).comments()`.

Tests: `MarkdownExporterTest` holds `comments` as a list and calls `export(ReviewSession(), comments, options)`; add one outdated comment and assert `(outdated)` appears. `ModelTest.reviewedStateFollowsContentHash` unchanged. `DiffBindingTest`: after `store.addComment`, the binding reads through the model; the file is not in `model.changes` (empty), so `place` drops it. Set the change list in the test via a new internal setter `ReviewChangesModel.setChangesForTest(list)` is over-reach. Instead the placer keeps comments whose path is not in scope when `changes.isEmpty()`? No. Make `commentsFor(path)` fall back: when `find(path) == null`, return `store.comments.filter { matches(path) && !isReviewLevel }` unplaced. That also covers a diff opened outside the tool window (Git log, commit dialog): comments still render at their stored lines. Document it in the function comment.

- [ ] **Step 8: Test**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, `CommentPlacerTest` green.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Comments belong to files, not scopes

Anchored by content hash and snippet. Each scope places them where the
text is now, or lists them as outdated. Reviewed marks carry over by hash."
```

---

### Task 7: Export / import session

**Files:**
- Create: `src/main/kotlin/dev/gipo/agentreview/export/SessionFile.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/store/ReviewStore.kt`
- Modify: `src/main/kotlin/dev/gipo/agentreview/ui/ReviewToolWindowPanel.kt`
- Test: `src/test/kotlin/dev/gipo/agentreview/SessionFileTest.kt`

- [ ] **Step 1: Failing round-trip test**

```kotlin
class SessionFileTest {
    @Test
    fun roundTrip() {
        val session = ReviewSession(scope = Scope(ScopeKind.RANGE, base = "a", head = "b"), reviewed = mapOf("x.kt" to "h"), notes = "n")
        val comments = listOf(Comment(path = "x.kt", startLine = 1, text = "t", scopeKey = session.scope.key()))
        val text = SessionFile.encode(session, comments, branch = "main")
        val back = SessionFile.decode(text)
        assertEquals(1, back.format)
        assertEquals(session, back.session)
        assertEquals(comments, back.comments)
        assertEquals("main", back.branch)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
@Serializable
data class SessionFile(
    val format: Int = 1,
    val plugin: String = "nitpick",
    val branch: String? = null,
    val exportedAt: Long = System.currentTimeMillis(),
    val session: ReviewSession = ReviewSession(),
    val comments: List<Comment> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
        fun encode(session: ReviewSession, comments: List<Comment>, branch: String?): String =
            json.encodeToString(serializer(), SessionFile(branch = branch, session = session, comments = comments))
        fun decode(text: String): SessionFile = json.decodeFromString(serializer(), text)
    }
}
```

Store:

```kotlin
/** Adds the session under its scope key and switches to it. Comments with known ids are replaced. */
fun importSession(session: ReviewSession, comments: List<Comment>) {
    synchronized(this) {
        val ids = comments.map { it.id }.toSet()
        val key = session.scope.key()
        storage = storage.copy(
            sessions = storage.sessions + (key to session),
            comments = storage.comments.filterNot { it.id in ids } + comments,
            currentKey = key,
        )
    }
    update { it }
}
```

Panel toolbar, after the send group separator:

```kotlin
add(object : AnAction("Export Session…", "Save this scope's marks, notes and comments to a JSON file", AllIcons.ToolbarDecorator.Export), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val descriptor = FileSaverDescriptor("Export Review Session", "Marks, notes and comments of ${store.session.scope.describe()}", "json")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(null as VirtualFile?, "nitpick-review.json") ?: return
        val branch = try { ScopeChanges.currentBranch(project) } catch (ex: Exception) { null }
        val comments = model.comments().map { c -> store.comments.first { it.id == c.id } }
        wrapper.file.writeText(SessionFile.encode(store.session, comments, branch))
        Notifications.info(project, "Session exported", wrapper.file.path)
    }
})
add(object : AnAction("Import Session…", "Load a session exported by Nitpick", AllIcons.ToolbarDecorator.Import), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val vf = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("json"), project, null) ?: return
        val file = try { SessionFile.decode(String(vf.contentsToByteArray())) } catch (ex: Exception) {
            Notifications.warn(project, "Not a Nitpick session file", ex.message ?: ""); return
        }
        store.importSession(file.session, file.comments)
        model.refresh()
    }
})
```

Export writes the original (unplaced) comments so lines stay anchored to their own hash.

- [ ] **Step 3: Test, commit**

Run: `./gradlew test`

```bash
git add -A && git commit -m "Export and import a review session"
```

---

### Task 8: Auto refresh

**Files:**
- Modify: `src/main/kotlin/dev/gipo/agentreview/ui/ReviewToolWindowPanel.kt`

- [ ] **Step 1: Debounced refresh on VCS events**

In the panel `init`, after the existing bus subscriptions:

```kotlin
val autoRefresh = SingleAlarm({ model.refresh() }, 1000, this, Alarm.ThreadToUse.SWING_THREAD, ModalityState.nonModal())
bus.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
    override fun changeListUpdateDone() = autoRefresh.cancelAndRequest()
})
bus.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { autoRefresh.cancelAndRequest() })
```

Imports: `com.intellij.util.SingleAlarm`, `com.intellij.util.Alarm`, `com.intellij.openapi.vcs.changes.ChangeListListener`, `git4idea.repo.GitRepository`, `git4idea.repo.GitRepositoryChangeListener`.

`model.refresh()` already coalesces overlapping runs.

- [ ] **Step 2: Test, commit**

Run: `./gradlew test`

```bash
git commit -am "Refresh the tree when the working tree or repository changes"
```

---

### Task 9: Docs, version, verify, PR

**Files:**
- Modify: `build.gradle.kts` (version 0.2.1)
- Modify: `src/main/resources/META-INF/plugin.xml` (change notes)
- Modify: `README.md`, `CLAUDE.md`

- [ ] **Step 1: Change notes 0.2.1**

```html
<h3>0.2.1</h3>
<ul>
    <li>Comments belong to files. They follow the commented text across scopes and commits, or show as outdated.</li>
    <li>Reviewed marks carry over between scopes when the file content matches.</li>
    <li>Right-click a file in the tree: mark reviewed, add or edit comments. File comment button in the diff toolbar.</li>
    <li>Hide Reviewed Files toggle. Resizable notes pane. Export and import a session.</li>
    <li>The prompt tells the agent to use the MCP tools when available.</li>
    <li>Auto-mark is one setting: off, on open, on close. The tree refreshes on VCS changes.</li>
</ul>
```

- [ ] **Step 2: README**

Update the Reviewing section (tree context menu, hide reviewed, notes pane, file comment toolbar), the Persistence section (comments are project-wide, anchored to text, outdated, export/import), the header toggles paragraph (Auto-Mark Reviewed dropdown), the Markdown format sample (MCP paragraph), Settings list. Add to CLAUDE.md data model: "Comments are project-wide in `ReviewStorage.comments`; `CommentPlacer` places them per scope. Sessions hold marks and notes."

- [ ] **Step 3: Verify**

Run: `./gradlew test verifyPlugin`
Expected: tests pass, verifier reports Compatible.

- [ ] **Step 4: Branch, commit, PR**

```bash
git checkout -b review-persistence
git commit -am "Bump to 0.2.1, document 0.2.1 changes"
git push -u origin review-persistence
gh pr create --base main --title "0.2.1: file-anchored comments, tree actions, session export" --body "..."
```

The PR body lists the closed issues: `Closes #2, #3, #4, #5, #6`.
