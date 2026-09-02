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

// Keep this packaging-time copy aligned with DesktopBrand.parseDisplayName.
// Ampersand stays last so `&amp;lt;` decodes once to the literal `&lt;`.
private fun decodeXmlText(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private fun escapeXmlText(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

val androidStringsFile = layout.projectDirectory.file("../app/src/main/res/values/strings.xml")
val desktopDisplayName = providers.fileContents(androidStringsFile).asText.map { text ->
    Regex("""<string\b[^>]*\bname\s*=\s*["']app_name["'][^>]*>([^<]*)</string>""")
        .find(text)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { decodeXmlText(it) }
        ?: error("app_name is missing from ${androidStringsFile.asFile}")
}.get()
val desktopDisplayNameForPlist = escapeXmlText(desktopDisplayName)

val androidBuildFile = layout.projectDirectory.file("../app/build.gradle.kts")
val desktopPackageVersion = providers.fileContents(androidBuildFile).asText.map { text ->
    Regex("""(?m)^\s*versionName\s*=\s*"([^"]+)"""")
        .find(text)
        ?.groupValues
        ?.get(1)
        ?: error("versionName is missing from ${androidBuildFile.asFile}")
}.get()

val desktopPackageBuildVersion = providers.fileContents(androidBuildFile).asText.map { text ->
    Regex("""(?m)^\s*versionCode\s*=\s*(\d+)""")
        .find(text)
        ?.groupValues
        ?.get(1)
        ?: error("versionCode is missing from ${androidBuildFile.asFile}")
}.get()

// Apple accepts only MAJOR[.MINOR][.PATCH]; versionCode distinguishes RCs.
val desktopMacPackageVersion = desktopPackageVersion.substringBefore('-')
check(Regex("""\d+(?:\.\d+){0,2}""").matches(desktopMacPackageVersion)) {
    "macOS package version must be numeric: $desktopMacPackageVersion"
}
val desktopDebPackageVersion = desktopPackageVersion.replaceFirst('-', '~')
val desktopRpmPackageVersion = desktopPackageVersion.replaceFirst('-', '~')

val hostOsName = providers.systemProperty("os.name").get()
val hostArchitecture = providers.systemProperty("os.arch").get()
val arm64Architectures = setOf("aarch64", "arm64")
val x64Architectures = setOf("amd64", "x86_64")
val lwjglNativeClassifier = when {
    hostOsName.startsWith("Mac") && hostArchitecture in arm64Architectures -> "natives-macos-arm64"
    hostOsName.startsWith("Mac") && hostArchitecture in x64Architectures -> "natives-macos"
    hostOsName.startsWith("Linux") && hostArchitecture in arm64Architectures -> "natives-linux-arm64"
    hostOsName.startsWith("Linux") && hostArchitecture in x64Architectures -> "natives-linux"
    hostOsName.startsWith("Windows") && hostArchitecture in x64Architectures -> "natives-windows"
    hostOsName.startsWith("Windows") && hostArchitecture in arm64Architectures -> "natives-windows-arm64"
    else -> error("unsupported desktop host: $hostOsName $hostArchitecture")
}

kotlin {
    jvmToolchain(17)
}

// Android and desktop packages share one release version source.
version = desktopPackageVersion

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
            packageName = desktopDisplayName
            packageVersion = version.toString()
            description = "Layered raster painting"
            vendor = "L-K-M"
            modules("java.instrument", "jdk.management", "jdk.unsupported")

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
                packageVersion = desktopMacPackageVersion
                packageBuildVersion = desktopPackageBuildVersion
                dockName = desktopDisplayName
                iconFile.set(project.file("packaging/icons/bangnidraw.icns"))

                // jpackage emits CFBundleName, but Finder prefers this key.
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleDisplayName</key>
                        <string>$desktopDisplayNameForPlist</string>
                    """.trimIndent()
                }
            }
            linux {
                // Debian policy: package names are lowercase — mixed case
                // builds green but dpkg refuses the install.
                packageName = "bangnidraw"
                debPackageVersion = desktopDebPackageVersion
                rpmPackageVersion = desktopRpmPackageVersion
                appRelease = desktopPackageBuildVersion
                appCategory = "Graphics"
                debMaintainer = "L-K-M@users.noreply.github.com"
                menuGroup = "Graphics"
                iconFile.set(project.file("packaging/icons/bangnidraw.png"))
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
        runtimeOnly("org.lwjgl:$artifact:$lwjglVersion:$lwjglNativeClassifier")
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
    from(androidStringsFile) {
        into("brand")
        rename { "android-strings.xml" }
    }
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
