package ch.lkmc.bangnidraw.engine.core

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Affine image-pixel to canvas-pixel mapping for a tracing reference. */
data class ReferenceTransform(
    val xx: Float,
    val xy: Float,
    val yx: Float,
    val yy: Float,
    val tx: Float,
    val ty: Float,
) {
    init {
        require(
            xx.isFinite() && xy.isFinite() && yx.isFinite() && yy.isFinite() &&
                tx.isFinite() && ty.isFinite(),
        ) { "reference transform must be finite" }
        require(determinant.isFinite() && determinant != 0f) {
            "reference transform must be invertible"
        }
        require(effectiveScale.isFinite()) { "reference scale must be finite" }
    }

    private val determinant: Float get() = xx * yy - xy * yx

    val xScale: Float get() = hypot(xx, yx)

    val yScale: Float get() = hypot(xy, yy)

    private val effectiveScale: Float
        get() = max(xScale, yScale)

    /** Smallest target-space stretch; minification filtering follows this axis. */
    val minimumScale: Float
        get() {
            val trace = xx * xx + xy * xy + yx * yx + yy * yy
            val discriminant = max(0f, trace * trace - 4f * determinant * determinant)
            return sqrt(max(0f, (trace - sqrt(discriminant)) / 2f))
        }

    fun apply(x: Float, y: Float): Pair<Float, Float> = Pair(
        xx * x + xy * y + tx,
        yx * x + yy * y + ty,
    )

    /** Applies a canvas-space two-finger similarity after the current mapping. */
    fun gesture(
        pivotX: Float,
        pivotY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        rotationDelta: Float,
    ): ReferenceTransform {
        require(zoom.isFinite() && zoom > 0f) { "zoom must be positive" }
        require(rotationDelta.isFinite()) { "rotation must be finite" }
        require(
            pivotX.isFinite() && pivotY.isFinite() && panX.isFinite() && panY.isFinite(),
        ) { "reference gesture must be finite" }

        val currentScale = effectiveScale
        val requestedScale = currentScale * zoom
        val appliedZoom = requestedScale.coerceIn(MIN_SCALE, MAX_SCALE) / currentScale
        val cosine = cos(rotationDelta) * appliedZoom
        val sine = sin(rotationDelta) * appliedZoom
        val nextXx = cosine * xx - sine * yx
        val nextXy = cosine * xy - sine * yy
        val nextYx = sine * xx + cosine * yx
        val nextYy = sine * xy + cosine * yy
        val offsetX = tx - pivotX
        val offsetY = ty - pivotY

        return ReferenceTransform(
            xx = nextXx,
            xy = nextXy,
            yx = nextYx,
            yy = nextYy,
            tx = cosine * offsetX - sine * offsetY + pivotX + panX,
            ty = sine * offsetX + cosine * offsetY + pivotY + panY,
        )
    }

    /** Composes the canvas-to-screen similarity after this image mapping. */
    fun followedBy(screen: ScreenTransform): ReferenceTransform = ReferenceTransform(
        xx = screen.a * xx - screen.b * yx,
        xy = screen.a * xy - screen.b * yy,
        yx = screen.b * xx + screen.a * yx,
        yy = screen.b * xy + screen.a * yy,
        tx = screen.screenX(tx, ty),
        ty = screen.screenY(tx, ty),
    )

    /** Composes a future crop/resize's canvas mapping after this image mapping. */
    fun followedByCanvas(mapping: ReferenceTransform): ReferenceTransform = ReferenceTransform(
        xx = mapping.xx * xx + mapping.xy * yx,
        xy = mapping.xx * xy + mapping.xy * yy,
        yx = mapping.yx * xx + mapping.yy * yx,
        yy = mapping.yx * xy + mapping.yy * yy,
        tx = mapping.xx * tx + mapping.xy * ty + mapping.tx,
        ty = mapping.yx * tx + mapping.yy * ty + mapping.ty,
    )

    /** Source-pixel AABB needed to redraw a destination-space rectangle. */
    fun sourceBoundsOf(
        destination: IntRect,
        sourceWidth: Int,
        sourceHeight: Int,
    ): IntRect {
        if (destination.isEmpty || sourceWidth <= 0 || sourceHeight <= 0) return IntRect.EMPTY

        val x0 = destination.left.toFloat()
        val y0 = destination.top.toFloat()
        val x1 = destination.right.toFloat()
        val y1 = destination.bottom.toFloat()
        val sx00 = inverseX(x0, y0)
        val sy00 = inverseY(x0, y0)
        val sx10 = inverseX(x1, y0)
        val sy10 = inverseY(x1, y0)
        val sx11 = inverseX(x1, y1)
        val sy11 = inverseY(x1, y1)
        val sx01 = inverseX(x0, y1)
        val sy01 = inverseY(x0, y1)

        val left = floor(min(min(sx00, sx10), min(sx11, sx01))).toInt()
            .coerceIn(0, sourceWidth)
        val top = floor(min(min(sy00, sy10), min(sy11, sy01))).toInt()
            .coerceIn(0, sourceHeight)
        val right = ceil(max(max(sx00, sx10), max(sx11, sx01))).toInt()
            .coerceIn(0, sourceWidth)
        val bottom = ceil(max(max(sy00, sy10), max(sy11, sy01))).toInt()
            .coerceIn(0, sourceHeight)

        return if (left >= right || top >= bottom) IntRect.EMPTY
        else IntRect(left, top, right, bottom)
    }

    private fun inverseX(x: Float, y: Float): Float =
        (yy * (x - tx) - xy * (y - ty)) / determinant

    private fun inverseY(x: Float, y: Float): Float =
        (-yx * (x - tx) + xx * (y - ty)) / determinant

    companion object {
        val IDENTITY = ReferenceTransform(
            xx = 1f,
            xy = 0f,
            yx = 0f,
            yy = 1f,
            tx = 0f,
            ty = 0f,
        )

        const val MIN_SCALE = 0.01f
        val MAX_SCALE = TileGrid.MAX_EDGE.toFloat()

        fun fit(
            imageWidth: Int,
            imageHeight: Int,
            canvasWidth: Int,
            canvasHeight: Int,
        ): ReferenceTransform {
            require(imageWidth > 0 && imageHeight > 0) { "image size must be positive" }
            require(canvasWidth > 0 && canvasHeight > 0) { "canvas size must be positive" }

            val scale = min(
                canvasWidth.toFloat() / imageWidth,
                canvasHeight.toFloat() / imageHeight,
            )

            return ReferenceTransform(
                xx = scale,
                xy = 0f,
                yx = 0f,
                yy = scale,
                tx = (canvasWidth - imageWidth * scale) / 2f,
                ty = (canvasHeight - imageHeight * scale) / 2f,
            )
        }
    }
}

