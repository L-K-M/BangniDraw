package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.StrokeInput
import ch.lkmc.bangnidraw.engine.core.ToolKind

/** One-shot fill; the context owns its asynchronous CPU and upload lifetime. */
class FillTool(private val params: FillParams = FillParams()) : Tool {
    override val kind: ToolKind = ToolKind.Fill(params)

    override fun onStrokeBegin(input: StrokeInput, context: StrokeContext) {
        context.beginFill(params, input)
    }

    override fun onStrokeSample(input: StrokeInput, context: StrokeContext) = Unit

    override fun onStrokeEnd(context: StrokeContext) = Unit

    override fun onStrokeCancel(context: StrokeContext) {
        context.cancelFill()
    }
}
