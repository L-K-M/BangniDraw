package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.GPU_TILE_FRACTION
import ch.lkmc.bangnidraw.engine.core.PerfConstants.GPU_TILE_MAX_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.GPU_TILE_MIN_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.HISTORY_BYTES_LARGE
import ch.lkmc.bangnidraw.engine.core.PerfConstants.HISTORY_BYTES_SMALL
import ch.lkmc.bangnidraw.engine.core.PerfConstants.HISTORY_STEPS_LARGE
import ch.lkmc.bangnidraw.engine.core.PerfConstants.HISTORY_STEPS_SMALL
import ch.lkmc.bangnidraw.engine.core.PerfConstants.LARGE_DEVICE_TOTAL_MEM
import ch.lkmc.bangnidraw.engine.core.PerfConstants.LOW_RAM_GPU_TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.MAX_CANVAS_EDGE_V1
import ch.lkmc.bangnidraw.engine.core.PerfConstants.MAX_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.MIN_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.MIN_USEFUL_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.STROKE_BUFFER_RESERVE_LAYERS
import ch.lkmc.bangnidraw.engine.core.PerfConstants.THUMB_MIB_LARGE
import ch.lkmc.bangnidraw.engine.core.PerfConstants.THUMB_MIB_LOW_RAM
import ch.lkmc.bangnidraw.engine.core.PerfConstants.THUMB_MIB_SMALL
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE

/**
 * Everything the budget needs about the device, read once (from
 * `ActivityManager` and the first GL context) and never re-queried per frame.
 */
data class DeviceMemory(
    val totalMemBytes: Long,
    val isLowRamDevice: Boolean,
    val largeMemoryClassMb: Int,
    /** `GL_MAX_ARRAY_TEXTURE_LAYERS`; 0 means "no context yet". */
    val glMaxArrayLayers: Int,
    val glMaxTextureSize: Int,
)

/** A canvas size in pixels, with the tile arithmetic that follows from it. */
data class CanvasSize(val width: Int, val height: Int) {
    val tilesX: Int get() = (width + TILE_SIZE - 1) / TILE_SIZE
    val tilesY: Int get() = (height + TILE_SIZE - 1) / TILE_SIZE
    val tilesPerLayer: Int get() = tilesX * tilesY
    val layerBytesWorstCase: Long get() = tilesPerLayer.toLong() * TILE_BYTES
}

/**
 * Turns "how much memory does this device have" and "how big is this canvas"
 * into the caps the app is honest about: how many layers fit, how large a
 * canvas may be, how deep undo goes (decision 4).
 *
 * Pure arithmetic over numbers the caller read from the platform — no
 * `android.*`, no silent downgrade, and `MemoryBudgetTest` pins the worked
 * table of `docs/plan/10-performance.md` §4 so a constant change is a visible
 * diff.
 */
object MemoryBudget {
    data class Result(
        /** What `TilePool` may allocate for tiles, all texture arrays together. */
        val gpuTileBudgetBytes: Long,
        /** For THIS canvas size, `MIN_LAYERS..MAX_LAYERS`. */
        val maxLayers: Int,
        /** The largest edge any preset may offer on this device. */
        val maxCanvasEdge: Int,
        val historyMaxSteps: Int,
        val historyMaxBytes: Long,
        val thumbnailCacheBytes: Long,
        /** Slices per texture array `TilePool` creates; never above `glMaxArrayLayers`. */
        val poolArraySlices: Int,
        /** How many texture arrays fit the budget. */
        val poolArrayCount: Int,
    )

    /** The spec minimum for `GL_MAX_ARRAY_TEXTURE_LAYERS`, and our page size. */
    const val SLICES_PER_PAGE = 256

    /**
     * How many layers of [canvas] fit a tile budget of [gpuTileBudgetBytes],
     * clamped to `MIN_LAYERS..MAX_LAYERS`. `CanvasPresets` annotates every
     * row with this, so the New Canvas dialog and the layer panel can never
     * disagree.
     */
    fun maxLayersFor(gpuTileBudgetBytes: Long, canvas: CanvasSize): Int {
        val layersThatFit =
            (gpuTileBudgetBytes / canvas.layerBytesWorstCase).toInt() - STROKE_BUFFER_RESERVE_LAYERS
        return layersThatFit.coerceIn(MIN_LAYERS, MAX_LAYERS)
    }

    fun compute(device: DeviceMemory, canvas: CanvasSize): Result {
        val gpu = when {
            device.isLowRamDevice -> LOW_RAM_GPU_TILE_BYTES
            else -> (device.totalMemBytes * GPU_TILE_FRACTION).toLong()
                .coerceIn(GPU_TILE_MIN_BYTES, GPU_TILE_MAX_BYTES)
        }
        val maxLayers = maxLayersFor(gpu, canvas)
        val slices =
            if (device.glMaxArrayLayers > 0) minOf(device.glMaxArrayLayers, SLICES_PER_PAGE)
            else SLICES_PER_PAGE
        val arrays = maxOf(1, (gpu / (slices.toLong() * TILE_BYTES)).toInt())
        // maxCanvasEdge is bounded by memory and by the v1 ceiling, never by
        // glMaxTextureSize: tiles are 256 px, so a big canvas never needs a
        // big texture. The largest multiple of TILE_SIZE whose square, fully
        // painted, still holds MIN_USEFUL_LAYERS plus the stroke-buffer
        // reserve — so a size the dialog offers can always be painted on.
        val perLayerLimit = gpu / (MIN_USEFUL_LAYERS + STROKE_BUFFER_RESERVE_LAYERS)
        var maxCanvasEdge = TILE_SIZE
        while (maxCanvasEdge + TILE_SIZE <= MAX_CANVAS_EDGE_V1 &&
            CanvasSize(maxCanvasEdge + TILE_SIZE, maxCanvasEdge + TILE_SIZE)
                .layerBytesWorstCase <= perLayerLimit
        ) {
            maxCanvasEdge += TILE_SIZE
        }
        val large = device.totalMemBytes >= LARGE_DEVICE_TOTAL_MEM && !device.isLowRamDevice
        val historySteps = if (large) HISTORY_STEPS_LARGE else HISTORY_STEPS_SMALL
        val historyBytes = if (large) HISTORY_BYTES_LARGE else HISTORY_BYTES_SMALL
        val thumbBytes = (
            when {
                device.isLowRamDevice -> THUMB_MIB_LOW_RAM
                large -> THUMB_MIB_LARGE
                else -> THUMB_MIB_SMALL
            }
            ).toLong() shl 20
        return Result(
            gpuTileBudgetBytes = gpu,
            maxLayers = maxLayers,
            maxCanvasEdge = maxCanvasEdge,
            historyMaxSteps = historySteps,
            historyMaxBytes = historyBytes,
            thumbnailCacheBytes = thumbBytes,
            poolArraySlices = slices,
            poolArrayCount = arrays,
        )
    }
}
