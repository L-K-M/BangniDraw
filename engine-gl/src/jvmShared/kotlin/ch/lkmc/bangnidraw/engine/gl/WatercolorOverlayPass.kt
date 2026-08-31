package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.WatercolorKernel

/** Maps quarter-resolution wet tiles onto the canvas without changing pixels. */
internal class WatercolorOverlayPass(
    private val canvas: CanvasSize,
    private val program: GlProgram,
    private val state: GlState,
) {

    private val composite = CompositePass(program, state)

    fun draw(
        textures: LayerTextures,
        screen: ScreenTransform,
        projection: FloatArray,
        bufferTransform: FloatArray,
        canvasDirty: IntRect,
        nowTick: Int,
        opacity: Float,
    ): Int {
        val clipped = IntRect(
            canvasDirty.left.coerceIn(0, canvas.width),
            canvasDirty.top.coerceIn(0, canvas.height),
            canvasDirty.right.coerceIn(0, canvas.width),
            canvasDirty.bottom.coerceIn(0, canvas.height),
        )
        if (clipped.isEmpty) return 0

        val cell = WatercolorKernel.CELL_SIZE
        val sourceDirty = IntRect(
            clipped.left / cell,
            clipped.top / cell,
            divideCeil(clipped.right, cell),
            divideCeil(clipped.bottom, cell),
        )
        state.useProgram(program)
        program.uniform1i("u_nowTick", nowTick)
        program.uniform2f("u_canvasSize", canvas.width.toFloat(), canvas.height.toFloat())

        return composite.drawScaled(
            textures = textures,
            opacity = opacity,
            screen = screen,
            projection = projection,
            bufferTransform = bufferTransform,
            sourceDirtyRect = sourceDirty,
            sourceToCanvasScale = cell.toFloat(),
        )
    }

    fun release() = composite.release()

    private fun divideCeil(value: Int, divisor: Int): Int =
        (value + divisor - 1) / divisor
}
