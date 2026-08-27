package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasResidentCapacityWiringTest {

    private val openLoaded = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt",
    ).readText()
        .substringAfter("private suspend fun openLoaded(")
        .substringBefore("private fun historyDir(")

    @Test
    fun `relisted resident tiles are checked before canvas state is published`() {
        val relist = openLoaded.indexOf("store.relistTiles(")
        val capacity = openLoaded.indexOf("TileCapacityPolicy.residentTilesFit(")
        val publish = openLoaded.indexOf("document = doc")
        val ready = openLoaded.indexOf("_uiState.value = readyState(")

        assertTrue(relist >= 0)
        assertTrue(capacity > relist)
        assertTrue(capacity < publish)
        assertTrue(capacity < ready)
        assertTrue(openLoaded.contains("UiState.Failed(R.string.canvas_over_capacity)"))
    }

    @Test
    fun `a sparse over-layer-cap document is not rejected at open`() {
        assertFalse(openLoaded.contains("TileCapacityPolicy.withinLayerCap("))
        assertFalse(openLoaded.contains("TileCapacityPolicy.hasTransientReserve("))
    }
}
