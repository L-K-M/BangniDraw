// The desktop shell (DESKTOP.md Phase 2, M4): Compose Desktop window,
// engine rendering through the offscreen-FBO → image architecture
// (DESKTOP.md "Compositing", architecture 1), input through the M3
// pointer-sample records.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

// The desktop packages' version. jpackage/deb refuse Gradle's default
// "unspecified"; aligned with the app's versionName by hand at release time
// until the release script learns the desktop formats (M5+).
version = "1.3.0"

compose.desktop {
    application {
        mainClass = "ch.lkmc.bangnidraw.desktop.MainKt"
        // Dmg (macOS) and Deb/Rpm (Linux). NO cross-compilation: each
        // format's task builds only on its own OS (DESKTOP.md
        // "Packaging and distribution"), so CI runs per-OS jobs and macOS
        // packaging stays manual/dispatched. `TargetFormat.AppImage` is
        // deliberately absent: it is jpackage's unpacked directory, not a
        // real AppImage — do not label it as one.
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
            )
            packageName = "BangniDraw"
            packageVersion = version.toString()
            // ASCII only: jpackage writes desktop-entry metadata with the
            // system charset, and a C locale turns non-ASCII into "Input
            // length = 1" failures. The localized name lives in the app UI.
            // Variant-neutral: the nomixbox CI build stamps the same
            // metadata, so the description must not name Mixbox.
            description = "BangniDraw - layered raster painting"
            vendor = "BangniDraw"

            // macOS needs ANGLE's dylibs beside the app at runtime (see the
            // README's desktop section). Only the macOS folders exist: Linux
            // uses the system GLES natively and its fallback folders are
            // recreated when such a thing ever lands. The checked-in
            // placeholders document the layout and NO binaries are committed;
            // appResourcesRootDir packages the folders verbatim, so the
            // placeholder is removed when real dylibs are staged.
            appResourcesRootDir = project.file("packaging/angle")

            // Windows is out of scope for the desktop port (DESKTOP.md covers
            // macOS and Linux); if it returns, restore a windows { menuGroup … }
            // block alongside an Msi/Exe target format.
            macOS {
                bundleID = "ch.lkmc.bangnidraw.desktop"
            }
            linux {
                // Debian policy: package names are lowercase — mixed case
                // builds green but dpkg refuses the install.
                packageName = "bangnidraw"
            }
        }
    }
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-gl"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)

    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)

    // The desktop GL stack: the ES context (GLFW/EGL, incl. the macOS ANGLE
    // init hint) plus the LWJGL bindings already pinned by :engine-gl.
    // lwjgl-egl has no natives artifacts — the EGL library loads dynamically.
    val lwjglVersion = libs.versions.lwjgl.get()
    for (artifact in listOf("lwjgl", "lwjgl-opengles", "lwjgl-glfw", "lwjgl-egl")) {
        implementation("org.lwjgl:$artifact:$lwjglVersion")
    }
    for (artifact in listOf("lwjgl", "lwjgl-opengles", "lwjgl-glfw")) {
        for (natives in listOf(
            "natives-linux", "natives-linux-arm64", "natives-macos",
            "natives-macos-arm64", "natives-windows",
        )) {
            runtimeOnly("org.lwjgl:$artifact:$lwjglVersion:$natives")
        }
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// The one copy of the shipped brush presets doubles as desktop classpath
// resources, exactly as the vendored Mixbox assets do for :engine-gl.
// Packaging is scoped to the brushes so any future Android-only asset
// stays Android-only.
sourceSets.main {
    resources.srcDir(project.file("../app/src/main/assets"))
}

tasks.processResources {
    // Scope the filter to the shared Android assets dir so desktop's own
    // src/main/resources still package normally. The path is a local
    // java.nio.file.Path (serializable) — a script-level val would capture
    // the script object and break the configuration cache.
    val androidAssets = layout.projectDirectory.dir("../app/src/main/assets").asFile.toPath()
    exclude { details ->
        val rel = details.relativePath.pathString
        details.file.toPath().startsWith(androidAssets) && rel != "brushes" && !rel.startsWith("brushes/")
    }
}
