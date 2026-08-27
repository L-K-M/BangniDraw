package ch.lkmc.bangnidraw.engine.mixbox

import ch.lkmc.bangnidraw.engine.core.HsvColor
import com.scrtwpns.Mixbox
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixboxMixerTest {
    private val mixer = MixboxMixer()

    @Test
    fun `blue plus yellow is green`() {
        val hsv = HsvColor.fromArgb(mixer.mix(BLUE, YELLOW, 0.5f))

        assertTrue(hsv.h in 70f..170f, "hue ${hsv.h}")
        assertTrue(hsv.s > 0.3f)
        assertTrue(hsv.v > 0.15f)
        assertTrue(mixer.isPigment)
    }

    @Test
    fun `endpoints are exact`() {
        assertEquals(BLUE, mixer.mix(BLUE, YELLOW, 0f))
        assertEquals(YELLOW, mixer.mix(BLUE, YELLOW, 1f))
    }

    @Test
    fun `latent size and palette round trips match Mixbox`() {
        assertEquals(Mixbox.LATENT_SIZE, mixer.latentSize)

        for (color in PALETTE) {
            val latent = FloatArray(mixer.latentSize)
            mixer.toLatent(color, latent)
            assertChannelsWithinOneStep(color, mixer.fromLatent(latent))
        }
    }

    @Test
    fun `mixing is symmetric within one channel step`() {
        val forward = mixer.mix(BLUE, YELLOW, 0.35f)
        val reverse = mixer.mix(YELLOW, BLUE, 0.65f)

        assertChannelsWithinOneStep(forward, reverse)
    }

    private fun assertChannelsWithinOneStep(expected: Int, actual: Int) {
        for (shift in CHANNEL_SHIFTS) {
            val expectedChannel = (expected ushr shift) and CHANNEL_MASK
            val actualChannel = (actual ushr shift) and CHANNEL_MASK
            assertTrue(abs(expectedChannel - actualChannel) <= 1, "$expected vs $actual at $shift")
        }
    }

    private companion object {
        const val BLUE = 0xFF0000FF.toInt()
        const val YELLOW = 0xFFFFFF00.toInt()
        const val CHANNEL_MASK = 0xFF
        val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
        val PALETTE = intArrayOf(
            0xFFFEEC00.toInt(),
            0xFFFF2702.toInt(),
            0xFF190059.toInt(),
            0xFF003C32.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF141414.toInt(),
        )
    }
}
