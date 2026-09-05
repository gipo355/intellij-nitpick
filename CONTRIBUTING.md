# Contributing

## Setup

- JDK 21.
- IntelliJ IDEA 2026.2+, optional. `gradle.properties` reads `ideaHome`; without
  a local install Gradle downloads IU 2026.2 (about 1.5 GB). Override with
  `-PideaHome=/path/to/ide`.

## Build and test

```bash
./gradlew test          # unit + platform tests
./gradlew buildPlugin   # build/distributions/nitpick-<version>.zip
./gradlew verifyPlugin  # IntelliJ Plugin Verifier
./gradlew runIde        # sandbox IDE with the plugin installed
```

`verifyPlugin` must report *Compatible* with no internal API usages.

## Pull requests

- Branch from `main`; CI runs `test`, `buildPlugin` and `verifyPlugin`.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org):
  `feat:` bumps the minor version, `fix:` the patch, `feat!:` the major.
  `chore:`, `docs:` and `refactor:` stay out of the changelog.
- Add a test for behaviour changes. Platform tests live next to the unit tests
  under `src/test`.

## Release

release-please keeps a release PR open on `main` with the next version and
`CHANGELOG.md`. Merging it tags `v<version>`, creates the GitHub release, and
the publish workflow uploads the zip to JetBrains Marketplace. Do not edit
`version` by hand.

## Layout

| Package | Role |
|---|---|
| `model` | Session, comments, scope, content hash |
| `store` | `ReviewStore` project service, persisted in workspace.xml |
| `scope` | Scope → changes, `ReviewChangesModel` cache, navigation |
| `diff` | Diff and editor bindings, inlays, comment popup |
| `actions` | Diff, editor, send and scope actions |
| `ui` | Tool window, Commit view hover icon, notifications |
| `channels` | Clipboard, file, terminal, Copilot, AI Assistant |
| `mcp` | Toolset on the bundled MCP server |
| `export` | Markdown and JSON exporters |
