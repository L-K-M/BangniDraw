package ch.lkmc.bangnidraw.engine.core

/** Which fixed preset a row is; the display name is a string resource in `ui/`. */
enum class CanvasPresetId { PHONE_SKETCH, SQUARE_2048, TABLET, LARGE_4096, CUSTOM }

/**
 * One row of the New Canvas dialog: a size, how many layers it holds on this
 * device, and whether it can be offered at all.
 *
 * A preset the device cannot hold is [enabled] `false` rather than absent —
 * the dialog shows it greyed with a reason, so the limit is visible instead
 * of mysterious (`docs/plan/08-ui-and-layout.md` §2.1, decision 4).
 */
data class CanvasPreset(
    val id: CanvasPresetId,
    val size: CanvasSize,
    val maxLayers: Int,
    val enabled: Boolean,
) {
    val isSquare: Boolean get() = size.width == size.height

    /** The same preset with its longer side horizontal ([landscape]) or vertical. */
    fun oriented(landscape: Boolean): CanvasSize {
        val long = maxOf(size.width, size.height)
        val short = minOf(size.width, size.height)
        return if (landscape) CanvasSize(long, short) else CanvasSize(short, long)
    }
}

/** Why a custom size cannot be created. Values, never text: `ui/` maps them to strings. */
enum class SizeRefusal { TOO_SMALL, TOO_LARGE_FOR_DEVICE, TOO_MANY_TILES }

sealed interface CustomSizeResult {
    data class Ok(val preset: CanvasPreset) : CustomSizeResult
    data class Refused(val reason: SizeRefusal) : CustomSizeResult
}

/**
 * The fixed preset list the New Canvas dialog offers, annotated with this
 * device's budget (`docs/plan/10-performance.md` §4).
 */
object CanvasPresets {
    /**
     * The format's ceiling on tiles per layer (`docs/plan/03-canvas-engine.md`
     * §1): the readback chunking and the sandwich rebuild are sized for it.
     */
    const val MAX_TILES = 1024

    /** The sizes, small to large, in their natural orientation. */
    private val SIZES: List<Pair<CanvasPresetId, CanvasSize>> = listOf(
        CanvasPresetId.PHONE_SKETCH to CanvasSize(1080, 1920),
        CanvasPresetId.SQUARE_2048 to CanvasSize(2048, 2048),
        CanvasPresetId.TABLET to CanvasSize(2560, 1600),
        CanvasPresetId.LARGE_4096 to CanvasSize(4096, 4096),
    )

    /**
     * Every preset, ordered small to large, each annotated with the layer
     * count it holds under [result] and disabled when its longer side is over
     * `result.maxCanvasEdge`.
     */
    fun forDevice(result: MemoryBudget.Result): List<CanvasPreset> = SIZES.map { (id, size) ->
        CanvasPreset(
            id = id,
            size = size,
            maxLayers = MemoryBudget.maxLayersFor(result.gpuTileBudgetBytes, size),
            enabled = fits(size, result),
        )
    }

    /**
     * The row the dialog selects when it opens: the largest preset this
     * device can hold. The dialog narrows that further to what fits the
     * screen — a pixel measurement `engine/core` has no business knowing.
     */
    fun defaultIndex(presets: List<CanvasPreset>): Int =
        presets.indexOfLast { it.enabled }.takeIf { it >= 0 } ?: 0

    /** A user-typed size, validated against the format and this device's budget. */
    fun custom(size: CanvasSize, result: MemoryBudget.Result): CustomSizeResult {
        if (size.width < TileGrid.MIN_EDGE || size.height < TileGrid.MIN_EDGE) {
            return CustomSizeResult.Refused(SizeRefusal.TOO_SMALL)
        }
        // Two independent ceilings, both of them the format's rather than the
        // device's: a side longer than the tile coordinate space allows, and a
        // tile count past what the readback chunking and sandwich rebuild are
        // sized for. Written as separate statements so neither depends on the
        // other being evaluated first — `CanvasSize`'s arithmetic is
        // overflow-safe now, so the ordering is clarity, not correctness.
        if (size.width > TileGrid.MAX_EDGE || size.height > TileGrid.MAX_EDGE) {
            return CustomSizeResult.Refused(SizeRefusal.TOO_MANY_TILES)
        }
        if (size.tilesPerLayer > MAX_TILES) {
            return CustomSizeResult.Refused(SizeRefusal.TOO_MANY_TILES)
        }
        if (!fits(size, result)) {
            return CustomSizeResult.Refused(SizeRefusal.TOO_LARGE_FOR_DEVICE)
        }
        return CustomSizeResult.Ok(
            CanvasPreset(
                id = CanvasPresetId.CUSTOM,
                size = size,
                maxLayers = MemoryBudget.maxLayersFor(result.gpuTileBudgetBytes, size),
                enabled = true,
            ),
        )
    }

    private fun fits(size: CanvasSize, result: MemoryBudget.Result): Boolean =
        maxOf(size.width, size.height) <= result.maxCanvasEdge && size.tilesPerLayer <= MAX_TILES
}