enum class ReferenceVisibility { VISIBLE, HIDDEN }

data class TracingReference(
    val assetName: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val transform: ReferenceTransform,
    val opacity: Float = DEFAULT_OPACITY,
    val visibility: ReferenceVisibility = ReferenceVisibility.VISIBLE,
) {
    init {
        require(isSafeAssetName(assetName)) { "unsafe reference asset name" }
        require(imageWidth > 0 && imageHeight > 0) { "reference size must be positive" }
        require(opacity in 0f..1f && opacity.isFinite()) { "reference opacity must be 0..1" }
    }

    companion object {
        const val DEFAULT_OPACITY = 0.5f
    }
}

enum class ReferenceImportDecision {
    ACCEPT,
    REFUSE_LAYER_BUDGET,
    REFUSE_TRANSIENT_BUDGET,
}

enum class ReferenceLayerReserve { REQUIRED, HELD }

object TracingReferencePolicy {
    fun importDecision(
        layerCount: Int,
        maxLayers: Int,
        transientImageBytes: Long,
        layerReserve: ReferenceLayerReserve,
    ): ReferenceImportDecision {
        if (layerReserve == ReferenceLayerReserve.REQUIRED && layerCount >= maxLayers) {
            return ReferenceImportDecision.REFUSE_LAYER_BUDGET
        }
        if (transientImageBytes < PerfConstants.TILE_BYTES) {
            return ReferenceImportDecision.REFUSE_TRANSIENT_BUDGET
        }

        return ReferenceImportDecision.ACCEPT
    }

    fun layerCap(
        layerCount: Int,
        maxLayers: Int,
        reference: TracingReference?,
    ): Int {
        if (reference == null) return maxLayers

        return maxOf(layerCount, maxLayers - REFERENCE_LAYER_RESERVE)
    }

    fun normalizedSize(
        sourceWidth: Int,
        sourceHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        maxPixelBytes: Long,
    ): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "source size must be positive" }
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas size must be positive" }
        require(maxPixelBytes >= RGBA8_BYTES_PER_PIXEL) { "pixel allowance is too small" }
        val sourcePixels = sourceWidth.toDouble() * sourceHeight
        val maxPixels = maxPixelBytes / RGBA8_BYTES_PER_PIXEL
        val scale = min(
            1f,
            min(
                canvasWidth.toFloat() / sourceWidth,
                min(
                    canvasHeight.toFloat() / sourceHeight,
                    sqrt(maxPixels.toDouble() / sourcePixels).toFloat(),
                ),
            ),
        )
        var width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        var height = (sourceHeight * scale).toInt().coerceAtLeast(1)

        // Flooring normally fits. Clamp extreme aspect ratios after the
        // one-pixel floor so malformed sources cannot exceed the allowance.
        if (width.toLong() * height > maxPixels) {
            if (width >= height) width = (maxPixels / height).toInt().coerceAtLeast(1)
            else height = (maxPixels / width).toInt().coerceAtLeast(1)
        }

        return Pair(width, height)
    }

    private const val RGBA8_BYTES_PER_PIXEL = 4L
    private const val REFERENCE_LAYER_RESERVE = 1
}

internal fun isSafeAssetName(name: String): Boolean =
    isSafePathSegment(name) && name.endsWith(REFERENCE_ASSET_SUFFIX)

private const val REFERENCE_ASSET_SUFFIX = ".png"
