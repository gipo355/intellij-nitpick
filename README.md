# Agent Review — IntelliJ plugin

Review changes written by AI agents inside IntelliJ's diff viewer. Leave inline
comments, mark files reviewed, then hand the review to any coding agent:
Claude Code, Codex, OpenCode, Pi, GitHub Copilot Chat, JetBrains AI Assistant,
or anything that speaks MCP.

Inspired by [tuicr](https://tuicr.dev), [hunk](https://github.com/modem-dev/hunk)
and Bitbucket Integration Pro, but built on IntelliJ's own diff engine.

## Install

From JetBrains Marketplace: Settings | Plugins | Marketplace, search
"Agent Review".

From source:

```bash
./gradlew buildPlugin        # build/distributions/agent-review-<version>.zip
```

Settings | Plugins | ⚙ | Install Plugin from Disk… → the zip, restart.
Requires IntelliJ 2026.2+ (build 262).

## The loop

1. The agent edits your working tree or lands commits.
2. Open the **Agent Review** tool window (right sidebar), pick a scope.
3. Walk the files. Comment, mark reviewed, move on.
4. Send the review, or let the agent pull it over MCP.
5. The agent fixes things and resolves comments. Reviewed files it touched
   again show as *changed*, so you re-check only those.

## Scopes

The toolbar combo picks what you review. Each scope keeps its own session of
comments and reviewed marks, so switching back and forth loses nothing.

| Scope | What it compares |
|---|---|
| Uncommitted | working tree vs HEAD, plus untracked files |
| Staged | index vs HEAD |
| Unstaged | working tree vs index |
| Compare with Branch… | HEAD vs the merge-base with a branch you pick from a searchable list. This is what a pull request shows. |
| Commit Range… | `base..head`, or `base...head` for merge-base |
| Single Commit… | one commit vs its parent |

From Git Log: right-click a commit → *Review Commit with Agent Review*.
Ctrl+click several commits → *Review Range of N Commits*, oldest vs newest,
same semantics as the log's own *Compare Versions*.

## Reviewing

Double-click a file (or Enter) to open it in the **Agent Review** diff tab. One
tab is reused for the whole review.

In the diff:

| Action | Where |
|---|---|
| Comment a line or selection | hover the line and click **+** in the gutter, `Alt+Shift+C`, or the toolbar |
| Comment the whole file | `Alt+Shift+F` |
| Toggle file reviewed | `Alt+Shift+R` or the ✓ toolbar button |
| Next / previous unreviewed file | `Alt+Shift+N` / `Alt+Shift+P` or the arrows |
| Copy review Markdown | `Alt+Shift+Y` |
| Send review | the upload button opens the *Send Review To* menu |

The comment editor has a type picker (note, issue, question, nit, praise),
Ctrl+Enter saves, Esc cancels. Comments render as cards under the line with a
colored bar per type, and Edit / Resolve / Delete links. Resolved comments turn
green and show the agent's reply when it resolved them.

In the tool window:

- **Tree**: ✓ reviewed, *⟳ changed* when the file changed since you marked it,
  `N ✎` open comments. `Space` toggles reviewed. The Commit tool window also
  gets a ✓ hover icon on every file row, and *Review Uncommitted Changes* in
  its context menu.
- **Comments list**: Enter or double-click opens the diff at the line, or the
  file itself when it is outside the current scope. `R` resolves, `Delete`
  removes. Right-click for Open in Diff, Edit, Resolve, Copy Location, Delete.
- **Notes**: free text for the whole review, exported at the end.
- **Status line**: files, reviewed, stale, open comments, scope, other saved
  sessions.

Toolbar cleanup: *Reset Reviewed Marks* (keeps comments), *Clear Resolved
Comments*, *Clear Session* (this scope), *Clear All Sessions* (every scope of
the project), *Forget Other Sessions*.

Header quick toggles (also in the ⋮ menu):

- **Open Diff on Single Click**: the diff tab follows the tree selection. Off
  means only double-click, Enter and the unreviewed arrows change it.
- **Mark Reviewed When Diff Opens** / **Closes**: hands-free marking as you walk
  the files. In continuous mode a file counts as opened when you click into its
  block.
- **Continuous Diff**: all files in one scrollable view instead of one file per
  tab. IDE-wide setting, shared with the Commit tool window.

## Getting the review to the agent

| Channel | How | When |
|---|---|---|
| Clipboard | Markdown, tuicr-compatible. Paste anywhere. | always works |
| Terminal | pastes into an IDE terminal tab with bracketed paste; prefers a tab running claude, codex, opencode, pi | agent runs inside the IDE terminal |
| File | writes `.agent-review/REVIEW.md` and `REVIEW.json` | tell the agent to read it |
| GitHub Copilot Chat | opens a new chat with the review as the prompt | Copilot plugin installed |
| JetBrains AI Assistant | opens a new chat with the input pre-filled, clipboard fallback | AI Assistant installed |
| MCP | tools on IntelliJ's built-in MCP server; the agent pulls comments, resolves them, asks back | agents in tmux or anywhere, best channel |

### Markdown format

```
I reviewed your code and have the following comments. Please address them.

Scope: uncommitted changes on `main`

1. **[ISSUE]** `src/auth.rs:42` - Magic number should be a named constant
   ```rust
   let timeout = 3000;
   ```
2. `src/auth.rs:50-55` - This block could be refactored
3. `src/old.rs:~12` - why was this removed?

Review notes:
- overall fine, ship after fixes
```

`~` marks a line on the old side of the diff. Snippets, intro sentence and
resolved-comment inclusion are settings.

### MCP

Enable Settings | Tools | MCP Server. The plugin adds five tools:

- `agent_review_get_review(format)` — Markdown or JSON of the whole review.
- `agent_review_list_comments(include_resolved)` — comments with ids.
- `agent_review_resolve_comment(id, reply)` — mark done; the reply shows in the IDE.
- `agent_review_add_comment(path, text, line, end_line, type)` — ask the reviewer something.
- `agent_review_status()` — per-file reviewed / stale / unreviewed and open counts.

The server is plain HTTP on localhost, so agents running outside the IDE
(tmux, another terminal, Codex, OpenCode) connect the same way. The settings
page shows the SSE URL (`http://127.0.0.1:<port>/sse`), the streamable URL, and
an auth token if restricted mode is on.

```bash
# Claude Code
claude mcp add --transport sse jetbrains http://127.0.0.1:64342/sse
# with a token: add --header "Authorization: Bearer <token>"
```

```toml
# Codex: ~/.codex/config.toml
[mcp_servers.jetbrains]
url = "http://127.0.0.1:64342/sse"
```

```jsonc
// OpenCode: opencode.json
{ "mcp": { "jetbrains": { "type": "remote", "url": "http://127.0.0.1:64342/sse", "enabled": true } } }
```

Suggested line for your agent's instructions file (CLAUDE.md, AGENTS.md…):

> When I say "address the review", call `agent_review_get_review` (format json),
> fix each comment, then `agent_review_resolve_comment` with a one-line reply.
> If a comment is unclear, `agent_review_add_comment` with a question instead of guessing.

Without MCP, the same instruction works with the file channel: "read
`.agent-review/REVIEW.md` and address every item".

## Settings

Settings | Tools | Agent Review: intro sentence, snippets on/off and max lines,
include resolved comments, review file path, terminal tab regex, auto-submit,
mark reviewed on open / close, open diff on single click. The header toggles
write the same values.

## Persistence

Sessions live in `.idea/workspace.xml`, per user, never committed. One session
per scope, the 30 most recent are kept. Nothing else touches disk unless you
use *Write Review File*.

## Contributing

See `CONTRIBUTING.md` for building, installing locally, and releasing.
Design: `docs/superpowers/specs/2026-09-02-agent-review-design.md`.
