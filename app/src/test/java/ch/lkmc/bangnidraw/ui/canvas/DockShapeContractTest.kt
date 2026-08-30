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
        val rail = ContractTestSources.read(TOOL_RAIL_PATH)
        val dock = rail.substringAfter("private fun Dock(").substringBefore("@Composable")

        assertTrue("shape = MaterialTheme.shapes.large.copy(" in dock, "dock derives its shape from the shared large shape")
        assertTrue("bottomStart = CornerSize(0.dp)" in dock, "the bottom start corner stays flush with the window edge")
        assertTrue("bottomEnd = CornerSize(0.dp)" in dock, "the bottom end corner stays flush with the window edge")
    }

    private companion object {
        const val TOOL_RAIL_PATH = "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ToolRail.kt"
    }
}
