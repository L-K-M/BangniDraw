package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.SmudgeParams
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

    @Test
    fun `smudge routes one RMW stroke`() {
        val context = RecordingContext()
        val tool = SmudgeTool(SmudgeParams(size = 55f))

        tool.onStrokeBegin(sample, context)
        tool.onStrokeSample(sample, context)
        tool.onStrokeEnd(context)

        assertEquals(listOf("smudge:55.0", "rmw-sample", "rmw-commit"), context.events)
    }

    @Test
    fun `blur cancel restores its direct writes`() {
        val context = RecordingContext()
        val tool = BlurTool(BlurParams(size = 70f))

        tool.onStrokeBegin(sample, context)
        tool.onStrokeSample(sample, context)
        tool.onStrokeCancel(context)

        assertEquals(listOf("blur:70.0", "rmw-sample", "rmw-cancel"), context.events)
    }

    @Test
    fun `fill routes one request and cancellation`() {
        val context = RecordingContext()
        val tool = FillTool(FillParams(expand = 3))

        tool.onStrokeBegin(sample, context)
        tool.onStrokeSample(sample, context)
        tool.onStrokeCancel(context)

        assertEquals(listOf("fill:3:3.0,4.0", "fill-cancel"), context.events)
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

        override fun beginSmudge(params: SmudgeParams, input: StrokeInput) {
            events += "smudge:${params.size}"
        }

        override fun beginBlur(params: BlurParams, input: StrokeInput) {
            events += "blur:${params.size}"
        }

        override fun sampleRmw(input: StrokeInput) {
            events += "rmw-sample"
        }

        override fun commitRmw() {
            events += "rmw-commit"
        }

        override fun cancelRmw() {
            events += "rmw-cancel"
        }

        override fun beginFill(params: FillParams, input: StrokeInput) {
            events += "fill:${params.expand}:${input.x},${input.y}"
        }

        override fun cancelFill() {
            events += "fill-cancel"
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
