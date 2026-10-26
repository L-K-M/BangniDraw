// The desktop shell (DESKTOP.md Phase 2, M4): Compose Desktop window,
// engine rendering through the offscreen-FBO → image architecture
// (DESKTOP.md "Compositing", architecture 1), input through the M3
// pointer-sample records.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
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

val macAngleTarget = when (hostArchitecture) {
    in arm64Architectures -> "macos-arm64"
    in x64Architectures -> "macos-x64"
    else -> error("unsupported macOS architecture: $hostArchitecture")
}
// Compose app resources: common/ for every OS (the notices) and the macOS
// ANGLE runtime under macos-arm64/ or macos-x64/. They land in lib/app/resources
// (deb, Flatpak) and Contents/app/resources (dmg).
val appResources = layout.buildDirectory.dir("generated/app-resources")
val stageMacAngle = tasks.register<Exec>("stageMacAngle") {
    val fetchScript = layout.projectDirectory.file("../scripts/fetch-angle-macos.sh")
    val angleVersion = libs.versions.electronAngle.get()

    inputs.file(fetchScript)
    inputs.property("electronAngleVersion", angleVersion)
    inputs.property("macAngleTarget", macAngleTarget)
    outputs.dir(appResources.get().dir(macAngleTarget))

    enabled = hostOsName.startsWith("Mac")
    commandLine(
        fetchScript.asFile.absolutePath,
        angleVersion,
        macAngleTarget,
        appResources.get().asFile.absolutePath,
    )
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

            // Third-party notices for every OS, plus the pinned ANGLE
            // binaries and their notices on macOS (Linux uses the system
            // EGL/GLES implementation).
            appResourcesRootDir = appResources.get().asFile

            // Finder's Get Info and the deb's copyright file: the app's own
            // license, scoped, because the packages bundle third-party code.
            copyright = "Public domain (Unlicense). Bundled components keep their own licenses."
            // jpackage puts this text in the deb's share/doc/copyright as the
            // License field, instead of "Unknown". Linux only: on macOS
            // jpackage would turn it into a dmg license agreement.
            if (hostOsName.startsWith("Linux")) {
                licenseFile.set(project.file("packaging/linux/copyright-license.txt"))
            }

            // Windows is out of scope for the desktop port (DESKTOP.md covers
            // macOS and Linux); if it returns, restore a windows { menuGroup … }
            // block alongside an Msi/Exe target format.
            macOS {
                bundleID = "ch.lkmc.bangnidraw.desktop"
                minimumSystemVersion = "12.0"
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

// Third-party notices for the desktop packages: the runtime classpath that
// ships, plus config/ for what no POM describes (Skia and the libraries
// libskiko links statically, LWJGL's natives, Skiko's NOTICE). Offline, and
// fails on a dependency without a license or outside the allow-list.
aboutLibraries {
    offlineMode = true
    collect {
        configPath = file("config")
        fetchRemoteLicense = false
        fetchRemoteFunding = false
        filterVariants.add("runtimeClasspath")
    }
    export {
        outputFile = layout.buildDirectory.file("generated/notices/aboutlibraries.json")
        prettyPrint = true
    }
    library {
        requireLicense = true
    }
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll(
            "Apache-2.0", "BSD-3-Clause", "FTL", "IJG", "LicenseRef-Adobe-DNG-SDK", "MIT",
            "MIT-Khronos-old", "MIT-Modern-Variant", "Unicode-3.0", "Zlib", "libpng-2.0",
        )
        // Mixbox is non-commercial (ADR 0003) and allowed for it alone.
        allowedLicensesMap = mapOf("CC-BY-NC-4.0" to listOf("com.scrtwpns:mixbox"))
    }
}

// The jlink'd runtime (GPL-2.0-only WITH Classpath-exception-2.0) comes from
// the JDK jpackage runs on, so name that exact build and its source.
val packagedJdkRelease: Map<String, String> =
    providers.fileContents(
        layout.projectDirectory.file(File(compose.desktop.application.javaHome, "release").absolutePath),
    ).asText.get()
        .lines()
        .mapNotNull { Regex("""^([A-Z_]+)="(.*)"$""").find(it.trim())?.destructured }
        .associate { (key, value) -> key to value }
val packagedJdkVersion = packagedJdkRelease["JAVA_RUNTIME_VERSION"]
    ?: error("JAVA_RUNTIME_VERSION is missing from ${compose.desktop.application.javaHome}/release")
val packagedJdkImplementor = packagedJdkRelease["IMPLEMENTOR"] ?: "unknown vendor"
val packagedJdkCommit = Regex("""git:([0-9a-f]{7,40})""")
    .find(packagedJdkRelease["SOURCE"].orEmpty())?.groupValues?.get(1)
// Temurin, which CI and release use, records its adoptium/jdk<N>u commit.
val packagedJdkSource = if (packagedJdkImplementor == "Eclipse Adoptium" && packagedJdkCommit != null) {
    "https://github.com/adoptium/jdk${packagedJdkVersion.substringBefore('.')}u/tree/$packagedJdkCommit"
} else {
    "the $packagedJdkImplementor source for this version (release file SOURCE: " +
        "${packagedJdkRelease["SOURCE"] ?: "not recorded"})"
}
val noticesRule = "=".repeat(72)
// ASCII on purpose: the header travels as a command-line argument, which a
// non-UTF-8 locale would mangle.
val thirdPartyNoticesHeader = buildString {
    appendLine("BangniDraw desktop: third-party notices")
    appendLine()
    appendLine("BangniDraw's own code is released under the Unlicense; see")
    appendLine("BangniDraw-LICENSE.txt next to this file. This package also bundles the")
    appendLine("third-party components below, which keep their own licenses. Each entry")
    appendLine("names the component, its version, its license, its source and the full")
    appendLine("license and notice texts.")
    appendLine(noticesRule)
    appendLine("OpenJDK runtime $packagedJdkVersion ($packagedJdkImplementor)")
    appendLine("GPL-2.0-only WITH Classpath-exception-2.0")
    appendLine("Source: $packagedJdkSource")
    appendLine("The Java runtime jlink assembled for this package (lib/runtime, or")
    appendLine("Contents/runtime on macOS). Its license texts and the notices of the code")
    appendLine("it contains ship with it in its legal/ directory.")
    if (hostOsName.startsWith("Mac")) {
        val angleVersion = libs.versions.electronAngle.get()
        appendLine(noticesRule)
        appendLine("ANGLE (libEGL.dylib, libGLESv2.dylib), from Electron $angleVersion")
        appendLine("https://github.com/electron/electron/releases/tag/v$angleVersion")
        appendLine("Their notices ship next to this file: LICENSES.chromium.html for ANGLE")
        appendLine("and the Chromium code it uses, and Electron-LICENSE.txt.")
    }
}

val noticesScript = rootProject.layout.projectDirectory.file("scripts/android_notices.py")
val noticesDefinitions = layout.buildDirectory.file("generated/notices/aboutlibraries.json")
val renderedNotices = layout.buildDirectory.file("generated/notices/Third-Party-Notices.txt")

// Every component has its full text; MIT/BSD-style ones carry their own
// copyright line; Mixbox names its creators, source and modification status.
val checkThirdPartyNotices = tasks.register<Exec>("checkThirdPartyNotices") {
    dependsOn("exportLibraryDefinitions")
    inputs.file(noticesScript)
    inputs.file(noticesDefinitions)
    commandLine(
        "python3", "-I", noticesScript.asFile.absolutePath,
        "check", noticesDefinitions.get().asFile.absolutePath, "-q",
    )
}

val renderThirdPartyNotices = tasks.register<Exec>("renderThirdPartyNotices") {
    dependsOn(checkThirdPartyNotices)
    inputs.file(noticesScript)
    inputs.file(noticesDefinitions)
    inputs.property("header", thirdPartyNoticesHeader)
    outputs.file(renderedNotices)
    commandLine(
        "python3", "-I", noticesScript.asFile.absolutePath,
        "render", noticesDefinitions.get().asFile.absolutePath,
        "-o", renderedNotices.get().asFile.absolutePath,
        "--header", thirdPartyNoticesHeader,
    )
}

// The notices and the app's own LICENSE, for every OS.
val stageCommonAppResources = tasks.register<Sync>("stageCommonAppResources") {
    from(renderThirdPartyNotices)
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        rename { "BangniDraw-LICENSE.txt" }
    }
    into(appResources.map { it.dir("common") })
}

