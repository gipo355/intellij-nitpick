# Contributing

## Requirements

- JDK 21+ (the build uses a 21 toolchain).
- A local IntelliJ IDEA 2026.2+ install. `gradle.properties` points `ideaHome`
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

## Release to JetBrains Marketplace

1. Create a vendor account at https://plugins.jetbrains.com and generate a
   token under your profile (My Tokens).
2. The first version must be uploaded manually: Upload plugin → the zip from
   `./gradlew buildPlugin`. The plugin id `dev.gipo.nitpick` from
   `plugin.xml` becomes the Marketplace id. JetBrains reviews the first upload,
   usually within two business days.
3. Every later version goes through Gradle with the token:
   ```bash
   export PUBLISH_TOKEN=perm:...
   ./gradlew publishPlugin
   ```
   `build.gradle.kts` reads `PUBLISH_TOKEN` from the environment. There is no
   project key or other account setting: Marketplace matches the upload by the
   plugin id `dev.gipo.nitpick` that the first manual upload registered. It
   rejects a version that already exists, so bump `version` first. The release
   channel defaults to stable; set `intellijPlatform.publishing.channels` for a
   beta channel. Never commit the token.

   From CI: store the token as a repository secret and run the same command,
   for example in a GitHub Actions job triggered by a tag. Wait until the first
   manual review has cleared before wiring that up.
4. Release checklist: bump `version` in `build.gradle.kts`, update
   `<change-notes>` in `plugin.xml`, then
   ```bash
   ./gradlew test verifyPlugin buildPlugin
   ```
   The verifier runs against the local IDE from `ideaHome`. Fix every
   "compatibility problem" it reports; warnings about internal API usage are
   expected for the AI Assistant channel.

`untilBuild` is unset, so Marketplace treats the plugin as compatible with all
future builds. The AI Assistant channel uses an internal API and is the most
likely thing to break on a major IDE update.

Without Marketplace, attach the zip to a GitHub release. Users install it from
disk as above.

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
