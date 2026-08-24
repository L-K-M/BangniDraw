package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §3.12. */
class CanvasPresetsTest {

    private fun device(totalGib: Double, lowRam: Boolean = false) = DeviceMemory(
        totalMemBytes = (totalGib * (1L shl 30)).toLong(),
        isLowRamDevice = lowRam,
        largeMemoryClassMb = 512,
        glMaxArrayLayers = 256,
        glMaxTextureSize = 4096,
    )

    private fun budget(totalGib: Double, lowRam: Boolean = false) =
        MemoryBudget.compute(device(totalGib, lowRam), CanvasSize(2048, 2048))

    @Test
    fun `the preset list is the fixed one of 10 section 4, small to large, annotated with maxLayers`() {
        val result = budget(8.0)
        val presets = CanvasPresets.forDevice(result)
        assertEquals(
            listOf(
                CanvasPresetId.PHONE_SKETCH,
                CanvasPresetId.SQUARE_2048,
                CanvasPresetId.TABLET,
                CanvasPresetId.LARGE_4096,
            ),
            presets.map { it.id },
        )
        assertEquals(
            listOf(
                CanvasSize(1080, 1920),
                CanvasSize(2048, 2048),
                CanvasSize(2560, 1600),
                CanvasSize(4096, 4096),
            ),
            presets.map { it.size },
        )
        // "Small to large" is by what a layer costs — tiles, not pixels: a
        // 2560x1600 tablet canvas is fewer pixels than a 2048 square but more
        // tiles, and tiles are what the budget spends.
        var tiles = 0
        var previousLayers = Int.MAX_VALUE
        for (p in presets) {
            assertTrue(p.size.tilesPerLayer > tiles, "${p.id} costs no more tiles than the preset before it")
            tiles = p.size.tilesPerLayer
            assertTrue(p.maxLayers <= previousLayers, "${p.id} holds more layers than a smaller preset")
            previousLayers = p.maxLayers
            assertEquals(
                MemoryBudget.maxLayersFor(result.gpuTileBudgetBytes, p.size),
                p.maxLayers,
                "${p.id} must carry the budget's own answer, not its own arithmetic",
            )
        }
    }

    @Test
    fun `every preset has even sides and can be turned both ways`() {
        for (p in CanvasPresets.forDevice(budget(8.0))) {
            assertTrue(p.size.width % 2 == 0 && p.size.height % 2 == 0, "${p.id} has an odd side")
            val landscape = p.oriented(landscape = true)
            val portrait = p.oriented(landscape = false)
            assertTrue(landscape.width >= landscape.height, "${p.id} landscape is not wide")
            assertTrue(portrait.height >= portrait.width, "${p.id} portrait is not tall")
            assertEquals(landscape.width, portrait.height, "orientation only swaps the sides")
            assertEquals(landscape.height, portrait.width)
            assertEquals(p.isSquare, landscape == portrait, "a square preset hides the orientation toggle")
        }
    }

    @Test
    fun `a preset above maxCanvasEdge is offered disabled, never dropped`() {
        val result = budget(4.0, lowRam = true)
        assertEquals(3584, result.maxCanvasEdge, "the low-RAM ceiling of 10 section 4")
        val presets = CanvasPresets.forDevice(result)
        assertEquals(4, presets.size, "nothing is dropped from the list")
        assertEquals(
            listOf(true, true, true, false),
            presets.map { it.enabled },
            "only Large 4096 is over this device's ceiling",
        )
    }

    @Test
    fun `every enabled preset fits within its own budget`() {
        for (totalGib in listOf(2.0, 4.0, 8.0, 12.0)) {
            for (lowRam in listOf(false, true)) {
                val result = budget(totalGib, lowRam)
                for (p in CanvasPresets.forDevice(result).filter { it.enabled }) {
                    assertTrue(
                        maxOf(p.size.width, p.size.height) <= result.maxCanvasEdge,
                        "${p.id} is offered at $totalGib GiB (lowRam=$lowRam) but exceeds maxCanvasEdge",
                    )
                    assertTrue(
                        p.size.tilesPerLayer <= CanvasPresets.MAX_TILES,
                        "${p.id} needs ${p.size.tilesPerLayer} tiles, over the format's ${CanvasPresets.MAX_TILES}",
                    )
                    assertTrue(p.maxLayers >= 1, "${p.id} is offered but holds no layers")
                }
            }
        }
    }

    @Test
    fun `a custom size above maxCanvasEdge is refused with the reason`() {
        val result = budget(4.0, lowRam = true)
        val refused = CanvasPresets.custom(CanvasSize(4096, 1024), result)
        assertIs<CustomSizeResult.Refused>(refused)
        assertEquals(SizeRefusal.TOO_LARGE_FOR_DEVICE, refused.reason)
    }

    @Test
    fun `a custom size below the format minimum is refused`() {
        val refused = CanvasPresets.custom(CanvasSize(255, 1024), budget(8.0))
        assertIs<CustomSizeResult.Refused>(refused)
        assertEquals(SizeRefusal.TOO_SMALL, refused.reason)
    }

    @Test
    fun `a custom size over the format's tile ceiling is refused before the device is consulted`() {
        val generous = MemoryBudget.compute(device(64.0), CanvasSize(2048, 2048))
        val refused = CanvasPresets.custom(CanvasSize(8192, 8448), generous)
        assertIs<CustomSizeResult.Refused>(refused)
        assertEquals(SizeRefusal.TOO_MANY_TILES, refused.reason)
    }

    @Test
    fun `a custom size is bounded before the tile arithmetic that could overflow`() {
        // CanvasSize.tilesX computes (width + 255) / 256, which overflows to a
        // NEGATIVE tile count near Int.MAX_VALUE — so a MAX_TILES guard alone
        // would wave the very largest sizes through. This is the one entry
        // point fed by numbers a user typed.
        val result = budget(8.0)
        for (huge in listOf(Int.MAX_VALUE, Int.MAX_VALUE - 100, TileGrid.MAX_EDGE + 1)) {
            val refused = CanvasPresets.custom(CanvasSize(huge, 1024), result)
            assertIs<CustomSizeResult.Refused>(refused, "a ${huge}px side must be refused")
            assertEquals(SizeRefusal.TOO_MANY_TILES, refused.reason)
            val flipped = CanvasPresets.custom(CanvasSize(1024, huge), result)
            assertIs<CustomSizeResult.Refused>(flipped, "a ${huge}px side must be refused")
            assertEquals(SizeRefusal.TOO_MANY_TILES, flipped.reason)
        }
    }

    @Test
    fun `an accepted custom size carries the budget's layer count`() {
        val result = budget(8.0)
        val ok = CanvasPresets.custom(CanvasSize(3072, 2048), result)
        assertIs<CustomSizeResult.Ok>(ok)
        assertEquals(CanvasPresetId.CUSTOM, ok.preset.id)
        assertEquals(
            MemoryBudget.maxLayersFor(result.gpuTileBudgetBytes, CanvasSize(3072, 2048)),
            ok.preset.maxLayers,
        )
    }

    @Test
    fun `the default preset for a phone fits a phone budget`() {
        for (result in listOf(budget(2.0), budget(2.0, lowRam = true))) {
            val presets = CanvasPresets.forDevice(result)
            val default = presets[CanvasPresets.defaultIndex(presets)]
            assertTrue(default.enabled, "the dialog must not open on a disabled row")
            assertTrue(
                default.maxLayers >= PerfConstants.MIN_USEFUL_LAYERS,
                "${default.id} only holds ${default.maxLayers} layers on a phone",
            )
        }
    }
}
