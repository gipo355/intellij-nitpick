# Contributing

## Requirements

- JDK 21+ (the build uses a 21 toolchain).
- A local IntelliJ IDEA 2026.2+ install (optional; without it Gradle downloads
  IU 2026.2, about 1.5 GB). `gradle.properties` points `ideaHome`
  at the Toolbox path; override with `-PideaHome=/path/to/ide`.

## Build and test

```bash
./gradlew test          # unit + platform tests
./gradlew buildPlugin   # build/distributions/nitpick-<version>.zip
./gradlew verifyPlugin  # IntelliJ Plugin Verifier, run before a release
```

## Try it in a sandbox IDE

```bash
./gradlew runIde
```

A second IntelliJ starts with the plugin installed and its own settings.
Open any git project in it and use the **Nitpick** tool window.

## Install in your real IDE

1. `./gradlew buildPlugin`
2. Settings | Plugins.
3. Click the ⚙ gear icon at the top of the plugin list.
4. Install Plugin from Disk… and pick `build/distributions/nitpick-<version>.zip`.
5. Restart the IDE.

Repeat the same steps with a new zip to update. Uninstall from the Installed tab.

## Release

Commits on `main` follow [Conventional Commits](https://www.conventionalcommits.org):
`feat:` bumps the minor version, `fix:` the patch, `feat!:` the major.
Other types (`chore:`, `docs:`, `refactor:`) stay out of the changelog.

1. `.github/workflows/build.yml` runs `test`, `buildPlugin` and `verifyPlugin`
   on every pull request. Fix every "compatibility problem" the verifier
   reports; warnings about internal API usage are expected for the AI
   Assistant channel.
2. `.github/workflows/release-please.yml` keeps a release PR open on `main`
   with the next version and the generated `CHANGELOG.md`. Merge it to tag
   `v<version>` and create the GitHub release.
3. `.github/workflows/publish.yml` builds the tag, verifies it, attaches the
   zip to the release and uploads it to JetBrains Marketplace. The release
   notes become the plugin's change notes.

Secrets: `PUBLISH_TOKEN` (permanent Marketplace token from
https://plugins.jetbrains.com, My Tokens) and `RELEASE_PLEASE_TOKEN` (a PAT
with contents and pull-requests write; releases created with the default
Actions token do not trigger workflows).

Local publish: `export PUBLISH_TOKEN=perm:...` then `./gradlew publishPlugin`.
Marketplace rejects a version that already exists. Never commit the token.

`untilBuild` is unset, so Marketplace treats the plugin as compatible with all
future builds. The AI Assistant channel uses an internal API and is the most
likely thing to break on a major IDE update.

## Layout

| Package | Role |
|---|---|
| `model` | Session, comments, scope, content hash |
| `store` | `ReviewStore` project service, persisted in workspace.xml |
| `scope` | Scope → git changes, `ReviewChangesModel` cache, diff opening |
| `diff` | `DiffExtension`, inlays, comment popup |
| `actions` | Diff actions, send actions, scope actions |
| `ui` | Tool window, Commit view hover icon, notifications |
| `channels` | Clipboard, file, terminal, Copilot, AI Assistant |
| `mcp` | Toolset on the bundled MCP server |
| `export` | Markdown and JSON exporters |

Design doc: `docs/superpowers/specs/2026-09-02-agent-review-design.md`.
