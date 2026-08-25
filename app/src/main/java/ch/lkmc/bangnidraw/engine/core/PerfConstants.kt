package ch.lkmc.bangnidraw.engine.core

/**
 * Every number the engine is allowed to know, in one place.
 *
 * `docs/plan/10-performance.md` §4 owns this object: nothing in `engine/gl`,
 * `input/`, `data/` or `ui/` may invent a budget, a capacity or a cap. The
 * tests that pin the derived values are [MemoryBudgetTest] and
 * [CanvasPresetsTest], so a constant change shows up as a failing assertion
 * rather than as a silently different app.
 */
object PerfConstants {
    // Every tile computation in TileGrid depends on TILE_SIZE == 1 shl
    // TILE_SHIFT, so the shift is the one authored number and the size is
    // derived: editing one without the other would compile cleanly and shift
    // every tile address, index and dirty rect.
    const val TILE_SHIFT = 8
    const val TILE_SIZE = 1 shl TILE_SHIFT // 256
    const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4 // 262 144

    // Touch path (all preallocated once per CanvasTouchHandler).
    const val STROKE_INPUT_CAPACITY = 8192
    const val DAB_STRIDE = 8 // x y radius flow hardness angle aspect seed
    const val DAB_BATCH_CAPACITY = 1024
    const val DAB_RING_SLOTS = 8
    const val DAB_RING_CAPACITY = DAB_BATCH_CAPACITY * DAB_RING_SLOTS
    const val STABILIZER_WINDOW = 16

    // Stroke end.
    const val READBACK_PBO_COUNT = 2
    const val READBACK_PBO_TILES = 64
    const val READBACK_MIN_FRAME_AGE = 2
    const val CPU_MIRROR_CAP_BYTES = 64L shl 20

    // Reopen.
    const val REOPEN_INFLATE_CHUNK = 16
    const val UPLOAD_BATCH_TILES = 32
    const val REOPEN_STAGING_TILES = 2 * UPLOAD_BATCH_TILES

    // Composite. Not a texture border: the canvas-space margin around the
    // viewport that a lazy sandwich rebuild fills first, so a small pan finds
    // its tiles already built (`10-performance.md` §2.6). One tile wide, and
    // written as TILE_SIZE rather than 256 so re-tiling moves it too — the
    // rebuild works in whole tiles, so a margin that is not a tile multiple
    // would round up to this anyway.
    const val SANDWICH_MARGIN_PX = TILE_SIZE

    // Budget.
    const val MAX_LAYERS = 16
    const val MIN_LAYERS = 1
    const val MIN_USEFUL_LAYERS = 4
    const val MAX_CANVAS_EDGE_V1 = 4096
    const val HISTORY_STEPS_LARGE = 200
    const val HISTORY_BYTES_LARGE = 256L shl 20
    const val HISTORY_STEPS_SMALL = 100
    const val HISTORY_BYTES_SMALL = 128L shl 20
    const val THUMB_MIB_LARGE = 24
    const val THUMB_MIB_SMALL = 12
    const val THUMB_MIB_LOW_RAM = 8
    const val LARGE_DEVICE_TOTAL_MEM = 6L shl 30
    const val GPU_TILE_FRACTION = 0.125
    const val GPU_TILE_MIN_BYTES = 256L shl 20
    const val GPU_TILE_MAX_BYTES = 1536L shl 20
    const val LOW_RAM_GPU_TILE_BYTES = 256L shl 20
    const val STROKE_BUFFER_RESERVE_LAYERS = 1
}
