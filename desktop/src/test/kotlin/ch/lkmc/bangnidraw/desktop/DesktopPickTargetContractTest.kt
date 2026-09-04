package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An armed panel pick must not outlive the user's mind.
 *
 * `beginPickInto` points the *next* eyedropper read at a dish well or a
 * palette swatch instead of the paint colour, and borrows the tool to do it.
 * Nothing ends that arming except using it — so a user who arms a pick, thinks
 * better of it and goes back to a brush leaves it set, and their next
 * eyedropper read, minutes later and deliberate, silently lands in the well.
 * Every explicit tool choice therefore cancels it.
 *
 * Pinned against the source because `DesktopShellState` needs a GL engine and
 * a DataStore to construct, which a JVM unit test has neither of; this is the
 * same shape the other desktop contract tests use for the same reason.
 */
class DesktopPickTargetContractTest {

    @Test
    fun `every explicit tool choice cancels an armed pick`() {
        for (entry in listOf("fun selectPreset(", "fun eraserTap(", "fun selectSecondary(")) {
            assertTrue(
                "cancelPendingPick()" in body(entry),
                "$entry can leave a panel pick armed",
            )
        }
    }

    @Test
    fun `arming a pick is not cancelled by the tool it selects`() {
        // beginPickInto borrows the eyedropper, which routes through
        // selectSecondary — so an unguarded cancel there would clear the
        // arming it was just given.
        val body = body("fun selectSecondary(")

        assertTrue(
            "!= DesktopSecondaryTool.EYEDROPPER" in body.replace(Regex("\\s+"), " "),
            "selectSecondary cancels the arming that selected it",
        )
    }

    @Test
    fun `cancelling drops the borrow instead of returning it`() {
        // Returning it would move the tool, and the user just chose one.
        val body = body("private fun cancelPendingPick(")

        assertTrue("borrowing = false" in body, "the borrow survives a cancelled pick")
        assertTrue(
            "returnEyedropper()" !in body,
            "cancelling a pick moves the tool the user just chose",
        )
    }

    private fun body(marker: String): String {
        val source = File(repositoryRoot(), STATE_PATH).readText()
        val from = source.indexOf(marker)
        if (from < 0) fail("missing source marker: $marker")

        // To the next declaration at the same indentation, which is where a
        // function body ends in this file's style.
        val end = source.indexOf("\n    fun ", from + marker.length)
            .let { if (it < 0) source.length else it }
        val privateEnd = source.indexOf("\n    private fun ", from + marker.length)
            .let { if (it < 0) source.length else it }
        return source.substring(from, minOf(end, privateEnd))
    }

    private fun repositoryRoot(): File {
        val start = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: fail("repository root not found above $start")
    }

    private companion object {
        const val STATE_PATH =
            "desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopShellState.kt"
    }
}
