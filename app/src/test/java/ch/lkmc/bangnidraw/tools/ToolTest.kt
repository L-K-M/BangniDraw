package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.StrokeInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolTest {

    private val sample = StrokeInput().also { it.set(3f, 4f) }

    @Test
    fun `brush routes one complete stroke`() {
        val context = RecordingContext()
        val tool = BrushTool(BrushPresets.DEFAULT)

        tool.onStrokeBegin(sample, context)
        tool.onStrokeSample(sample, context)
        tool.onStrokeEnd(context)

        assertEquals(listOf("brush:builtin.ink_pen", "brush-sample", "brush-commit"), context.events)
    }

    @Test
    fun `eraser rejects a painting preset`() {
        assertFailsWith<IllegalArgumentException> { EraserTool(BrushPresets.DEFAULT) }
    }

    @Test
    fun `eyedropper cancel restores the prior color`() {
        val context = RecordingContext()
        val tool = EyedropperTool(EyedropperParams(radius = 1))

        tool.onStrokeBegin(sample, context)
        tool.onStrokeSample(sample, context)
        tool.onStrokeCancel(context)

        assertEquals(listOf("pick:1", "pick-sample", "pick-cancel"), context.events)
    }

    private class RecordingContext : StrokeContext {
        val events = ArrayList<String>()

        override fun beginBrush(preset: BrushPreset, input: StrokeInput) {
            events += "brush:${preset.id}"
        }

        override fun sampleBrush(input: StrokeInput) {
            events += "brush-sample"
        }

        override fun commitBrush() {
            events += "brush-commit"
        }

        override fun cancelBrush() {
            events += "brush-cancel"
        }

        override fun beginColorPick(params: EyedropperParams, input: StrokeInput) {
            events += "pick:${params.radius}"
        }

        override fun sampleColorPick(input: StrokeInput) {
            events += "pick-sample"
        }

        override fun commitColorPick() {
            events += "pick-commit"
        }

        override fun cancelColorPick() {
            events += "pick-cancel"
        }
    }
}
