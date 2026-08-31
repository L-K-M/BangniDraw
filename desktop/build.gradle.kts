// The desktop shell (DESKTOP.md Phase 2, M4): Compose Desktop window,
// engine rendering through the offscreen-FBO → image architecture
// (DESKTOP.md "Compositing", architecture 1), input through the M3
// pointer-sample records.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    `application`
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("ch.lkmc.bangnidraw.desktop.MainKt")
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
    // src/main/resources still package normally. The path is a local File
    // (serializable) — a script-level val would capture the script object
    // and break the configuration cache.
    val androidAssets = layout.projectDirectory.dir("../app/src/main/assets").asFile
    exclude { details ->
        val rel = details.relativePath.pathString
        details.file.startsWith(androidAssets) && rel != "brushes" && !rel.startsWith("brushes/")
    }
}
