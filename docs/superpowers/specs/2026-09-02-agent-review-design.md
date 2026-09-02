# Nitpick — IntelliJ plugin design

Date: 2026-09-02. Status: approved by autonomy grant ("you are on your own").

## Goal

Review agent-authored changes inside IntelliJ's diff viewer, annotate them, mark
files reviewed, and hand the result back to any coding agent.

Reference tools: tuicr (clipboard Markdown, reviewed flags), hunk (agent pulls
comments via CLI), Bitbucket Integration Pro (inline comments in diff, per-file
reviewed checkbox, MCP tools).

## Non-goals

- Forge integration (GitHub/GitLab PR submit). IntelliJ already has it.
- Custom diff rendering. IntelliJ's diff viewer stays as is.
- A chat UI.

## Research facts that shape the design

- Claude Code JetBrains plugin exposes no prompt-injection API. The CLI is an MCP
  client of the IDE; only `at_mentioned`/`selection_changed` flow IDE→CLI.
  Terminal keystroke injection is the only push route. It is agent-agnostic.
- IntelliJ 2025.2+ bundles an MCP server (`com.intellij.mcpServer`) with a public
  extension point `mcpServer.mcpToolset`. Any MCP-capable agent (claude, codex,
  opencode, pi, Copilot, AI Assistant) can pull the review through it.
- GitHub Copilot exposes a public `CopilotChatService.query { withInput() }`.
- AI Assistant only has internal `AIAContainerPanelFacade.setChatText(String)`.
- ACP platform plugin has no prompt API.

## Architecture

```
ReviewStore (project service, persisted in workspace.xml)
   ├── ReviewSession { scope, comments[], reviewed{path→hash} }
   └── listeners (ReviewTopic on MessageBus)
        │
        ├── Tool window "Nitpick": scope selector, changes tree with
        │   reviewed/comment decorations, comment list, export toolbar
        ├── DiffExtension: comment inlays + gutter marks + actions in every
        │   diff viewer whose content resolves to a project file
        ├── Exporters: Markdown, JSON
        └── Channels: clipboard, terminal, file, Copilot, AI Assistant, MCP toolset
```

## Data model

```kotlin
enum class ScopeKind { UNCOMMITTED, STAGED, UNSTAGED, RANGE, COMMIT }
data class Scope(kind, base: String?, head: String?)   // RANGE: base..head; COMMIT: head
enum class Side { OLD, NEW }
enum class CommentType { NOTE, ISSUE, QUESTION, NIT, PRAISE }
data class Comment(id, path, side, startLine?, endLine?, type, text,
                   snippet: String?, createdAt, resolved: Boolean)
data class ReviewSession(scope, comments, reviewed: Map<path, contentHash>, notes: String)
```

Paths are project-relative with `/` separators. Lines are 1-based. A file-level
comment has null lines. A review-level note has empty path.

`reviewed[path]` stores a hash of the NEW content at the time of marking. If the
agent edits the file again the hash differs, the file becomes unreviewed, and
"next unreviewed" surfaces it. Same trick as tuicr `content_hash`.

## Scope → changes

| Kind | Source |
|---|---|
| UNCOMMITTED | `ChangeListManager.getInstance(p).allChanges` + unversioned files |
| STAGED / UNSTAGED | `GitChangeUtils` index vs HEAD / working tree vs index |
| RANGE | `GitChangeUtils.getDiff(repo, base, head)` |
| COMMIT | `GitChangeUtils.getDiff(repo, "$head~1", head)` |

Default scope is UNCOMMITTED. A Git Log context action opens a COMMIT scope.

## Diff integration

`DiffExtension.onViewerCreated` handles `TwosideTextDiffViewer`,
`UnifiedDiffViewer`, `SimpleOnesideDiffViewer`. For each editor it resolves the
project-relative path and `Side` from the request contents (`DiffContent` with
`VirtualFile`, or `FilePath` user data). It installs:

- Block inlays (`EditorEmbeddedComponentManager`) rendering each comment under its
  end line: type badge, text, edit/delete/resolve buttons.
