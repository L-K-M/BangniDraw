package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRuntimeSmokeContractTest {

    @Test
    fun `Linux CI and releases render one packaged frame`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")
        val ci = source(".github/workflows/ci.yml")
        val release = source(".github/workflows/release.yml")

        assertTrue(main.contains("--smoke-window"))
        assertTrue(ci.contains("xvfb-run"))
        assertTrue(release.contains("xvfb-run"))
        assertTrue(ci.contains("帮你Draw window OK:"))
        assertTrue(release.contains("帮你Draw window OK:"))
    }

    @Test
    fun `macOS CI and releases render one packaged frame`() {
        val ci = source(".github/workflows/ci.yml")
        val release = source(".github/workflows/release.yml")
        val ciMac = ci.substringAfter("\n  desktop-macos:\n")
        val releaseMac = release.substringAfter("\n  build-desktop-macos:\n")
            .substringBefore("\n  publish:\n")

        listOf(ciMac, releaseMac).forEach { workflow ->
            assertTrue(workflow.contains("--smoke-window"))
            assertTrue(workflow.contains("帮你Draw window OK:"))
        }
    }

    @Test
    fun `packaged runtime verification excludes the jlink JVM and is bounded`() {
        val workflows = listOf(
            source(".github/workflows/ci.yml"),
            source(".github/workflows/release.yml"),
        )

        workflows.forEach { workflow ->
            assertTrue(workflow.contains("! -path '*/runtime/*'"))
            assertTrue(workflow.contains("timeout 60s \"\$LAUNCHER\" --verify-runtime"))
            assertTrue(
                workflow.contains(
                    "perl -e 'alarm shift; exec @ARGV' 60 \"\$LAUNCHER\" --verify-runtime",
                ),
            )
            assertEquals(2, "--verify-runtime".toRegex().findAll(workflow).count())
        }
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
