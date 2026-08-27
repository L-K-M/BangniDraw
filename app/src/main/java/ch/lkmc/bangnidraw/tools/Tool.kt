package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.SmudgeParams
import ch.lkmc.bangnidraw.engine.core.StrokeInput
import ch.lkmc.bangnidraw.engine.core.ToolKind

interface Tool {
    val kind: ToolKind

    fun onStrokeBegin(input: StrokeInput, context: StrokeContext)
    fun onStrokeSample(input: StrokeInput, context: StrokeContext)
    fun onStrokeEnd(context: StrokeContext)
    fun onStrokeCancel(context: StrokeContext)
}

/** High-level engine operations exposed to tools; platform mechanics stay below. */
interface StrokeContext {
    fun beginBrush(preset: BrushPreset, input: StrokeInput)
    fun sampleBrush(input: StrokeInput)
    fun commitBrush()
    fun cancelBrush()

    fun beginSmudge(params: SmudgeParams, input: StrokeInput)
    fun beginBlur(params: BlurParams, input: StrokeInput)
    fun sampleRmw(input: StrokeInput)
    fun commitRmw()
    fun cancelRmw()

    fun beginFill(params: FillParams, input: StrokeInput)
    fun cancelFill()

    fun beginColorPick(params: EyedropperParams, input: StrokeInput)
    fun sampleColorPick(input: StrokeInput)
    fun commitColorPick()
    fun cancelColorPick()
}
