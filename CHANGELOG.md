# Changelog

## [0.4.0](https://github.com/gipo355/intellij-nitpick/compare/v0.3.1...v0.4.0) (2026-09-05)


### Features

* branch mode and editor annotations toggle ([fa2d299](https://github.com/gipo355/intellij-nitpick/commit/fa2d299bd33ff603c36966eac6a862a0e5ae324b))
* branch mode annotates the checked-out tree, lazy hashing, stash scope ([5751726](https://github.com/gipo355/intellij-nitpick/commit/5751726f60ff4c55f6006d40066f02c27ff17e0f))
* editor annotations toggle hides cards, gutter + and review buttons ([5e8c05a](https://github.com/gipo355/intellij-nitpick/commit/5e8c05ae1673a3bd64c75f94e9ddbfb6a333babe))
* hidden annotations also silence editor shortcuts and unbind branch editors ([7b40b9d](https://github.com/gipo355/intellij-nitpick/commit/7b40b9d8121342cbb89e172df8c799f210405401))
* **scope:** add branch scope kind and single-pass content hash ([6466172](https://github.com/gipo355/intellij-nitpick/commit/6466172fcd39a6584b1619d4a1bc1703481e1f17))


### Bug Fixes

* **branch:** cancellable tree walk, follow named checkouts only, redraw on reload ([c3c1375](https://github.com/gipo355/intellij-nitpick/commit/c3c1375438d7d074aa12cabf0a1a0769b22edc4e))

## [0.3.1](https://github.com/gipo355/intellij-nitpick/compare/v0.3.0...v0.3.1) (2026-09-04)


### Continuous Integration

* document that immutable releases must stay off ([1f9fdba](https://github.com/gipo355/intellij-nitpick/commit/1f9fdbafc1fe0e785943656c53f56a186af116c6))

## [0.3.0](https://github.com/gipo355/intellij-nitpick/compare/v0.2.1...v0.3.0) (2026-09-04)


### Features

* agent feedback round: batch resolve, filters, context, threads ([3bb7e2f](https://github.com/gipo355/intellij-nitpick/commit/3bb7e2f49e2a397e18ef906d5527815627315a0d))
* Alt+1..5 pick the comment type, new comments start with the last type ([fccc70e](https://github.com/gipo355/intellij-nitpick/commit/fccc70e13eb7d990a84a5239d88740a5f4a57161))
* comment badge on folder nodes in the tree ([806f00d](https://github.com/gipo355/intellij-nitpick/commit/806f00d7bf8216c4484e2f0329d768c07bea9c44))
* editor-backed comment and notes inputs for IdeaVim ([efbeba8](https://github.com/gipo355/intellij-nitpick/commit/efbeba8ad9bb2dcb6ca5188a9cc877d07a66bd9a))
* file filter option Has Comments ([ab45262](https://github.com/gipo355/intellij-nitpick/commit/ab45262cdcbc5ae13d22f3c8802ae5bbc08e6976))
* filter the tree to all, unreviewed or reviewed files ([e0e883c](https://github.com/gipo355/intellij-nitpick/commit/e0e883cb1f13a641d0cbc39d3432fa5890791bbc))
* folder comments, comment filter, resolved badges and agent replies ([37b1205](https://github.com/gipo355/intellij-nitpick/commit/37b1205cc38e1b396051258f6665434f4dd04707))
* folder comments, comment filter, resolved badges and agent replies in the list ([f6795c6](https://github.com/gipo355/intellij-nitpick/commit/f6795c620953feb32d474a6abbdf6b5f862544ee))
* mark or unmark every file under a folder as reviewed ([14209ab](https://github.com/gipo355/intellij-nitpick/commit/14209abfcceb3a35d84c5fdcd2899a9c4a5c6119))
* markdown grouped by file with one-line snippets ([ad699a3](https://github.com/gipo355/intellij-nitpick/commit/ad699a3a89beeb83deb41eed0df5a042f4970baa))
* **mcp:** batch resolve, id prefixes, scope base/head, status totals ([03881e8](https://github.com/gipo355/intellij-nitpick/commit/03881e84a809ac82cbbca8fd717972cb2c989459))
* **mcp:** list_comments filters and context lines ([f6adf14](https://github.com/gipo355/intellij-nitpick/commit/f6adf147e3034691afca39ccaa1ba0ee9e79058e))
* **mcp:** per-file resolved and won't-fix counts, scope_kind values documented ([70c028f](https://github.com/gipo355/intellij-nitpick/commit/70c028f35dd827e12c5e40190e066eb4dbd6588b))
* **mcp:** relocate a comment to where the fix landed on resolve ([c9eb893](https://github.com/gipo355/intellij-nitpick/commit/c9eb8931f8d6d8d16434808b01142f4af494c6ed))
* reply threads and won't-fix on comments, in MCP, exports and UI ([03166b9](https://github.com/gipo355/intellij-nitpick/commit/03166b95fbdd849f397898f90c0bb62d8e227eeb))
* switch and delete saved sessions from the scope dropdown ([18654e8](https://github.com/gipo355/intellij-nitpick/commit/18654e8647673695abb17e92f7095d3e5e2a6bbf))
* switch and delete saved sessions from the scope dropdown ([#9](https://github.com/gipo355/intellij-nitpick/issues/9)) ([feee86d](https://github.com/gipo355/intellij-nitpick/commit/feee86d37f5176c6bc1eb112ece0d1f111c08646))


### Bug Fixes

* keep folder expansion and selection when the tree rebuilds ([04d468d](https://github.com/gipo355/intellij-nitpick/commit/04d468d8b22144d41caa1bda5aa715f3bdda479f))
* keep the tree selection when hidden reviewed files rebuild the tree ([4c0432f](https://github.com/gipo355/intellij-nitpick/commit/4c0432f0b035a8b19c96d97d0d74656e567dcb79))
* make comment and notes editors pass IdeaVim's allowlist ([7b9e3e5](https://github.com/gipo355/intellij-nitpick/commit/7b9e3e591ac5ca52d4ed29fe0669be5dd2c61309))
* wrap the toolbar on overflow, replace deprecated platform APIs ([0759abb](https://github.com/gipo355/intellij-nitpick/commit/0759abb9032dd1907124e22a083aa876ea854c2c))

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
