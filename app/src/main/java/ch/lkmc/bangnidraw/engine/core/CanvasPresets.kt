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
    /**
     * How many layers this size holds on this device — **meaningful only when
     * [enabled]**. `MemoryBudget.maxLayersFor` clamps up to `MIN_LAYERS`, so a
     * row that is over the device's ceiling still carries a number, and it is
     * the floor rather than an offer. A greyed row shows its reason, not its
     * layer count.
     */
    val maxLayers: Int,
    val enabled: Boolean,
) {
    val isSquare: Boolean get() = size.width == size.height

    /**
     * This preset's *size* with its longer side horizontal ([landscape]) or
     * vertical — not a preset: `id`, `maxLayers` and `enabled` are dropped.
     * Feed the result through [CanvasPresets.custom] to get a validated,
     * annotated preset back.
     */
    fun oriented(landscape: Boolean): CanvasSize {
        val long = maxOf(size.width, size.height)
        val short = minOf(size.width, size.height)
        return if (landscape) CanvasSize(long, short) else CanvasSize(short, long)
    }
}

/**
 * Why a custom size cannot be created. Values, never text: `ui/` maps them to
 * strings.
 *
 * Three of the four are the *format's* limits and read the same on every
 * device; only [TOO_LARGE_FOR_DEVICE] depends on this phone's budget. The
 * distinction matters to the message the dialog shows: "this drawing is too
 * big for the file format" invites a smaller number, "too big for this
 * device" invites a smaller number *or* a different device.
 */
enum class SizeRefusal {
    /** A side under [TileGrid.MIN_EDGE] px. */
    TOO_SMALL,

    /** A side over [TileGrid.MAX_EDGE] px, whatever the tile count works out to. */
    TOO_LARGE_FOR_FORMAT,

    /** Sides in range, but over [TileGrid.MAX_TILES] tiles between them. */
    TOO_MANY_TILES,

    /** Inside the format, over this device's budget. */
    TOO_LARGE_FOR_DEVICE,
}

sealed interface CustomSizeResult {
    data class Ok(val preset: CanvasPreset) : CustomSizeResult
    data class Refused(val reason: SizeRefusal) : CustomSizeResult
}

/**
 * The fixed preset list the New Canvas dialog offers, annotated with this
 * device's budget (`docs/plan/10-performance.md` §4).
 */
object CanvasPresets {
    /** The name `docs/plan/03-canvas-engine.md` §1 uses; the number is [TileGrid.MAX_TILES]. */
    const val MAX_TILES = TileGrid.MAX_TILES

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
            maxLayers = MemoryBudget.maxLayersFor(result.poolCapacityBytes, size),
            enabled = fits(size, result),
        )
    }

    /**
     * The row the dialog selects when it opens: the largest preset this device
     * can hold, measured in tiles because that is what the budget spends. The
     * dialog narrows that further to what fits the screen — a pixel
     * measurement `engine/core` has no business knowing.
     *
     * Deliberately not "the last enabled row": that would silently depend on
     * [SIZES] staying sorted, and on the caller not having reordered or
     * filtered the list first. Falls back to 0 only when nothing is enabled,
     * which no real device produces (the smallest preset fits every budget).
     */
    fun defaultIndex(presets: List<CanvasPreset>): Int =
        presets.withIndex()
            .filter { it.value.enabled }
            .maxByOrNull { it.value.size.tilesPerLayer }
            ?.index
            ?: 0

    /** A user-typed size, validated against the format and this device's budget. */
    fun custom(size: CanvasSize, result: MemoryBudget.Result): CustomSizeResult {
        if (size.width < TileGrid.MIN_EDGE || size.height < TileGrid.MIN_EDGE) {
            return CustomSizeResult.Refused(SizeRefusal.TOO_SMALL)
        }
        // Two independent ceilings, both of them the format's rather than the
        // device's: a side longer than the tile coordinate space allows, and a
        // tile count past what the readback chunking and sandwich rebuild are
        // sized for. They get separate refusals because they are separate
        // facts — 9000x256 is 36 tiles, far under the tile cap, and telling
        // the user it has too many tiles would send them shrinking the wrong
        // number. Written as separate statements so neither depends on the
        // other being evaluated first — `CanvasSize`'s arithmetic is
        // overflow-safe now, so the ordering is clarity, not correctness.
        if (size.width > TileGrid.MAX_EDGE || size.height > TileGrid.MAX_EDGE) {
            return CustomSizeResult.Refused(SizeRefusal.TOO_LARGE_FOR_FORMAT)
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
                maxLayers = MemoryBudget.maxLayersFor(result.poolCapacityBytes, size),
                enabled = true,
            ),
        )
    }

    /**
     * Deliberately does **not** also require `maxLayersFor(...) >= 1`.
     *
     * That looks like a missing check — a size could be offered with no layer
     * to paint on — but `MemoryBudget` already rules it out from the other
     * side: `maxCanvasEdge` is the largest edge whose layer worst case still
     * fits `poolCapacity / (MIN_USEFUL_LAYERS + STROKE_BUFFER_RESERVE_LAYERS)`,
     * so anything at or under it holds at least `MIN_USEFUL_LAYERS`, which is
     * 4. Adding the check would not make a false case true; it would restate
     * an invariant one layer down and imply, wrongly, that `maxCanvasEdge`
     * might not carry it. `MemoryBudgetTest` pins the invariant where it lives
     * ("a device offers no edge it cannot hold layers at"), and `compute` now
     * fails fast if the constants ever stop supporting it at the floor.
     */
    private fun fits(size: CanvasSize, result: MemoryBudget.Result): Boolean =
        maxOf(size.width, size.height) <= result.maxCanvasEdge && size.tilesPerLayer <= MAX_TILES
}
