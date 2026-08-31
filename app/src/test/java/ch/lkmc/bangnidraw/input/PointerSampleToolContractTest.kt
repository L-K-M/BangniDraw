package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `PointerSample.tool` is filled only where it is read.
 *
 * Reading the tool costs a JNI call per sample on Android
 * (`MotionEvent.getToolType`), and the record port made it a mandatory
 * argument of every fill — so the historical-sample loop paid for it several
 * times a frame from a 240 Hz digitizer, and threw the answer away each time.
 * `setWithoutTool`/`setHoverWithoutTool` stop paying it on the four paths
 * whose consumers never look.
 *
 * That leaves [PointerSample.tool] holding whatever the last full fill wrote,
 * which is safe only while those consumers keep not looking. This is the pin
 * that makes it stay that way: a consumer that starts needing the tool fails
 * here and moves its call site back to the full `set`, rather than silently
 * reading the previous gesture's tool. The positive half is pinned too, so
 * the test cannot pass by the handler simply not using tools at all.
 */
class PointerSampleToolContractTest {

    @Test
    fun `only the two entries that decide on the tool read it`() {
        // A down decides palm rejection and the eraser end; a hover-enter
        // decides the cursor. Both must keep reading it.
        for (entry in listOf(POINTER_DOWN, HOVER_ENTER)) {
            assertTrue(
                TOOL_READ in section(HANDLER, entry.start, entry.end),
                "${entry.start} decides on the tool and must read it",
            )
        }

        // The four that do not: a move and a lift continue a gesture whose
        // tool was settled at its down, and appendPredicted takes position,
        // pressure, tilt, orientation and time.
        for (entry in listOf(POINTER_MOVE, POINTER_UP, HOVER_MOVE, APPEND_PREDICTED)) {
            assertFalse(
                TOOL_READ in section(HANDLER, entry.start, entry.end),
                "${entry.start} is fed by a fill that leaves tool stale, so it must not read " +
                    "it — switch its call sites in AndroidCanvasInput/Predictor back to the " +
                    "full set() first",
            )
        }
    }

    @Test
    fun `the adapter fills the tool once per contact and once per hover`() {
        val adapter = ContractTestSources.readCompact(ADAPTER)

        // Counted, not merely present: the point of the change is that the
        // OTHER paths stopped filling it, which only a count can say.
        assertEquals(
            1,
            adapter.split(FULL_SET).size - 1,
            "only ACTION_DOWN/POINTER_DOWN fills the tool for contact",
        )
        assertEquals(
            1,
            adapter.split(FULL_HOVER).size - 1,
            "only ACTION_HOVER_ENTER fills the tool for hover",
        )
        assertEquals(
            3,
            adapter.split(BARE_SET).size - 1,
            "the two move loops and the lift take the tool-less fill",
        )
        assertEquals(
            1,
            adapter.split(BARE_HOVER).size - 1,
            "the hover move takes the tool-less hover fill",
        )
    }

    @Test
    fun `the predictor asks the platform for no tool at all`() {
        // Every predicted sample is discarded-tool by construction, so the
        // whole file should have no reason to name the platform call.
        assertFalse(
            "getToolType" in ContractTestSources.readCompact(PREDICTOR),
            "predicted samples carry no tool; a getToolType here is a JNI call per " +
                "predicted point per frame with no reader",
        )
    }

    /** Whitespace-canonicalized, and loud when either delimiter moves. */
    private fun section(path: String, startMarker: String, endMarker: String): String {
        val source = ContractTestSources.readNormalized(path)
        val start = source.indexOf(startMarker)
        if (start < 0) fail("missing $startMarker in $path — renamed?")
        val end = source.indexOf(endMarker, start + startMarker.length)
        if (end <= start) fail("missing $endMarker after $startMarker in $path")

        return source.substring(start, end)
    }

    private data class Entry(val start: String, val end: String)

    private companion object {
        const val HANDLER =
            "engine-core/src/jvmShared/kotlin/ch/lkmc/bangnidraw/input/CanvasTouchHandler.kt"
        const val ADAPTER = "app/src/main/java/ch/lkmc/bangnidraw/input/AndroidCanvasInput.kt"
        const val PREDICTOR = "app/src/main/java/ch/lkmc/bangnidraw/input/Predictor.kt"

        const val TOOL_READ = ".tool"

        val POINTER_DOWN = Entry("fun onPointerDown(sample: PointerSample)", "fun onPointerMove(")
        val POINTER_MOVE = Entry("fun onPointerMove(sample: PointerSample)", "fun onPointerMoveEnd(")
        val POINTER_UP = Entry("fun onPointerUp(sample: PointerSample)", "fun onPointerCancel(")
        val HOVER_ENTER = Entry("fun onHoverEnter(sample: PointerSample)", "fun onHoverMove(")
        val HOVER_MOVE = Entry("fun onHoverMove(sample: PointerSample)", "fun onHoverExit(")
        val APPEND_PREDICTED =
            Entry("private fun appendPredicted(predicted: PointerSample)", "fun onPointerDown(")

        // Compact spelling: these are matched against readCompact.
        const val FULL_SET = "sample.set("
        const val FULL_HOVER = "sample.setHover("
        const val BARE_SET = "sample.setWithoutTool("
        const val BARE_HOVER = "sample.setHoverWithoutTool("
    }
}
