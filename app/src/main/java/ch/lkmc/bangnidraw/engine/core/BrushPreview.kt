package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** CPU brush-settings preview: generator → reference stamp → compositor. */
internal object BrushPreview {

    fun render(
        preset: BrushPreset,
        brushColor: Int,
        paperColor: Int,
        width: Int,
        height: Int,
    ): IntArray {
        require(width > 0 && height > 0)

        val previewPreset = preset.withSize(preset.size.coerceAtMost(PREVIEW_SIZE_CAP_PX))
        // Dab spacing floors at 0.5 px. The rectilinear path bound is width
        // plus the sine's total vertical travel, safely under width + height.
        val batch = DabBatch((width + height) * MAX_DABS_PER_PIXEL + PREVIEW_SAMPLES)
        val generator = DabGenerator(previewPreset, PREVIEW_SEED)
        val sample = StrokeInput()
        for (index in 0 until PREVIEW_SAMPLES) {
            val t = index.toFloat() / (PREVIEW_SAMPLES - 1)
            sample.set(
                x = HORIZONTAL_INSET_PX + t * (width - HORIZONTAL_INSET_PX * 2f),
                y = height / 2f + sin(t * PI.toFloat() * 2f) * height * WAVE_HEIGHT_FRACTION,
                pressure = MIN_PRESSURE + (1f - MIN_PRESSURE) * t,
                tilt = MAX_PREVIEW_TILT * (1f - t),
                orientation = PREVIEW_ORIENTATION,
                timeNs = index * SAMPLE_INTERVAL_NS,
            )
            if (index == 0) generator.begin(sample, batch) else generator.advance(sample, batch)
        }
        generator.end(batch)

        val color = floatArrayOf(
            Composite.red(brushColor) / CHANNEL_MAX_F,
            Composite.green(brushColor) / CHANNEL_MAX_F,
            Composite.blue(brushColor) / CHANNEL_MAX_F,
        )
        val buffer = FloatArray(width * height)
        for (dabIndex in 0 until batch.count) {
            stamp(
                buffer = buffer,
                width = width,
                height = height,
                dab = batch[dabIndex],
                mode = previewPreset.bufferMode,
                grain = previewPreset.grainMode,
                brushModel = previewPreset.model,
            )
        }

        val opacity = previewPreset.opacity * generator.pressureOpacityMax
        val paper = Composite.premultiply(paperColor)
        return IntArray(buffer.size) { index ->
            val alpha = minOf(buffer[index], opacity)
            val stroke = Composite.argb(
                quantize(alpha),
                quantize(color[0] * alpha),
                quantize(color[1] * alpha),
                quantize(color[2] * alpha),
            )
            val merged = if (previewPreset.eraseMode) {
                Composite.erase(paper, stroke)
            } else {
                Composite.over(paper, stroke)
            }
            unpremultiply(merged)
        }
    }

    private fun stamp(
        buffer: FloatArray,
        width: Int,
        height: Int,
        dab: Dab,
        mode: BufferMode,
        grain: GrainMode,
        brushModel: BrushModel,
    ) {
        val drawRadius = DabStamp.drawRadius(dab.radius) + AA_MARGIN_PX
        val left = (dab.x - drawRadius).toInt().coerceAtLeast(0)
        val top = (dab.y - drawRadius).toInt().coerceAtLeast(0)
        val right = (dab.x + drawRadius).toInt().coerceAtMost(width - 1)
        val bottom = (dab.y + drawRadius).toInt().coerceAtMost(height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                val incoming = DabStamp.alphaAt(
                    x + PIXEL_CENTER,
                    y + PIXEL_CENTER,
                    dab,
                    grainMode = grain,
                    brushModel = brushModel,
                )
                if (incoming <= 0f) continue
                val index = y * width + x
                buffer[index] = DabStamp.blendAlpha(buffer[index], incoming, mode)
            }
        }
    }

    private fun unpremultiply(color: Int): Int {
        val alpha = Composite.alpha(color)
        if (alpha == 0 || alpha == CHANNEL_MAX) return color

        fun channel(value: Int): Int =
            ((value * CHANNEL_MAX + alpha / 2) / alpha).coerceIn(0, CHANNEL_MAX)
        return Composite.argb(
            alpha,
            channel(Composite.red(color)),
            channel(Composite.green(color)),
            channel(Composite.blue(color)),
        )
    }

    private fun quantize(value: Float): Int =
        (value.coerceIn(0f, 1f) * CHANNEL_MAX).roundToInt()

    private const val PREVIEW_SAMPLES = 64
    private const val MAX_DABS_PER_PIXEL = 2
    private const val PREVIEW_SEED = 0x4252555348L
    private const val PREVIEW_SIZE_CAP_PX = 48f
    private const val HORIZONTAL_INSET_PX = 16f
    private const val WAVE_HEIGHT_FRACTION = 0.2f
    private const val MIN_PRESSURE = 0.1f
    private const val MAX_PREVIEW_TILT = 1.2f
    private const val PREVIEW_ORIENTATION = 0.7f
    private const val SAMPLE_INTERVAL_NS = 8_000_000L
    private const val AA_MARGIN_PX = 1f
    private const val PIXEL_CENTER = 0.5f
    private const val CHANNEL_MAX = 255
    private const val CHANNEL_MAX_F = 255f
}
