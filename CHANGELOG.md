# Changelog

## 0.2.1

- Comments belong to files. They follow the commented text across scopes and commits, or show as outdated.
- Reviewed marks carry over between scopes when the file content matches.
- Right-click a file in the tree: mark reviewed, add or edit comments. File comment button in the diff toolbar.
- Hide Reviewed Files toggle. Resizable notes pane. Export and import a session.
- The prompt tells the agent to use the MCP tools when available.
- Auto-mark is one setting: off, on open, on close. The tree refreshes on VCS changes.

## 0.2.0

- Inline review comments in side-by-side and unified diff viewers, with gutter + on hover.
- Scopes: uncommitted, staged, unstaged, commit, commit range, compare with branch (merge-base).
- Git Log actions for one commit or a multi-commit range.
- Reviewed marks keyed by content hash, stale detection, Next Unreviewed.
- Export to clipboard, terminal (classic and reworked), file, Copilot Chat, AI Assistant.
- MCP toolset: get review, list, resolve, add comment, status.
