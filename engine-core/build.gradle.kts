// The pure-JVM engine model layer (tiles, strokes, brushes, wet sim,
// history, serialization), shared by the Android app and the desktop app.
// Every target is a JVM, so the code lives in the shared `jvmShared`
// source set rather than commonMain (it uses java.nio / java.util.concurrent).
plugins {
    // AGP 9 ships the Kotlin Gradle plugin on its classpath (built-in Kotlin
    // support), so the KMP plugin must be applied without a version here —
    // requesting a marker version for an already-loaded plugin fails.
    id("org.jetbrains.kotlin.multiplatform")
    // Ships inside AGP's distribution (version-matched to the AGP pin), so it
    // is likewise applied without a version.
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // JDK 17 is the repo's floor (AGENTS.md); pin it so the desktop target
    // does not inherit whatever JDK happens to run Gradle.
    jvmToolchain(17)

    androidLibrary {
        namespace = "ch.lkmc.bangnidraw.engine.core"
        compileSdk = 37
        minSdk = 29
    }
    jvm("desktop")

    sourceSets {
        val commonMain by getting
        val jvmShared by creating { dependsOn(commonMain) }
        val androidMain by getting { dependsOn(jvmShared) }
        val desktopMain by getting { dependsOn(jvmShared) }

        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }

        val commonTest by getting
        val jvmSharedTest by creating { dependsOn(commonTest) }
        val desktopTest by getting { dependsOn(jvmSharedTest) }

        jvmSharedTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlin.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    // The golden-stroke test regenerates its pinned file when this is set
    // (`docs/plan/11-testing.md` §6); see the same block in :app for why the
    // property must be forwarded explicitly.
    systemProperty(
        "bangni.updateGolden",
        providers.gradleProperty("bangni.updateGolden")
            .orElse(providers.systemProperty("bangni.updateGolden"))
            .getOrElse("false"),
    )
}
