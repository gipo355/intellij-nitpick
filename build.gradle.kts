plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.gipo.agentreview"
version = "0.2.1" // x-release-please-version

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Local IDE from gradle.properties when the directory exists (dev machine), otherwise a
// downloaded IU so CI can build. Override with -PplatformVersion=2026.2.1.
val ideaHome = providers.gradleProperty("ideaHome").map { file(it) }
val hasLocalIde = ideaHome.map { it.isDirectory }.getOrElse(false)
val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2026.2")

dependencies {
    intellijPlatform {
        if (hasLocalIde) local(providers.gradleProperty("ideaHome")) else intellijIdeaUltimate(platformVersion)
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.mcpServer")
        bundledModule("intellij.terminal.frontend")
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.impl.shared")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.log")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    // Interface defaults stay defaults: no bridge overrides of deprecated ToolWindowFactory methods.
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

intellijPlatform {
    pluginConfiguration {
        // CI passes the GitHub release notes; local builds ship without change notes.
        changeNotes = providers.environmentVariable("CHANGE_NOTES")
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    publishing {
        // Marketplace token: export PUBLISH_TOKEN=... locally, repository secret in CI.
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides { if (hasLocalIde) local(ideaHome) else recommended() }
    }
}
