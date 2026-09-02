package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopReleaseContractTest {

    @Test
    fun `desktop package version comes from Android versionName`() {
        val build = source("desktop/build.gradle.kts")

        assertTrue(build.contains("versionName"))
        assertFalse(Regex("""(?m)^version\s*=\s*\"\d""").containsMatchIn(build))
    }

    @Test
    fun `mac package version remains numeric for prerelease tags`() {
        val build = source("desktop/build.gradle.kts")
        val separator = Regex(
            """desktopMacPackageVersion\s*=\s*desktopPackageVersion\.substringBefore\('([^']+)'\)""",
        ).find(build)?.groupValues?.get(1)

        assertEquals("-", separator)
        assertEquals("1.4.0", "1.4.0-rc.1".substringBefore(checkNotNull(separator)))
        assertTrue(build.contains("packageVersion = desktopMacPackageVersion"))
        assertTrue(build.contains("packageBuildVersion = desktopPackageBuildVersion"))
        assertTrue(build.contains("desktopPackageVersion.replaceFirst('-', '~')"))
        assertTrue(build.contains("debPackageVersion = desktopDebPackageVersion"))
        assertTrue(build.contains("rpmPackageVersion = desktopRpmPackageVersion"))
        assertTrue(build.contains("val desktopRpmPackageVersion = desktopPackageVersion.replaceFirst('-', '~')"))
        assertTrue(build.contains("appRelease = desktopPackageBuildVersion"))
    }

    @Test
    fun `release verifies both desktop package versions`() {
        val release = source(".github/workflows/release.yml")

        assertTrue(release.contains("dpkg-deb -f"))
        assertTrue(release.contains("CFBundleShortVersionString"))
        assertTrue(release.contains("CFBundleVersion"))
        assertTrue(release.contains("MAC_VERSION="))
        assertTrue(release.contains("DEB_VERSION=\"${'$'}{VERSION/-/~}\""))
        assertTrue(release.contains("versionCode not found in app/build.gradle.kts"))
    }

    @Test
    fun `mac release filename derives its runner architecture`() {
        val release = source(".github/workflows/release.yml")

        assertTrue(release.contains("uname -m"))
        assertFalse(release.contains("macos-arm64.dmg\""))
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
}
