package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.StrokeInput
import ch.lkmc.bangnidraw.engine.core.ToolKind

class EyedropperTool(private val params: EyedropperParams = EyedropperParams()) : Tool {
    override val kind: ToolKind = ToolKind.Eyedropper(params)

    override fun onStrokeBegin(input: StrokeInput, context: StrokeContext) {
        context.beginColorPick(params, input)
    }

    override fun onStrokeSample(input: StrokeInput, context: StrokeContext) {
        context.sampleColorPick(input)
    }

    override fun onStrokeEnd(context: StrokeContext) {
        context.commitColorPick()
    }

    override fun onStrokeCancel(context: StrokeContext) {
        context.cancelColorPick()
    }
}
