package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.StrokeInput
import ch.lkmc.bangnidraw.engine.core.ToolKind

open class BrushTool(private val preset: BrushPreset) : Tool {
    final override val kind: ToolKind = ToolKind.Brush(preset)

    final override fun onStrokeBegin(input: StrokeInput, context: StrokeContext) {
        context.beginBrush(preset, input)
    }

    final override fun onStrokeSample(input: StrokeInput, context: StrokeContext) {
        context.sampleBrush(input)
    }

    final override fun onStrokeEnd(context: StrokeContext) {
        context.commitBrush()
    }

    final override fun onStrokeCancel(context: StrokeContext) {
        context.cancelBrush()
    }
}