- Gutter icon on commented lines.
- Actions (also in the diff toolbar and editor popup):
  - Add Review Comment (on caret/selection) — `Alt+Shift+C`.
  - Add File Comment — `Alt+Shift+F`.
  - Toggle File Reviewed — `Alt+Shift+R`.
  - Next Unreviewed File — `Alt+Shift+N`.
  - Copy Review to Clipboard — `Alt+Shift+Y`.

Comment editor: a small popup with a text area and type combo. Enter+Ctrl saves.
Selected text of the range is stored as `snippet`.

## Tool window

- Toolbar: scope combo, base ref field for RANGE, refresh, next unreviewed,
  export group (Copy, Send to Terminal, Send to Copilot, Send to AI Assistant,
  Write File), Clear session, Settings.
- Changes tree (`ChangesTree` with grouping): each node shows reviewed ✓ and
  comment count; stale reviewed files show ⟳. Double-click opens the diff for the
  node using the same DiffRequestChain as the Commit tool window so the
  DiffExtension applies. Space toggles reviewed.
- Comment list: all comments in path/line order; double-click opens the diff at
  the line; right-click resolve/delete.
- Status line: `12 files · 7 reviewed · 3 comments`.

## Export

Markdown, tuicr-compatible:

```
I reviewed your code and have the following comments. Please address them.

Scope: uncommitted changes on `main`

1. **[ISSUE]** `src/auth.rs:42` - Magic number should be a named constant
   ```rust
   let timeout = 3000;
   ```
2. `src/auth.rs:50-55` - This block could be refactored
3. `src/auth.rs` - Consider adding unit tests
4. `src/old.rs:~12` - (old side) why was this removed?

Review notes: overall looks fine, ship after fixes.
```

Snippets are included when the setting is on (default on, max 12 lines).
Resolved comments are omitted unless "include resolved" is on.

JSON export mirrors tuicr's `review comments` shape:
`{id, path, location, side, start_line, end_line, type, text, resolved}`.

## Channels

| Channel | Mechanism | Agents |
|---|---|---|
| Clipboard | `CopyPasteManager` | all |
| Terminal | find terminal tab (setting: name regex, default last focused), `TtyConnector.write` with bracketed paste, optional `\r` submit | claude, codex, opencode, pi, any TUI |
| File | write to `<project>/.agent-review/REVIEW.md` (path setting), refresh VFS | any file-reading agent |
| Copilot | reflection on `com.github.copilot.api.CopilotChatService` | Copilot Chat |
| AI Assistant | reflection: `AIAssistant.ToolWindow.ShowOrFocus` then `AIAContentFacade.getPanel().setChatText` ; clipboard fallback | AI Assistant, ACP agents hosted in it |
| MCP | toolset `agent_review_*` on bundled MCP server | any MCP client |

MCP tools:

- `agent_review_get_review(format: "markdown"|"json")` — current session.
- `agent_review_list_comments(includeResolved)` — JSON list.
- `agent_review_resolve_comment(id, reply?)` — agent marks done; reply shown in UI.
- `agent_review_add_comment(path, line?, endLine?, text, type?)` — agent asks the
  human a question; appears in the tool window with author `agent`.

Reflection channels are optional `<depends optional="true">` and degrade to
clipboard with a notification.

## Persistence

`ReviewStore` is a `PersistentStateComponent` stored in `workspace.xml`
(`StoragePathMacros.WORKSPACE_FILE`), so it is per user and never committed.
One session per project. "Clear session" wipes it. Session survives IDE restart.

## Settings (`Settings | Tools | Agent Review`)

- Terminal tab name pattern (default `.*`, last focused wins), auto-submit.
- Export file path. Include snippets. Snippet max lines. Include resolved.
- Intro sentence text.
- Auto-mark file reviewed when its diff is closed (off by default).

## Testing

- Unit: Markdown/JSON exporters, content hash, stale detection, path
  normalization, terminal payload (bracketed paste framing).
- Platform (`BasePlatformTestCase`): ReviewStore persistence round trip, comment
  CRUD + listeners.
- Manual via `runIde`: diff inlays, tree decorations, channels.

## Build

IntelliJ Platform Gradle Plugin 2.18.1, Kotlin 2.3, JDK 21 toolchain, compiled
against the local IU 262.9437.185. `since-build=262`.
