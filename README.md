# Nitpick — IntelliJ plugin

Coding agents write a lot of code, fast. Reading it is now the job. `git diff`
in a terminal was never built for that: no place to leave a note, no memory of
what you already looked at, and no way to get your objections back into the
agent's context except retyping them.

Nitpick turns IntelliJ's diff viewer into a review desk for agent output.
Comment a line the way you would on a pull request, mark files as reviewed,
and let the agent pick the review up: paste it into its terminal, drop it in a
file, or let it pull comments live over MCP and resolve them one by one while
you watch the cards turn green.

It is the IDE-native answer to [tuicr](https://tuicr.dev) and
[hunk](https://github.com/modem-dev/hunk). Same idea, same Markdown hand-off
format, but with IntelliJ's diff engine, syntax highlighting, navigation and
Git integration underneath instead of a TUI. Works with Claude Code, Codex,
OpenCode, Pi, GitHub Copilot Chat, JetBrains AI Assistant, and anything else
that speaks MCP.

Why review at all when the agent could just be trusted? Because reviewed code
is code you understand. Nitpick keeps that loop short: agent writes, you read
and annotate in the place you already read code, agent fixes, only the files
that changed again come back to you.

## Install

From JetBrains Marketplace: Settings | Plugins | Marketplace, search
"Nitpick".

From source:

```bash
./gradlew buildPlugin        # build/distributions/nitpick-<version>.zip
```

Settings | Plugins | ⚙ | Install Plugin from Disk… → the zip, restart.
Requires IntelliJ 2026.2+ (build 262).

## The loop

1. The agent edits your working tree or lands commits.
2. Open the **Nitpick** tool window (right sidebar), pick a scope.
3. Walk the files. Comment, mark reviewed, move on.
4. Send the review, or let the agent pull it over MCP.
5. The agent fixes things and resolves comments. Reviewed files it touched
   again show as *changed*, so you re-check only those. The tree refreshes
   on its own when the working tree or the repository changes.

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
| Stash… | a stash vs the commit it was taken on |
| Current Branch, Whole Tree | no diff. Every file of the project at its current content. Click a file to open it in the editor and annotate it there. |
| Current Branch, Folder… | same, limited to one folder |

### Planning on a branch with no changes

Pick *Current Branch*. The tree lists the whole project (or a folder). A
file opens in its normal editor tab, where comments, the gutter `+` and
`Alt+Shift+C` work exactly as in a diff. Marks mean "walked through for the
plan". The session is per branch and remembers the commit the plan started
at; the export tells the agent to treat the comments as a plan and to
`git diff <start>..HEAD` to see what it already did. Switching to any other
scope removes every comment card from the editors again.

The *Editor Annotations* toolbar toggle hides all of it without leaving the
scope: cards, the gutter `+` and the review buttons, in editors and in every
diff the IDE opens (Git log included). Flip it back and they return in place.

From Git Log: right-click a commit → *Review Commit with Nitpick*.
Ctrl+click several commits → *Review Range of N Commits*, oldest vs newest,
same semantics as the log's own *Compare Versions*.

## Reviewing

Double-click a file (or Enter) to open it in the **Nitpick** diff tab. One
tab is reused for the whole review.

In the diff:

| Action | Where |
|---|---|
| Comment a line or selection | hover the line and click **+** in the gutter, `Alt+Shift+C`, or the toolbar |
| Comment the whole file | `Alt+Shift+F` or the toolbar; the card sits above line 1 |
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
  `N ✎` open comments. `Space` toggles reviewed. Right-click a file for Mark
  Reviewed, Add File Comment and Edit Comment. *Hide Reviewed Files* in the
  toolbar leaves only unreviewed and changed files. The Commit tool window
  also gets a ✓ hover icon on every file row, and *Review Uncommitted Changes*
  in its context menu.
- **Comments list**: Enter or double-click opens the diff at the line, or the
  file itself when it is outside the current scope. `R` resolves, `Delete`
  removes. Right-click for Open in Diff, Edit, Resolve, Copy Location, Delete.
  *outdated* marks a comment whose text is gone from the file in this scope.
- **Notes**: free text for the whole review, exported at the end. Drag the
  divider to give it more room.
- **Status line**: files, reviewed, stale, open comments, scope, other saved
  sessions.

Toolbar cleanup: *Reset Reviewed Marks* (keeps comments), *Clear Resolved
Comments*, *Clear Session* (this scope's marks, notes and the comments written
in it), *Clear All Sessions* (everything), *Forget Other Sessions*.

*Export Session…* writes marks, notes and comments of the current scope to a
JSON file. *Import Session…* loads one and switches to its scope. Use it to
move a review to another machine or hand it over.

Header quick toggles (also in the ⋮ menu):

- **Open Diff on Single Click**: the diff tab follows the tree selection. Off
  means only double-click, Enter and the unreviewed arrows change it.
- **Auto-Mark Reviewed**: off, when the diff opens, or when it closes.
  Hands-free marking as you walk the files. In continuous mode a file counts as
  opened when you click into its block.
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

If you have the agent_review MCP tools: call agent_review_list_comments for ids, fix each item, then agent_review_resolve_comment with a one-line reply. Ask with agent_review_add_comment when a comment is unclear. Otherwise reply here with what you changed per item.

1. **[ISSUE]** `src/auth.rs:42` - Magic number should be a named constant
   ```rust
   let timeout = 3000;
   ```
2. `src/auth.rs:50-55` - This block could be refactored
3. `src/old.rs:~12` - why was this removed?

Review notes:
- overall fine, ship after fixes
```

`~` marks a line on the old side of the diff. `(outdated)` after a location
means the commented text is gone. Snippets, intro sentence, the MCP paragraph
and resolved-comment inclusion are settings.

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

Settings | Tools | Nitpick: intro sentence, snippets on/off and max lines,
include resolved comments, MCP paragraph, review file path, terminal tab regex,
auto-submit, auto-mark reviewed (off / open / close), open diff on single
click. The header toggles write the same values.

## Persistence

Everything lives in `.idea/workspace.xml`, per user, never committed. Nothing
else touches disk unless you use *Write Review File* or *Export Session*.

Comments belong to files, not scopes. Each one remembers the file content it
was written on and the commented text. Any scope that contains the file shows
the comment: at the same line when the file is unchanged, at the line where
the text moved to, or as *outdated* in the comments list when the text is
gone. So a comment left on uncommitted changes is still there when you review
the commit, or a range around it, and a comment on commit C shows up in
`A..D` and `B..E` alike.

Reviewed marks work the same way: a file marked reviewed in one scope is
reviewed in every scope where its content is identical.

Sessions hold the scope, the reviewed marks and the notes. One per scope, the
30 most recent are kept.

## Contributing

See `CONTRIBUTING.md` for building, installing locally, and releasing.
Design: `docs/superpowers/specs/2026-09-02-agent-review-design.md`.

## License

MIT. See `LICENSE`.
