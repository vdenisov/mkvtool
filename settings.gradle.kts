// Plugin and dependency versions are pinned in gradle/libs.versions.toml (single source of truth).
// The version catalog's accessors are not available inside pluginManagement, so the plugins are
// applied — with their versions — from build.gradle.kts instead; only the repositories live here.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mkvtool"
