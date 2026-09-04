package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the input-path rules the desktop shell shares with `:app`'s
 * `CanvasScreen`. Each of these was wrong on the shell at some point and the
 * failure is silent — a lost stroke, a zoom that barely moves, a tap slop
 * measured in the wrong unit.
 */
class DesktopInputContractTest {

    @Test
    fun `pen-up reads the tool on its own thread, not in the GL completion`() {
        val end = between(
            source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt"),
            "override fun onStrokeEnd(",
            "override fun onStrokeCancel(",
        )

        // `DesktopShell.tool` is a plain field written by the Compose pointer
        // loop. `onStrokeEnd` runs there too, but `endStroke`'s completion
        // runs on the GL thread — so the recent-colour callback is captured
        // before the post, not read from inside it. Order, because both spell
        // the same call and only one of them is a race.
        val capture = end.indexOf("DesktopShell.tool?.onPainted")
        val commit = end.indexOf("engine.endStroke(")
        if (capture < 0) fail("pen-up no longer captures the recent-colour callback")
        if (commit < 0) fail("pen-up no longer commits the stroke")
        assertTrue(capture < commit, "the tool is read inside the GL completion")
        // Only the completion block: `finishPick` follows it in the file and
        // reads the tool legitimately, on this same input thread.
        val completion = end.substring(commit).substringBefore("\n    }")
        assertFalse(
            completion.contains("DesktopShell.tool"),
            "the GL completion reads DesktopShell.tool across threads",
        )
    }

    @Test
    fun `ring exhaustion at pen-up commits what was stamped`() {
        val end = between(
            source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt"),
            "override fun onStrokeEnd(",
            "override fun onStrokeCancel(",
        )

        // `:app` does `driver.cancel(); break` and then still calls
        // endStroke. Cancelling here instead would discard the whole stroke.
        assertTrue(end.contains("break"), "pen-up must break out of the fill loop, not return")
        assertTrue(end.contains("engine.endStroke("), "pen-up must still commit")
        assertFalse(
            end.contains("engine.cancelStroke()"),
            "pen-up must not cancel; that is onStrokeCancel's job",
        )
    }

    @Test
    fun `scroll zoom passes wheel notches, not pixels`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        // CanvasTouchHandler.onScroll's parameter is `ticks`, and ScrollZoom
        // raises STEP_PER_NOTCH to it. Dividing first made a notch a 0.35%
        // zoom; ScrollZoom.MAX_TICKS_PER_EVENT already bounds a fling.
        assertTrue(main.contains("-change.scrollDelta.y,"))
        assertFalse(main.contains("SCROLL_PIXELS_PER_TICK"))
    }

    @Test
    fun `the touch handler is built with the display density`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        // GestureArbiter converts TAP_SLOP_DP to px once at construction.
        assertTrue(main.contains("LocalDensity.current.density"))
        assertFalse(main.contains("CanvasTouchHandler(density = 1f"))
    }

    @Test
    fun `undo and redo go through the history door, not the cancel door`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val document = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopDocument.kt")
        val history = between(engine, "private fun applyHistory(", "// ---------------------------------------------------------- document")

        // restoreCancelledRmw never reaches WatercolorEditPolicy, so wetness
        // from an undone watercolor stroke would survive the undo.
        assertTrue(history.contains("DesktopUndoOps.ops("))
        assertTrue(document.contains("PixelOp.Restore(layerId, tiles)"))
        assertTrue(history.contains("SandwichPolicy.Op.UndoRedo"))
        // The word appears in the comment explaining why it is not used;
        // what must be absent is the call.
        assertFalse(history.contains("renderer.restoreCancelledRmw("))
        // The cancel path still owns that door.
        assertTrue(engine.contains("restoreCancelledRmw(spec.layerId, images)"))
    }

    @Test
    fun `wet paint expires on a clock while the canvas is idle`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val loop = between(engine, "override fun pumpGl(", "private fun initializeRenderer(")

        assertTrue(loop.contains("pumpWetOverlay()"), "the GL loop must tick the wet overlay")
        assertTrue(engine.contains("hasWatercolorOverlay()"))
        assertTrue(engine.contains("refreshWatercolorOverlay()"))
    }

    /** Requires the end marker to follow the start one; see the other contract tests. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, startIndex = from + 1)
        check(from >= 0 && to > from) { "markers not found or misordered: $start .. $end" }
        return source.substring(from, to)
    }

    private fun source(path: String): String = File(repoRoot(), path).readText(Charsets.UTF_8)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }
}
