# Contributing

## Requirements

- JDK 21+ (the build uses a 21 toolchain).
- A local IntelliJ IDEA 2026.2+ install. `gradle.properties` points `ideaHome`
  at the Toolbox path; override with `-PideaHome=/path/to/ide`.

## Build and test

```bash
./gradlew test          # unit + platform tests
./gradlew buildPlugin   # build/distributions/agent-review-<version>.zip
./gradlew verifyPlugin  # IntelliJ Plugin Verifier, run before a release
```

## Try it in a sandbox IDE

```bash
./gradlew runIde
```

A second IntelliJ starts with the plugin installed and its own settings.
Open any git project in it and use the **Agent Review** tool window.

## Install in your real IDE

1. `./gradlew buildPlugin`
2. Settings | Plugins.
3. Click the ⚙ gear icon at the top of the plugin list.
4. Install Plugin from Disk… and pick `build/distributions/agent-review-<version>.zip`.
5. Restart the IDE.

Repeat the same steps with a new zip to update. Uninstall from the Installed tab.

## Release to JetBrains Marketplace

1. Create a vendor account at https://plugins.jetbrains.com and generate a
   token under your profile (Tokens).
2. First upload is manual: Upload plugin → the zip. JetBrains reviews it once.
3. Later releases go through Gradle. Add to `build.gradle.kts`:
   ```kotlin
   intellijPlatform {
       publishing { token = providers.environmentVariable("JB_PUBLISH_TOKEN") }
   }
   ```
   then
   ```bash
   JB_PUBLISH_TOKEN=... ./gradlew publishPlugin
   ```
4. Before submitting: bump `version` in `build.gradle.kts`, add `<change-notes>`
   to `plugin.xml`, add a 40x40 `pluginIcon.svg` under `src/main/resources/META-INF`,
   and run `./gradlew verifyPlugin`.

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
