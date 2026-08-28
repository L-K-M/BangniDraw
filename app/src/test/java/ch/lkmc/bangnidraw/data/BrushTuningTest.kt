package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.WatercolorBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrushTuningTest {

    @Test
    fun `watercolor applies size and flow but rejects legacy opacity`() {
        val preset = BrushPreset(
            id = "watercolor",
            name = "Watercolor",
            size = 20f,
            sizeMax = 400f,
            opacity = 1f,
            flow = 0.4f,
            mixing = true,
            bufferMode = BufferMode.Accumulate,
            watercolor = WatercolorBehavior(),
        )

        val tuned = preset.applyTuning(
            BrushTuning(
                size = 28f,
                opacity = 0.2f,
                flow = 0.63f,
            ),
        )

        assertEquals(28f, tuned.size)
        assertEquals(0.63f, tuned.flow)
        assertEquals(1f, tuned.opacity)

        val persisted = tuned.persistedTuning()
        assertEquals(28f, persisted.size)
        assertEquals(0.63f, persisted.flow)
        assertNull(persisted.opacity)
    }

    @Test
    fun `ordinary brush applies opacity and does not persist flow tuning`() {
        val preset = BrushPreset(
            id = "ink",
            name = "Ink",
            size = 10f,
            opacity = 0.8f,
            flow = 0.5f,
        )

        val tuned = preset.applyTuning(
            BrushTuning(
                size = 14f,
                opacity = 0.35f,
                flow = 0.9f,
            ),
        )

        assertEquals(14f, tuned.size)
        assertEquals(0.35f, tuned.opacity)
        assertEquals(0.5f, tuned.flow)

        val persisted = tuned.persistedTuning()
        assertEquals(14f, persisted.size)
        assertEquals(0.35f, persisted.opacity)
        assertNull(persisted.flow)
    }
}
