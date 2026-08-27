package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewCanvasPolicyTest {

    private val presets = CanvasPresets.forDevice(
        MemoryBudget.compute(
            device = DeviceMemory(
                totalMemBytes = 8L * (1L shl 30),
                isLowRamDevice = false,
                largeMemoryClassMb = 512,
                glMaxArrayLayers = 256,
                glMaxTextureSize = 4096,
            ),
            canvas = CanvasSize(2048, 2048),
        ),
    )

    @Test
    fun `a portrait phone defaults to the phone-sized preset and portrait orientation`() {
        val defaults = NewCanvasDefaultsPolicy.forWindow(
            presets = presets,
            windowWidthPx = 1080,
            windowHeightPx = 2400,
        )

        assertEquals(CanvasPresetId.PHONE_SKETCH, presets[defaults.presetIndex].id)
        assertEquals(CanvasOrientation.PORTRAIT, defaults.orientation)
    }

    @Test
    fun `a landscape tablet defaults to the tablet preset and landscape orientation`() {
        val defaults = NewCanvasDefaultsPolicy.forWindow(
            presets = presets,
            windowWidthPx = 2560,
            windowHeightPx = 1600,
        )

        assertEquals(CanvasPresetId.TABLET, presets[defaults.presetIndex].id)
        assertEquals(CanvasOrientation.LANDSCAPE, defaults.orientation)
    }

    @Test
    fun `the selector falls back to the smallest enabled preset when none fits one to one`() {
        val defaults = NewCanvasDefaultsPolicy.forWindow(
            presets = presets,
            windowWidthPx = 720,
            windowHeightPx = 1280,
        )

        assertEquals(CanvasPresetId.PHONE_SKETCH, presets[defaults.presetIndex].id)
    }

    @Test
    fun `the selector never chooses a disabled preset`() {
        val constrained = presets.map { preset ->
            if (preset.id == CanvasPresetId.LARGE_4096) preset.copy(enabled = false) else preset
        }

        val defaults = NewCanvasDefaultsPolicy.forWindow(
            presets = constrained,
            windowWidthPx = 4096,
            windowHeightPx = 4096,
        )

        assertEquals(CanvasPresetId.TABLET, constrained[defaults.presetIndex].id)
    }

    @Test
    fun `preset dimensions follow the chosen orientation`() {
        val tablet = presets.single { it.id == CanvasPresetId.TABLET }

        assertEquals(CanvasSize(2560, 1600), tablet.oriented(CanvasOrientation.LANDSCAPE))
        assertEquals(CanvasSize(1600, 2560), tablet.oriented(CanvasOrientation.PORTRAIT))
    }

    @Test
    fun `compact custom fields share the second row without overflowing`() {
        for (contentWidth in listOf(232f, 272f)) {
            val layout = NewCanvasLayoutPolicy.customSizeFields(
                contentWidthDp = contentWidth,
                fontScale = 1f,
            )

            assertEquals(CustomSizeFieldArrangement.ROW, layout.arrangement)
            assertTrue(layout.fieldWidthDp > 0f)
            assertTrue(layout.occupiedWidthDp <= contentWidth)
        }
    }

    @Test
    fun `custom fields stack at two hundred percent text scale`() {
        for (contentWidth in listOf(232f, 272f)) {
            val layout = NewCanvasLayoutPolicy.customSizeFields(
                contentWidthDp = contentWidth,
                fontScale = 2f,
            )

            assertEquals(CustomSizeFieldArrangement.COLUMN, layout.arrangement)
            assertEquals(contentWidth, layout.fieldWidthDp)
        }
    }

    @Test
    fun `zero-width custom fields are marked unusable`() {
        val layout = NewCanvasLayoutPolicy.customSizeFields(
            contentWidthDp = 0f,
            fontScale = 1f,
        )

        assertEquals(CustomSizeFieldArrangement.COLUMN, layout.arrangement)
        assertTrue(!layout.hasUsableWidth)
    }

    @Test
    fun `unbounded custom fields keep finite geometry`() {
        val layout = NewCanvasLayoutPolicy.customSizeFields(
            contentWidthDp = Float.POSITIVE_INFINITY,
            fontScale = 1f,
        )

        assertEquals(CustomSizeFieldArrangement.ROW, layout.arrangement)
        assertTrue(layout.fieldWidthDp.isFinite())
        assertTrue(layout.occupiedWidthDp.isFinite())
    }

    @Test
    fun `paper visuals sit inside minimum accessible targets`() {
        assertTrue(NewCanvasLayoutPolicy.PAPER_TARGET_DP >= 48f)
        assertEquals(28f, NewCanvasLayoutPolicy.PAPER_VISUAL_DP)
        assertTrue(NewCanvasLayoutPolicy.PAPER_TARGET_DP > NewCanvasLayoutPolicy.PAPER_VISUAL_DP)
    }
}
