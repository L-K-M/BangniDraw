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
                TOOL_READ in section(HANDLER, entry),
                "$entry decides on the tool and must read it",
            )
        }

        // The four that do not: a move and a lift continue a gesture whose
        // tool was settled at its down, and appendPredicted takes position,
        // pressure, tilt, orientation and time.
        for (entry in listOf(POINTER_MOVE, POINTER_UP, HOVER_MOVE, APPEND_PREDICTED)) {
            assertFalse(
                TOOL_READ in section(HANDLER, entry),
                "$entry is fed by a fill that clears the tool, so it must not read it — " +
                    "switch its call sites in AndroidCanvasInput/Predictor back to the " +
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

    /**
     * One member's declaration and body: from its signature to wherever the
     * next member of the class begins.
     *
     * The end is found generically rather than named, so moving a member —
     * `appendPredicted` to the bottom of the class, say — cannot turn this
     * pin into a failure that blames a rename. Naming the *next* member would
     * couple every entry here to the layout of a file this change does not
     * touch. Running to the end of the source instead would be worse than
     * brittle: `appendPredicted`'s region would then swallow `onPointerDown`,
     * whose `.tool` read is exactly what the negative assertions look for, so
     * the test would fail on correct code.
     *
     * The raw source, not the canonicalized one: member boundaries are the
     * one thing indentation is load-bearing for.
     */
    private fun section(path: String, member: String): String {
        val source = ContractTestSources.read(path)
        val declaration = Regex(MEMBER_INDENT + MODIFIERS + "fun " + Regex.escape(member) + """\(""")
        val start = declaration.find(source) ?: fail("missing fun $member( in $path — renamed?")
        val end = NEXT_MEMBER.find(source, start.range.last)?.range?.first ?: source.length

        return source.substring(start.range.first, end)
    }

    private companion object {
        const val HANDLER =
            "engine-core/src/jvmShared/kotlin/ch/lkmc/bangnidraw/input/CanvasTouchHandler.kt"
        const val ADAPTER = "app/src/main/java/ch/lkmc/bangnidraw/input/AndroidCanvasInput.kt"
        const val PREDICTOR = "app/src/main/java/ch/lkmc/bangnidraw/input/Predictor.kt"

        // `.tool` rather than a `\btool\b` regex, which would also catch
        // `with(sample) { tool }`: the source is scanned with its comments,
        // since nothing here strips them, and the wider pattern hits the
        // prose that explains this very invariant. The runtime guarantee is
        // the nullable field — a consumer reading it gets `null`, not a
        // confident wrong answer — and this pin is the tripwire in front of
        // it, not the guarantee itself.
        const val TOOL_READ = ".tool"

        /**
         * A class member's declaration, which is where the previous one ends.
         *
         * Any stack of modifiers, and properties as well as functions: a
         * boundary this does not recognize is not a failed match but a
         * silently *widened* region, which for the four negative entries
         * means a `.tool` read from a swallowed member failing the pin on
         * correct code. `onPointerDown` gaining an `override` — the very
         * refactor R-237 discusses — would have done exactly that to
         * `appendPredicted`.
         *
         * The trailing `\s` matters: without it `fun` also matches
         * `fundamentally` and `val` matches `validate(`.
         */
        const val MEMBER_INDENT = """\n {4}"""
        const val MODIFIERS =
            """(?:(?:private|internal|protected|public|override|suspend|inline|""" +
                """operator|infix|open|final)\s+)*"""
        val NEXT_MEMBER = Regex(MEMBER_INDENT + MODIFIERS + """(?:fun|val|var)\s""")

        // Names, not signatures: the declaration is matched at its member
        // indent and only up to the open paren, so a wrapped parameter list
        // cannot turn a layout change into a "renamed?" failure.
        const val POINTER_DOWN = "onPointerDown"
        const val POINTER_MOVE = "onPointerMove"
        const val POINTER_UP = "onPointerUp"
        const val HOVER_ENTER = "onHoverEnter"
        const val HOVER_MOVE = "onHoverMove"
        const val APPEND_PREDICTED = "appendPredicted"

        // Compact spelling: these are matched against readCompact.
        const val FULL_SET = "sample.set("
        const val FULL_HOVER = "sample.setHover("
        const val BARE_SET = "sample.setWithoutTool("
        const val BARE_HOVER = "sample.setHoverWithoutTool("
    }
}
