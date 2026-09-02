# Agent Review — IntelliJ plugin

Review agent-authored changes in IntelliJ's diff viewer. Leave inline comments,
mark files reviewed, then hand the review to any coding agent: Claude Code,
Codex, OpenCode, Pi, GitHub Copilot Chat, JetBrains AI Assistant, or anything
that speaks MCP.

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

## Workflow

1. Open the **Agent Review** tool window (right side).
2. Pick a scope from the toolbar combo:
   - uncommitted (default), staged, unstaged;
   - *Compare with Branch…* picks a branch and reviews everything on HEAD since
     the merge-base, like a pull request;
   - *Commit Range…* takes `base..head` (or `base...head` for merge-base);
   - *Single Commit…*.

   Or from Git Log: right-click one commit → *Review Commit with Agent Review*.
   Ctrl+click several commits → *Review Range of N Commits* (oldest vs newest,
   same as the log's *Compare Versions*).
3. Double-click a file to open its diff. In the diff:

   | Shortcut | Action |
   |---|---|
   | `Alt+Shift+C` | Add comment on caret line or selection |
   | `Alt+Shift+F` | Add file-level comment |
   | `Alt+Shift+R` | Toggle file reviewed |
   | `Alt+Shift+N` | Open next unreviewed file |
   | `Alt+Shift+Y` | Copy review Markdown to clipboard |

   Hover a line and click the **+** in the gutter to comment it. The diff header
   toolbar also has Mark Reviewed, Add Comment, Next Unreviewed and Send.
   Comments render inline under the line with Edit / Resolve / Delete. Comment
   types: note, issue, question, nit, praise.
4. In the tool window, `Space` toggles reviewed on the selected file. The
   Commit tool window also gets a ✓ hover icon per file.
5. Send the review (toolbar or the *Send Review To* group in the diff popup).

Reviewed marks are keyed by content hash. If the agent edits a reviewed file
again it shows as *⟳ changed* and *Next Unreviewed* picks it up.

## Getting the review to the agent

| Channel | How |
|---|---|
| Clipboard | Markdown, tuicr-compatible. Paste anywhere. |
| Terminal | Pastes into an IDE terminal tab (bracketed paste, so newlines don't submit). Tab chosen by regex in settings, running-command tabs preferred. Works with `claude`, `codex`, `opencode`, `pi`. |
| File | Writes `.agent-review/REVIEW.md` and `REVIEW.json`. Tell the agent to read it. |
| GitHub Copilot Chat | Opens a new chat with the review as the prompt. |
| JetBrains AI Assistant | Opens a new chat with the input pre-filled (internal API, clipboard fallback). ACP agents hosted in AI Assistant get it the same way. |
| MCP | Tools on IntelliJ's built-in MCP server (Settings \| Tools \| MCP Server). |

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

`~` marks a line on the old side of the diff.

### MCP tools

Enable the IDE MCP server and connect your agent to it (the settings page has
one-click config for Claude Code, Cursor, VS Code; others take the SSE URL).

- `agent_review_get_review(format)` — Markdown or JSON of the whole review.
- `agent_review_list_comments(include_resolved)` — comments with ids.
- `agent_review_resolve_comment(id, reply)` — mark done; the reply shows in the IDE.
- `agent_review_add_comment(path, text, line, end_line, type)` — ask the reviewer something.
- `agent_review_status()` — per-file reviewed / stale / unreviewed and open counts.

Suggested line for your agent's instructions file (CLAUDE.md, AGENTS.md…):

> When I say "address the review", call `agent_review_get_review` (format json),
> fix each comment, then `agent_review_resolve_comment` with a one-line reply.
> If a comment is unclear, `agent_review_add_comment` with a question instead of guessing.

Without MCP, the same instruction works with the file channel: "read
`.agent-review/REVIEW.md` and address every item".

## Settings

Settings | Tools | Agent Review: intro sentence, snippets on/off and max lines,
include resolved comments, review file path, terminal tab regex, auto-submit,
auto-mark reviewed when a diff is opened or closed.

## Persistence

The session lives in `.idea/workspace.xml` (per user, not committed). *Clear
Session* wipes it.

## Contributing

See `CONTRIBUTING.md` for building, installing locally, and releasing.

## Development

```bash
./gradlew test        # unit + platform tests
./gradlew runIde
```

Design: `docs/superpowers/specs/2026-09-02-agent-review-design.md`.
