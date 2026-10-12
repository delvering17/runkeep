import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm

plugins {
    id("com.android.application") version "9.4.1" apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(25)
    // Developers and CI install JDK 25; no platform-specific download URLs are needed.
    toolchainDownloadUrls.empty()
}
