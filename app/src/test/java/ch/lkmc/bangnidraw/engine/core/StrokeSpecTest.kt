package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StrokeSpecTest {

    @Test
    fun `the measured pressure ceiling replaces the provisional opacity`() {
        val provisional = StrokeSpec(
            layerId = LayerId("ink"),
            mode = StrokeMode.PAINT,
            opacity = 0.9f,
            grainMode = GrainMode.Procedural,
        )

        val finished = provisional.withOpacityCeiling(0.36f)

        assertEquals(0.9f, provisional.opacity, "the pen-down spec stays immutable")
        assertEquals(0.36f, finished.opacity)
        assertEquals(GrainMode.Procedural, finished.grainMode, "unrelated stroke state survives pen-up")
    }
}