tasks.configureEach {
    if (name == "prepareAppResources") dependsOn(stageMacAngle, stageCommonAppResources)
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-gl"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // The rail and top strip wear the same Material glyphs the Android
    // chrome does; the multiplatform artifact is the desktop twin of
    // :app's androidx.compose.material:material-icons-extended. Deprecated
    // after 1.7.3, which is what it resolves to, and 37.8 MB of it ships in
    // the distribution — AGENTS.md weighs that against vendoring the eleven
    // extended-only glyphs, which the asset policy does not currently allow.
    implementation(compose.materialIconsExtended)
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

    // The repo-owned tool silhouettes are Compose-only (no android.*), so the
    // one copy under :app compiles into the desktop shell too rather than
    // being transcribed: the two rails must never drift apart on artwork.
    kotlin.srcDir(project.file("../app/src/main/java/ch/lkmc/bangnidraw/ui/glyphs"))

    // Portable Compose that is not artwork: the type scale, the hover cursor,
    // the composition guides and the colour picker. Same rule as the glyphs —
    // no android.*, one copy — because these are the pieces whose *geometry*
    // must not drift between the two products.
    kotlin.srcDir(project.file("../app/src/main/java/ch/lkmc/bangnidraw/ui/shared"))

    // The `.bangni` document format and the tile codec it stores. This one is
    // load-bearing rather than tidy: a file written on a phone has to open on
    // a laptop, so a second implementation of the container would be a second
    // chance to disagree about it.
    kotlin.srcDir(project.file("../app/src/main/java/ch/lkmc/bangnidraw/data/shared"))
}

tasks.processResources {
    from(androidStringsFile) {
        into("brand")
        rename { "android-strings.xml" }
    }

    // The user-visible text, from the same `strings.xml` the Android app
    // ships — one source, and `MissingTranslation` already gates the second
    // locale in CI, so a desktop-only string cannot land untranslated.
    from(androidStringsFile) {
        into("strings")
        rename { "values.xml" }
    }
    // Checked, because `from` skips a missing source without a word: rename
    // the locale folder and the desktop would ship English-only with nothing
    // failing. `MissingTranslation` does not cover it either — nothing is
    // missing from a folder that no longer exists. The English path already
    // fails loudly, since `app_name` is read out of it.
    val zhHansStrings =
        layout.projectDirectory.file("../app/src/main/res/values-b+zh+Hans/strings.xml")
    check(zhHansStrings.asFile.isFile) {
        "the shared zh-Hans strings are missing: ${zhHansStrings.asFile}"
    }
    from(zhHansStrings) {
        into("strings")
        rename { "zh-Hans.xml" }
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
