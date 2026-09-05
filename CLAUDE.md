# CLAUDE.md

IntelliJ plugin "Nitpick" (id `dev.gipo.nitpick`; Kotlin packages and action ids still say `agentreview`). Review agent-authored
diffs inline, mark files reviewed, hand the review to any agent.

## Build

- `./gradlew test` (unit + headless platform tests), `./gradlew buildPlugin`,
  `./gradlew verifyPlugin` (must stay "Compatible", no internal API usages).
- Compiles against the local Toolbox IDE in `gradle.properties` (`ideaHome`),
  IU 262 (2026.2). IntelliJ Platform Gradle Plugin 2.18.1, Kotlin 2.3, JDK 21.
- First Gradle run and `runIde` take minutes. Run them in the background.
- Sandbox log: `.intellijPlatform/sandbox/nitpick/IU-*/log/idea.log`.
  User's real IDE log: `~/.cache/JetBrains/IntelliJIdea2026.2/log/idea.log`.
- The user installs the zip from disk into the real IDE. Descriptor has
  `require-restart="true"`.
- Commits on main use Conventional Commits; release-please turns them into
  the version bump, CHANGELOG.md and the GitHub release. Never edit
  `version` by hand.

## Descriptor layout (do not regress)

- `META-INF/plugin.xml` uses the new format: `package=`, `<dependencies>`,
  `<content>` modules. No `<depends>`.
- Optional parts are content modules with their own package and descriptor at
  the resources root: `dev.gipo.agentreview.terminal.xml` (package
  `dev.gipo.agentreview.terminal`, needs `intellij.terminal.frontend`) and
  `dev.gipo.agentreview.mcp.xml` (package `dev.gipo.agentreview.mcp`).
  Classes of a content module MUST live in that package.
- `<dependencies><module>` inside an old-style `<depends config-file>`
  sub-descriptor is silently ignored. That is why content modules are used.

## Platform facts learned the hard way

- `ReviewStore.update` must publish on the EDT. MCP tool calls arrive on
  coroutine threads and listeners touch editors (inlays assert EDT).
- `DiffExtension.onViewerCreated` fires before the viewer's first rediff. The
  unified viewer's document is empty then. Render on `DiffViewerListener.onAfterRediff`.
- 2026.2 default terminal is the reworked one: tabs come from
  `TerminalToolWindowTabsManager`, not `TerminalToolWindowManager.terminalWidgets`
  (always empty). Send text with `TerminalView.createSendTextBuilder()`.
- MCP toolset (`McpToolset`, `@McpTool` suspend funs): return types must be
  objects (`@Serializable data class`), never a bare `List` or the server logs an
  error and the tool breaks. Project comes from `coroutineContext.project`.
- Combined ("continuous") diff viewer: builds its toolbar from
  `DiffUserDataKeys.CONTEXT_ACTIONS` on the context, not from `Diff.ViewerToolbar`.
  It preloads many file blocks, so per-viewer "opened" hooks fire for files the
  user never looked at. Use focus events there.
- The preview tab (`TreeHandlerEditorDiffPreview`) follows tree selection once
  open. `GatedPreviewHandler` in the tool window gates that behind the
  single-click setting; explicit opens re-fire the selection.
- `PluginManagerCore.getPlugin` / `PluginManager.findEnabledPlugin` are internal.
  Reach another plugin's classes through one of its registered actions'
  class loader. AI Assistant's facade lives in a content module, so use a chat
  action (`AIAssistantAddToChatAction`) as the anchor.
- `GitContentRevision` with revision `:0` reads the index (`git cat-file :0:path`).
- `GitChangeUtils.getDiffWithWorkingDir` diffs HEAD vs working tree even for
  staged paths. Do not use it for the staged scope.
- `BaseState.enum()` is inline bytecode built for a newer JVM target and fails
  to compile against JDK 21. Store enums as a `string()` property.
- `SimpleChangesBrowser.createPopupMenuActions` runs inside the super
  constructor. An override must not touch instance state.

## Data model

- One `ReviewSession` per scope key (`Scope.key()`), all in `ReviewStorage`,
  persisted as JSON in workspace.xml via `ReviewStore`. Legacy single-session
  JSON (no `sessions` key) still loads.
- Comments are project-wide in `ReviewStorage.comments`, anchored by path,
  side, line, `contentHash` and `snippet`. `CommentPlacer` places them per
  scope (same line, relocated by unique snippet match, or `outdated`). Read
  them through `ReviewChangesModel.comments()`, never from sessions.
  Pre-0.2.1 per-session comments are migrated on load.
- Sessions hold scope, reviewed marks and notes. Marks carry over between
  sessions by hash at refresh.
- Reviewed marks are `path -> content hash` of the NEW side. Empty string means
  "reviewed, hash unknown" and never goes stale.
- Paths are project-relative; `ReviewPaths.matches` tolerates differing roots.
- `ScopeKind.BRANCH` has no diff: `ScopeChanges.branchTree` lists project
  files from `ProjectFileIndex` as `Change(null, CurrentContentRevision)`.
  Session key `branch:<name>[@folder/]`, `base` = HEAD when the plan started.
- `ReviewedChange` is lazy: hash and content load on first use. Diff scopes
  prime everything in `refresh` (off EDT); the branch tree primes only files
  with a mark or comment. `state()` never reads a hash for an unmarked file.
  `ReviewChangesModel.comments()` is memoized on (changes version, store version).
- Branch mode binds `EditorReviewBinding` to regular editors through
  `BranchEditorBinder` (editorFactoryListener) and removes them on scope
  change. Clicking a tree file opens the source, not the preview diff.
  Saves reach the model through `VFS_CHANGES` (hash invalidation), file
  create/delete/rename schedule a refresh; changelist updates are ignored
  for BRANCH, RANGE and COMMIT.
- Design notes and the perf analysis behind this: `docs/BRANCH_MODE.md`.

## User preferences seen in this project

- Short sentences, no corporate tone. Ships fast, tests in the real IDE and
  reports back with screenshots. Wants quick toolbar buttons over menus.
- Agent-agnostic is a hard requirement: claude, codex, opencode, pi, Copilot,
  AI Assistant. Agents run in tmux, so MCP is the primary live channel.
