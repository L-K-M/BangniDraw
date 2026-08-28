package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WatercolorWetKernelTest {

    @Test
    fun `timestamp channels round-trip every byte`() {
        val tick = 0xABCD
        val encoded = WatercolorWetKernel.encodeTick(tick)

        assertEquals(0xAB / 255f, encoded.high, EPSILON)
        assertEquals(0xCD / 255f, encoded.low, EPSILON)
        assertEquals(tick, WatercolorWetKernel.decodeTick(encoded.high, encoded.low))
    }

    @Test
    fun `lazy age decays water and saturation across tick wrap`() {
        val result = WatercolorWetKernel.step(
            center = cell(surfaceWater = 0.8f, updatedTick = 65_534, saturation = 0.6f),
            neighbors = dryNeighbors(),
            parameters = parameters(
                spread = 0f,
                flowMask = 0f,
                nowTick = 1,
            ),
        )
        val retention = 1f - 3f / WatercolorKernel.DRY_TICKS

        assertEquals(0.8f * retention, result.surfaceWater, EPSILON)
        assertEquals(0.6f * retention, result.saturation, EPSILON)
        assertEquals(1, WatercolorWetKernel.decodeTick(result.tickHigh, result.tickLow))
    }

    @Test
    fun `each neighbor receives water before diffusion`() {
        val eastSource = WatercolorWetKernel.step(
            center = cell(saturation = 1f),
            neighbors = dryNeighbors(eastSourceMask = 1f),
            parameters = parameters(waterLoad = 0.5f),
        )
        val noSource = WatercolorWetKernel.step(
            center = cell(saturation = 1f),
            neighbors = dryNeighbors(),
            parameters = parameters(waterLoad = 0.5f),
        )

        assertEquals(WatercolorKernel.MAX_DIFFUSION * 0.5f, eastSource.surfaceWater, EPSILON)
        assertEquals(0f, noSource.surfaceWater, EPSILON)
    }

    @Test
    fun `spread and flow mask bound the convex diffusion step`() {
        val result = WatercolorWetKernel.step(
            center = cell(surfaceWater = 1f, saturation = 1f),
            neighbors = dryNeighbors(),
            parameters = parameters(spread = 0.5f, flowMask = 0.25f),
        )
        val diffusion = 4f * WatercolorKernel.MAX_DIFFUSION * 0.5f * 0.25f

        assertEquals(1f - diffusion, result.surfaceWater, EPSILON)
        assertTrue(result.surfaceWater in 0f..1f)
    }

    @Test
    fun `paper valleys absorb faster and hold more water`() {
        val valley = WatercolorWetKernel.step(
            center = cell(surfaceWater = 1f),
            neighbors = dryNeighbors(),
            parameters = parameters(spread = 0f, paperRelief = 0f),
        )
        val peak = WatercolorWetKernel.step(
            center = cell(surfaceWater = 1f),
            neighbors = dryNeighbors(),
            parameters = parameters(spread = 0f, paperRelief = 1f),
        )
        val peakAbsorbed = WatercolorKernel.ABSORPTION_PER_STEP *
            WatercolorKernel.PAPER_ABSORPTION_MIN * WatercolorKernel.PAPER_CAPACITY_MIN

        assertEquals(WatercolorKernel.ABSORPTION_PER_STEP, valley.saturation, EPSILON)
        assertEquals(peakAbsorbed, peak.saturation, EPSILON)
        assertTrue(valley.saturation > peak.saturation)
        assertTrue(valley.surfaceWater < peak.surfaceWater)
    }

    @Test
    fun `saturation closes the remaining paper capacity`() {
        val result = WatercolorWetKernel.step(
            center = cell(surfaceWater = 0.1f, saturation = 0.98f),
            neighbors = dryNeighbors(),
            parameters = parameters(spread = 0f, paperRelief = 0f),
        )
        val absorbed = WatercolorKernel.ABSORPTION_PER_STEP * (1f - 0.98f)

        assertEquals(0.1f - absorbed, result.surfaceWater, EPSILON)
        assertEquals(0.98f + absorbed, result.saturation, EPSILON)
    }

    @Test
    fun `age-only mode skips supply diffusion and absorption`() {
        val result = WatercolorWetKernel.step(
            center = cell(
                surfaceWater = 0.8f,
                updatedTick = 0,
                saturation = 0.6f,
                sourceMask = 1f,
            ),
            neighbors = dryNeighbors(eastSourceMask = 1f),
            parameters = parameters(
                waterLoad = 1f,
                paperRelief = 0f,
                nowTick = WatercolorKernel.DRY_TICKS / 2,
                mode = WatercolorWetKernel.Mode.AGE_ONLY,
            ),
        )

        assertEquals(0.4f, result.surfaceWater, EPSILON)
        assertEquals(0.3f, result.saturation, EPSILON)
        assertEquals(
            WatercolorKernel.DRY_TICKS / 2,
            WatercolorWetKernel.decodeTick(result.tickHigh, result.tickLow),
        )
    }

    @Test
    fun `epoch rebase preserves wetness and only re-stamps the tick`() {
        // The rebase moves a live cell's 16-bit tick into the new epoch without
        // aging it; drying it would wipe live water at the ~109-minute device
        // uptime rollover (AGENTS.md: rebase "preserves water"). A genuinely dry
        // cell already stores 0, so preserving keeps it 0.
        val stale = WatercolorWetKernel.step(
            center = cell(surfaceWater = 0.8f, updatedTick = 5, saturation = 0.6f),
            neighbors = dryNeighbors(),
            parameters = parameters(
                nowTick = 5,
                mode = WatercolorWetKernel.Mode.EPOCH_REBASE,
            ),
        )
        val recent = WatercolorWetKernel.step(
            center = cell(surfaceWater = 0.8f, updatedTick = 65_534, saturation = 0.6f),
            neighbors = dryNeighbors(),
            parameters = parameters(
                nowTick = 1,
                mode = WatercolorWetKernel.Mode.EPOCH_REBASE,
            ),
        )

        assertEquals(0.8f, stale.surfaceWater, EPSILON)
        assertEquals(0.6f, stale.saturation, EPSILON)
        assertEquals(5, WatercolorWetKernel.decodeTick(stale.tickHigh, stale.tickLow))
        assertEquals(0.8f, recent.surfaceWater, EPSILON)
        assertEquals(0.6f, recent.saturation, EPSILON)
        assertEquals(1, WatercolorWetKernel.decodeTick(recent.tickHigh, recent.tickLow))
    }

    @Test
    fun `epoch rebase leaves a dry cell dry`() {
        val dry = WatercolorWetKernel.step(
            center = cell(surfaceWater = 0f, saturation = 0f, updatedTick = 5),
            neighbors = dryNeighbors(),
            parameters = parameters(
                nowTick = 5,
                mode = WatercolorWetKernel.Mode.EPOCH_REBASE,
            ),
        )

        assertEquals(0f, dry.surfaceWater, EPSILON)
        assertEquals(0f, dry.saturation, EPSILON)
    }

    @Test
    fun `paper relief is stable in canvas space`() {
        val first = WatercolorWetKernel.paperRelief(4.1f, 7.1f)
        val sameCell = WatercolorWetKernel.paperRelief(4.9f, 7.9f)
        val otherCell = WatercolorWetKernel.paperRelief(5.1f, 7.1f)

        assertEquals(first, sameCell, EPSILON)
        assertNotEquals(first, otherCell)
        assertEquals(0f, WatercolorWetKernel.paperRelief(-1f, -1f), EPSILON)
    }

    @Test
    fun `supplied water saturates instead of adding past one`() {
        val result = WatercolorWetKernel.step(
            center = cell(surfaceWater = 0.5f, sourceMask = 1f),
            neighbors = dryNeighbors(),
            parameters = parameters(
                waterLoad = 0.5f,
                spread = 0f,
                flowMask = 0f,
            ),
        )

        assertEquals(0.75f, result.surfaceWater, EPSILON)
    }

    private fun cell(
        surfaceWater: Float = 0f,
        updatedTick: Int = 0,
        saturation: Float = 0f,
        sourceMask: Float = 0f,
    ): WatercolorWetKernel.Cell {
        val stamp = WatercolorWetKernel.encodeTick(updatedTick)
        val stored = WatercolorWetKernel.StoredCell(
            surfaceWater = surfaceWater,
            tickHigh = stamp.high,
            tickLow = stamp.low,
            saturation = saturation,
        )

        return WatercolorWetKernel.Cell(stored, sourceMask)
    }

    private fun dryNeighbors(
        eastSourceMask: Float = 0f,
    ): WatercolorWetKernel.Neighbors = WatercolorWetKernel.Neighbors(
        north = cell(),
        east = cell(sourceMask = eastSourceMask),
        south = cell(),
        west = cell(),
    )

    private fun parameters(
        waterLoad: Float = 0f,
        spread: Float = 1f,
        flowMask: Float = 1f,
        paperRelief: Float = 1f,
        nowTick: Int = 0,
        mode: WatercolorWetKernel.Mode = WatercolorWetKernel.Mode.UPDATE,
    ): WatercolorWetKernel.Parameters = WatercolorWetKernel.Parameters(
        waterLoad = waterLoad,
        spread = spread,
        flowMask = flowMask,
        paperRelief = paperRelief,
        nowTick = nowTick,
        mode = mode,
    )

    private companion object {
        const val EPSILON = 1e-6f
    }
}
