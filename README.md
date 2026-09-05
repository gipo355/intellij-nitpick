# Nitpick

Review code written by AI agents inside IntelliJ, then hand the review back
to the agent.

Nitpick turns the IDE's diff viewer into a review desk: comment on lines like
on a pull request, mark files as reviewed, and send the result to whatever
agent wrote the code. The agent fixes things and resolves the comments; only
files it touched again come back to you.

Works with Claude Code, Codex, OpenCode, Pi, GitHub Copilot Chat, JetBrains
AI Assistant, and anything that speaks MCP. It is the IDE-native sibling of
[tuicr](https://tuicr.dev) and [hunk](https://github.com/modem-dev/hunk),
with the same Markdown hand-off format.

## Features

- **Review any scope**: uncommitted, staged, unstaged, a stash, a commit, a
  commit range, or everything since a branch's merge-base. Right-click commits
  in Git Log to review them.
- **Branch mode**: no diff needed. List the whole tree (or one folder), open
  files in their editor and annotate them there. Plan a refactor on `main` and
  hand the notes to the agent.
- **Inline comments**: hover a line and click `+`, or press `Alt+Shift+C`.
  Types: note, issue, question, nit, praise. Cards render under the code with
  Edit, Resolve, Delete.
- **Reviewed marks keyed by content**: a file the agent edits again shows as
  *changed*, not reviewed. `Next Unreviewed` walks you through the rest.
- **Comments follow the text**: they stay attached across scopes and commits,
  relocate when lines move, and show as *outdated* when the text is gone.
- **Send the review anywhere**: clipboard, IDE terminal, a file, Copilot Chat,
  AI Assistant, or live over MCP where the agent pulls comments, resolves them
  with a reply, and can ask questions back.
- **Stays out of the way**: one toolbar toggle hides every card, button and
  shortcut Nitpick adds to editors and diffs.

## Install

Settings | Plugins | Marketplace, search **Nitpick**. Requires IntelliJ
2026.2+.

From source: `./gradlew buildPlugin`, then Settings | Plugins | ⚙ |
Install Plugin from Disk… with `build/distributions/nitpick-<version>.zip`.

## Quick start

1. Let the agent edit your working tree.
2. Open the **Nitpick** tool window (right sidebar) and pick a scope.
3. Walk the files: comment, mark reviewed, move on.
4. Click **Send Review To** and pick a channel, or let the agent pull it over MCP.
5. The agent fixes and resolves. Re-check only the files marked *changed*.

### Keys

| Action | Shortcut |
|---|---|
| Comment line or selection | `Alt+Shift+C` |
| Comment whole file | `Alt+Shift+F` |
| Toggle file reviewed | `Alt+Shift+R` (or `Space` in the tree) |
| Next / previous unreviewed file | `Alt+Shift+N` / `Alt+Shift+P` |
| Copy review as Markdown | `Alt+Shift+Y` |

## Scopes

| Scope | Compares |
|---|---|
| Uncommitted | working tree vs HEAD, plus untracked files |
| Staged / Unstaged | index vs HEAD / working tree vs index |
| Compare with Branch… | HEAD vs the merge-base with a branch, what a PR shows |
| Commit Range… | `base..head`, or `base...head` for merge-base |
| Single Commit… | one commit vs its parent |
| Stash… | a stash vs the commit it was taken on |
| Current Branch | no diff: the whole tree or one folder, annotated in the editor |

Each scope keeps its own session of reviewed marks and notes. Comments belong
to files, so every scope containing the file shows them.

## Getting the review to the agent

| Channel | How |
|---|---|
| Clipboard | tuicr-compatible Markdown, paste anywhere |
| Terminal | pastes into the IDE terminal tab running claude, codex, opencode or pi |
| File | writes `.agent-review/REVIEW.md` and `REVIEW.json` |
| Copilot Chat / AI Assistant | opens a new chat with the review as the prompt |
| MCP | the agent pulls, resolves and replies live; works from tmux or anywhere |

The Markdown looks like this:

```
I reviewed your code and have the following comments. Please address them.

Scope: uncommitted changes on `main`

1. **[ISSUE]** `src/auth.rs:42` - Magic number should be a named constant
   ```rust
   let timeout = 3000;
   ```
2. `src/auth.rs:50-55` - This block could be refactored
3. `src/old.rs:~12` - why was this removed?
```

`~` marks the old side of the diff. Intro sentence, snippets and the MCP hint
are settings.

### MCP

Enable Settings | Tools | MCP Server. Nitpick adds these tools:

| Tool | Does |
|---|---|
| `agent_review_get_review(format)` | the whole review as Markdown or JSON |
| `agent_review_list_comments(include_resolved)` | comments with ids |
| `agent_review_resolve_comment(id, reply)` | mark done; the reply shows in the IDE |
| `agent_review_add_comment(path, text, line, end_line, type)` | ask the reviewer something |
| `agent_review_status()` | per-file reviewed / stale / unreviewed |

The server is plain HTTP on localhost, so agents outside the IDE connect the
same way. The settings page shows the URL and a token if restricted mode is on.

```bash
# Claude Code
claude mcp add --transport sse jetbrains http://127.0.0.1:64342/sse
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

A line for your agent's instructions file:

> When I say "address the review", call `agent_review_get_review` (format
> json), fix each comment, then `agent_review_resolve_comment` with a one-line
> reply. If a comment is unclear, ask with `agent_review_add_comment` instead
> of guessing.

## Settings

Settings | Tools | Nitpick: intro sentence, snippets, resolved comments in
exports, review file path, terminal tab pattern, auto-mark reviewed, open diff
on single click. The tool window header has the same toggles.

State lives in `.idea/workspace.xml`, per user, never committed. Nothing else
touches disk unless you write the review file or export a session.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for building, testing and releasing.

## License

[MIT](LICENSE)
