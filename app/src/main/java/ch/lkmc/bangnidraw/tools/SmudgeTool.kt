package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.SmudgeParams
import ch.lkmc.bangnidraw.engine.core.StrokeInput
import ch.lkmc.bangnidraw.engine.core.ToolKind

class SmudgeTool(private val params: SmudgeParams = SmudgeParams()) : Tool {
    override val kind: ToolKind = ToolKind.Smudge(params)

    override fun onStrokeBegin(input: StrokeInput, context: StrokeContext) {
        context.beginSmudge(params, input)
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
