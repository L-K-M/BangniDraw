// The GLES 3.0 render engine, shared by the Android app and the desktop
// app behind the platform facade (`platform/GLES30.kt`, DESKTOP.md "The
// JVM binding"). Depends on the pure model layer in :engine-core.
plugins {
    // Both ship inside AGP 9's distribution — applied by id, without a
    // version (see :engine-core for the failure mode of a versioned alias).
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.serialization)
}

val mixboxEnabled = providers.gradleProperty("bangnidraw.mixbox")
    .map(String::toBooleanStrict)
    .getOrElse(true)

kotlin {
    // JDK 17 is the repo's floor (AGENTS.md); pin it so the desktop target
    // does not inherit whatever JDK happens to run Gradle.
    jvmToolchain(17)

    androidLibrary {
        namespace = "ch.lkmc.bangnidraw.engine.gl"
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
            implementation(project(":engine-core"))
        }

        // The Mixbox license switch (ADR 0003): the mixbox and nomixbox
        // source sets are exclusive, compiled into jvmShared per build.
        jvmShared.kotlin.srcDir(
            if (mixboxEnabled) "src/mixbox/kotlin" else "src/nomixbox/kotlin",
        )
        if (mixboxEnabled) {
            // One copy of the vendored assets: desktop reads them as
            // classpath resources from this directory (desktopMain only —
            // wiring jvmShared would duplicate them into the Android APK's
            // java resources; Android reads them as merged assets from :app).
            desktopMain.resources.srcDir("src/mixbox/assets")

            jvmShared.dependencies {
                // CC BY-NC 4.0 — ADR 0003.
                implementation(libs.mixbox)
            }
        }

        val commonTest by getting
        val jvmSharedTest by creating { dependsOn(commonTest) }
        val desktopTest by getting { dependsOn(jvmSharedTest) }

        jvmSharedTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlin.test)
        }

        jvmSharedTest.kotlin.srcDir(
            if (mixboxEnabled) "src/mixboxTest/kotlin" else "src/nomixboxTest/kotlin",
        )

        desktopMain.dependencies {
            // The desktop GL binding (DESKTOP.md: LWJGL 3.4). All three
            // platforms' natives ride along so one artifact runs on Linux
            // and macOS alike; the context itself arrives with the M4 shell.
            // Classifiers and BOM platforms are not expressible inside this
            // block, so the coordinates are built from the catalog's pin.
            val lwjglVersion = libs.versions.lwjgl.get()
            for (artifact in listOf("lwjgl", "lwjgl-opengles")) {
                implementation("org.lwjgl:$artifact:$lwjglVersion")
                for (natives in listOf(
                    "natives-linux", "natives-linux-arm64", "natives-macos",
                    "natives-macos-arm64", "natives-windows",
                )) {
                    implementation("org.lwjgl:$artifact:$lwjglVersion:$natives")
                }
            }
        }
    }
}
