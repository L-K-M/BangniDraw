package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAngleBundleContractTest {

    @Test
    fun `mac packaging stages pinned ANGLE before app resources`() {
        val build = source("desktop/build.gradle.kts")

        assertTrue(build.contains("libs.versions.electronAngle.get()"))
        assertTrue(build.contains("stageMacAngle"))
        assertTrue(build.contains("scripts/fetch-angle-macos.sh"))
        assertTrue(build.contains("angle/resources"))
        assertTrue(build.contains("prepareAppResources"))
        assertTrue(build.contains("dependsOn(stageMacAngle)"))
    }

    @Test
    fun `ANGLE fetch is versioned verified and attributed`() {
        val catalog = source("gradle/libs.versions.toml")
        val fetchFile = repoFile("scripts/fetch-angle-macos.sh")
        val provenanceFile = repoFile("third-party/angle/README.md")

        assertTrue(catalog.contains("electronAngle = \"$ELECTRON_ANGLE_VERSION\""))
        assertTrue(fetchFile.isFile)
        assertTrue(provenanceFile.isFile)

        val fetch = fetchFile.readText(Charsets.UTF_8)
        val provenance = provenanceFile.readText(Charsets.UTF_8)

        assertTrue(fetch.contains("<version> <macos-arm64|macos-x64> <output-root>"))
        assertTrue(fetch.contains("github.com/electron/electron/releases/download"))
        assertTrue(fetch.contains("libEGL.dylib"))
        assertTrue(fetch.contains("libGLESv2.dylib"))
        assertTrue(fetch.contains("LICENSE"))
        assertTrue(fetch.contains("LICENSES.chromium.html"))
        assertTrue(fetch.contains("shasum -a 256"))
        assertEquals(PINNED_SHA256_COUNT, SHA256.findAll(fetch).count())

        assertTrue(provenance.contains("Electron v$ELECTRON_ANGLE_VERSION"))
        assertTrue(provenance.contains("https://github.com/electron/electron"))
        assertTrue(provenance.contains("LICENSE"))
        assertTrue(provenance.contains("LICENSES.chromium.html"))
    }

    @Test
    fun `mac package targets the supported deployment floor`() {
        val build = source("desktop/build.gradle.kts")
        val mac = build.substringAfter("macOS {")

        assertTrue(mac.contains("minimumSystemVersion = \"$MINIMUM_MACOS_VERSION\""))
    }

    @Test
    fun `mac installer rejects an app without ANGLE`() {
        val script = source("scripts/build.sh")
        val check = script.substringAfter("if ! desktop_app_has_angle")
            .substringBefore("fi")

        assertTrue(check.contains("exit 1"))
        assertFalse(check.contains("warning:"))
    }

    @Test
    fun `mac CI and release require ANGLE and render a frame`() {
        val ciMac = source(".github/workflows/ci.yml")
            .substringAfter("\n  desktop-macos:\n")
        val releaseMac = source(".github/workflows/release.yml")
            .substringAfter("\n  build-desktop-macos:\n")
            .substringBefore("\n  publish:\n")

        listOf(ciMac, releaseMac).forEach { workflow ->
            assertTrue(workflow.contains("libEGL.dylib"))
            assertTrue(workflow.contains("libGLESv2.dylib"))
            assertTrue(workflow.contains("LICENSE"))
            assertTrue(workflow.contains("LICENSES.chromium.html"))
            assertTrue(workflow.contains("--smoke-window"))
            assertTrue(workflow.contains("帮你Draw window OK:"))
            assertFalse(workflow.contains("--smoke-startup-failure"))
            assertFalse(workflow.contains("帮你Draw error window OK:"))
        }
    }

    @Test
    fun `release tells users that ANGLE is bundled`() {
        val releaseBody = source(".github/workflows/release.yml")
            .substringAfter("body: |")
            .substringBefore("generate_release_notes:")

        assertTrue(BUNDLED_ANGLE.containsMatchIn(releaseBody))
        assertFalse(releaseBody.contains("needs ANGLE"))
        assertFalse(releaseBody.contains("Without ANGLE"))
        assertFalse(releaseBody.contains("bangnidraw.angle.dir"))
    }

    private fun source(path: String): String = repoFile(path).readText(Charsets.UTF_8)

    private fun repoFile(path: String): File = File(repoRoot(), path)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }

    private companion object {
        const val ELECTRON_ANGLE_VERSION = "41.10.3"
        const val MINIMUM_MACOS_VERSION = "12.0"
        const val PINNED_SHA256_COUNT = 8
        val SHA256 = Regex("(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")
        val BUNDLED_ANGLE = Regex("(?:bundles?\\s+ANGLE|ANGLE\\s+is\\s+bundled)", RegexOption.IGNORE_CASE)
    }
}
