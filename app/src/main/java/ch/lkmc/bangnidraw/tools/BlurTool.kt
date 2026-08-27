package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.StrokeInput
import ch.lkmc.bangnidraw.engine.core.ToolKind

class BlurTool(private val params: BlurParams = BlurParams()) : Tool {
    override val kind: ToolKind = ToolKind.Blur(params)

    override fun onStrokeBegin(input: StrokeInput, context: StrokeContext) {
        context.beginBlur(params, input)
    }

    override fun onStrokeSample(input: StrokeInput, context: StrokeContext) {
        context.sampleRmw(input)
    }

    override fun onStrokeEnd(context: StrokeContext) {
        context.commitRmw()
    }

    override fun onStrokeCancel(context: StrokeContext) {
        context.cancelRmw()
    }
}
