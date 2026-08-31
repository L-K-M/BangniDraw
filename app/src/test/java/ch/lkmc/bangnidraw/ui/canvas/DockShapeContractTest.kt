package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The DOCK rail is the one posture flush against a window edge, so it rounds
 * only the corners that meet the canvas. Every other posture keeps the plain
 * rounded surface; a square-cornered dock read as a different object from the
 * rail it replaces.
 */
class DockShapeContractTest {

    @Test
    fun `the dock rounds its top corners and keeps the bottom flush`() {
        // Whitespace-normalized per the house rule for source-contract
        // tests: every needle below is a behavioral pin, and a mechanical
        // reformat that wrapped one of these argument lists would otherwise
        // fail it for a reason that has nothing to do with the dock's shape.
        val rail = ContractTestSources.read(TOOL_RAIL_PATH).replace(WHITESPACE, " ")
        // An absent delimiter must fail loudly as "Dock moved", not let the
        // whole file masquerade as the snippet and fail on a shape message.
        val dock = rail.substringAfter("private fun Dock(", missingDelimiterValue = "")
            .substringBefore("@Composable")
        assertTrue(dock.isNotBlank(), "Dock composable not found in ToolRail.kt — was it renamed?")

        assertTrue("shape = MaterialTheme.shapes.large.copy(" in dock, "dock derives its shape from the shared large shape")
        assertTrue("bottomStart = CornerSize(0.dp)" in dock, "the bottom start corner stays flush with the window edge")
        assertTrue("bottomEnd = CornerSize(0.dp)" in dock, "the bottom end corner stays flush with the window edge")
        // The other half of the contract: squaring the TOP corners too would
        // regress to the slab this shape exists to retire.
        assertTrue(
            "topStart" !in dock && "topEnd" !in dock,
            "the top corners keep the shared large radius",
        )
    }

    private companion object {
        const val TOOL_RAIL_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ToolRail.kt"
        val WHITESPACE = Regex("\\s+")
    }
}
