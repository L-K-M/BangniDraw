package ch.lkmc.bangnidraw.engine.core

import kotlin.math.max

/** Premultiplied ARGB source for a CPU fill. */
fun interface PixelSource {
    fun pixel(x: Int, y: Int): Int
}

/** Row-major 0..255 coverage cropped to [bounds]. */
class Coverage(val bounds: IntRect, val bytes: ByteArray) {
    init {
        require(bytes.size == bounds.width * bounds.height) {
            "coverage byte count must match its bounds"
        }
    }

    operator fun get(x: Int, y: Int): Int {
        if (x !in bounds.left until bounds.right) return 0
        if (y !in bounds.top until bounds.bottom) return 0
        return bytes[(y - bounds.top) * bounds.width + x - bounds.left].toInt() and CHANNEL_MASK
    }

    companion object {
        val EMPTY = Coverage(IntRect.EMPTY, ByteArray(0))
        private const val CHANNEL_MASK = 0xFF
    }
}

/** Cancellable scanline fill with wall-aware expansion and a one-pixel AA edge. */
class FloodFill(
    private val width: Int,
    private val height: Int,
    private val reference: PixelSource,
    private val params: FillParams,
) {
    init {
        require(width > 0 && height > 0) { "fill dimensions must be positive" }
        require(width <= Int.MAX_VALUE / height) { "fill dimensions are too large" }
    }

    fun run(
        seedX: Int,
        seedY: Int,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Coverage? {
        if (seedX !in 0 until width || seedY !in 0 until height) return Coverage.EMPTY
        if (isCancelled()) return null

        val seed = reference.pixel(seedX, seedY)
        val region = ByteArray(width * height)
        val regionBounds = Bounds()
        val completed = if (params.contiguous) {
            fillContiguous(seedX, seedY, seed, region, regionBounds, progress, isCancelled)
        } else {
            fillGlobal(seed, region, regionBounds, progress, isCancelled)
        }
        if (!completed) return null

        if (regionBounds.isEmpty) {
            progress(PROGRESS_COMPLETE)
            return Coverage.EMPTY
        }

        var mask = region
        if (params.expand > 0) {
            mask = expand(mask, seed, progress, isCancelled) ?: return null
        }

        val binaryBounds = if (params.expand == 0) regionBounds.toRect() else bounds(mask)
        if (binaryBounds.isEmpty) {
            progress(PROGRESS_COMPLETE)
            return Coverage.EMPTY
        }

        val coverage = if (params.antialias) {
            antialias(mask, seed, binaryBounds, progress, isCancelled) ?: return null
        } else {
            crop(mask, binaryBounds)
        }
        progress(PROGRESS_COMPLETE)
        return coverage
    }

    private fun fillGlobal(
        seed: Int,
        mask: ByteArray,
        bounds: Bounds,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        for (y in 0 until height) {
            if (pollRow(y, isCancelled)) return false

            val row = y * width
            for (x in 0 until width) {
                if (!matches(reference.pixel(x, y), seed)) continue
                mask[row + x] = COVERED_BYTE
                bounds.include(x, y)
            }
            progress(PROGRESS_REGION * (y + 1f) / height)
        }
        return true
    }

    private fun fillContiguous(
        seedX: Int,
        seedY: Int,
        seed: Int,
        mask: ByteArray,
        bounds: Bounds,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        val spans = SpanStack()
        spans.push(seedY, seedX, seedX)
        var processedSpans = 0
        var coveredPixels = 0
        val totalPixels = width.toLong() * height

        while (spans.isNotEmpty) {
            if (processedSpans++ % CANCEL_POLL_ROWS == 0 && isCancelled()) return false
            val span = spans.pop()
            var x = span.left

            while (x <= span.right) {
                if (mask[span.y * width + x] != EMPTY_BYTE || !matches(reference.pixel(x, span.y), seed)) {
                    x++
                    continue
                }

                var left = x
                while (left > 0 && mask[span.y * width + left - 1] == EMPTY_BYTE &&
                    matches(reference.pixel(left - 1, span.y), seed)
                ) {
                    left--
                }
                var right = x
                while (right + 1 < width && mask[span.y * width + right + 1] == EMPTY_BYTE &&
                    matches(reference.pixel(right + 1, span.y), seed)
                ) {
                    right++
                }

                val row = span.y * width
                for (fillX in left..right) mask[row + fillX] = COVERED_BYTE
                coveredPixels += right - left + 1
                bounds.include(left, span.y)
                bounds.include(right, span.y)

                if (span.y > 0) queueRuns(spans, span.y - 1, left, right, seed, mask)
                if (span.y + 1 < height) queueRuns(spans, span.y + 1, left, right, seed, mask)
                x = right + 1
            }

            if (processedSpans % PROGRESS_SPANS == 0) {
                progress(PROGRESS_REGION * coveredPixels / totalPixels)
            }
        }
        progress(PROGRESS_REGION)
        return true
    }

    private fun queueRuns(
        spans: SpanStack,
        y: Int,
        left: Int,
        right: Int,
        seed: Int,
        mask: ByteArray,
    ) {
        var x = left
        val row = y * width
        while (x <= right) {
            while (x <= right && (mask[row + x] != EMPTY_BYTE || !matches(reference.pixel(x, y), seed))) x++
            if (x > right) return

            val runLeft = x
            while (x <= right && mask[row + x] == EMPTY_BYTE && matches(reference.pixel(x, y), seed)) x++
            spans.push(y, runLeft, x - 1)
        }
    }

    private fun expand(
        input: ByteArray,
        seed: Int,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): ByteArray? {
        val horizontal = ByteArray(input.size)
        for (y in 0 until height) {
            if (pollRow(y, isCancelled)) return null
            spreadRow(input, horizontal, y, seed)
            progress(PROGRESS_REGION + PROGRESS_EXPAND_HALF * (y + 1f) / height)
        }

        val output = ByteArray(input.size)
        for (x in 0 until width) {
            if (x % CANCEL_POLL_ROWS == 0 && isCancelled()) return null
            spreadColumn(horizontal, output, x, seed)
            progress(PROGRESS_REGION + PROGRESS_EXPAND_HALF + PROGRESS_EXPAND_HALF * (x + 1f) / width)
        }
        return output
    }

    private fun spreadRow(input: ByteArray, output: ByteArray, y: Int, seed: Int) {
        val row = y * width
        var lastCovered = NO_POSITION
        for (x in 0 until width) {
            if (isWall(reference.pixel(x, y), seed)) {
                lastCovered = NO_POSITION
                continue
            }
            if (input[row + x] != EMPTY_BYTE) lastCovered = x
            if (lastCovered != NO_POSITION && x - lastCovered <= params.expand) output[row + x] = COVERED_BYTE
        }

        var nextCovered = NO_POSITION
        for (x in width - 1 downTo 0) {
            if (isWall(reference.pixel(x, y), seed)) {
                nextCovered = NO_POSITION
                continue
            }
            if (input[row + x] != EMPTY_BYTE) nextCovered = x
            if (nextCovered != NO_POSITION && nextCovered - x <= params.expand) output[row + x] = COVERED_BYTE
        }
    }

    private fun spreadColumn(input: ByteArray, output: ByteArray, x: Int, seed: Int) {
        var lastCovered = NO_POSITION
        for (y in 0 until height) {
            val index = y * width + x
            if (isWall(reference.pixel(x, y), seed)) {
                lastCovered = NO_POSITION
                continue
            }
            if (input[index] != EMPTY_BYTE) lastCovered = y
            if (lastCovered != NO_POSITION && y - lastCovered <= params.expand) output[index] = COVERED_BYTE
        }

        var nextCovered = NO_POSITION
        for (y in height - 1 downTo 0) {
            val index = y * width + x
            if (isWall(reference.pixel(x, y), seed)) {
                nextCovered = NO_POSITION
                continue
            }
            if (input[index] != EMPTY_BYTE) nextCovered = y
            if (nextCovered != NO_POSITION && nextCovered - y <= params.expand) output[index] = COVERED_BYTE
        }
    }

    private fun antialias(
        mask: ByteArray,
        seed: Int,
        binaryBounds: IntRect,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Coverage? {
        val candidate = IntRect(
            left = maxOf(0, binaryBounds.left - AA_RADIUS),
            top = maxOf(0, binaryBounds.top - AA_RADIUS),
            right = minOf(width, binaryBounds.right + AA_RADIUS),
            bottom = minOf(height, binaryBounds.bottom + AA_RADIUS),
        )
        val bytes = ByteArray(candidate.width * candidate.height)
        val nonZero = Bounds()

        for (y in candidate.top until candidate.bottom) {
            if (pollRow(y - candidate.top, isCancelled)) return null
            for (x in candidate.left until candidate.right) {
                if (isWall(reference.pixel(x, y), seed)) continue

                var sum = 0
                var samples = 0
                for (sampleY in maxOf(0, y - AA_RADIUS)..minOf(height - 1, y + AA_RADIUS)) {
                    val row = sampleY * width
                    for (sampleX in maxOf(0, x - AA_RADIUS)..minOf(width - 1, x + AA_RADIUS)) {
                        sum += mask[row + sampleX].toInt() and CHANNEL_MASK
                        samples++
                    }
                }
                val coverage = (sum + samples / 2) / samples
                if (coverage == 0) continue
                bytes[(y - candidate.top) * candidate.width + x - candidate.left] = coverage.toByte()
                nonZero.include(x, y)
            }
            val completedRows = y - candidate.top + 1f
            progress(PROGRESS_AFTER_EXPAND + PROGRESS_AA * completedRows / candidate.height)
        }
        if (nonZero.isEmpty) return Coverage.EMPTY
        return crop(bytes, candidate, nonZero.toRect())
    }

    private fun crop(mask: ByteArray, bounds: IntRect): Coverage {
        val bytes = ByteArray(bounds.width * bounds.height)
        for (y in bounds.top until bounds.bottom) {
            mask.copyInto(
                bytes,
                destinationOffset = (y - bounds.top) * bounds.width,
                startIndex = y * width + bounds.left,
                endIndex = y * width + bounds.right,
            )
        }
        return Coverage(bounds, bytes)
    }

    private fun crop(source: ByteArray, sourceBounds: IntRect, resultBounds: IntRect): Coverage {
        if (sourceBounds == resultBounds) return Coverage(sourceBounds, source)

        val bytes = ByteArray(resultBounds.width * resultBounds.height)
        for (y in resultBounds.top until resultBounds.bottom) {
            val sourceOffset = (y - sourceBounds.top) * sourceBounds.width + resultBounds.left - sourceBounds.left
            source.copyInto(
                bytes,
                destinationOffset = (y - resultBounds.top) * resultBounds.width,
                startIndex = sourceOffset,
                endIndex = sourceOffset + resultBounds.width,
            )
        }
        return Coverage(resultBounds, bytes)
    }

    private fun bounds(mask: ByteArray): IntRect {
        val bounds = Bounds()
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) if (mask[row + x] != EMPTY_BYTE) bounds.include(x, y)
        }
        return bounds.toRect()
    }

    private fun matches(pixel: Int, seed: Int): Boolean =
        colorDistance(pixel, seed) <= params.tolerance * CHANNEL_MASK + DISTANCE_EPSILON

    private fun isWall(pixel: Int, seed: Int): Boolean {
        val threshold = max(params.tolerance * WALL_TOLERANCE_SCALE, MIN_WALL_THRESHOLD) * CHANNEL_MASK
        return colorDistance(pixel, seed) >= threshold
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val alphaA = Composite.alpha(a)
        val alphaB = Composite.alpha(b)
        return maxOf(
            kotlin.math.abs(alphaA - alphaB),
            kotlin.math.abs(straight(Composite.red(a), alphaA) - straight(Composite.red(b), alphaB)),
            kotlin.math.abs(straight(Composite.green(a), alphaA) - straight(Composite.green(b), alphaB)),
            kotlin.math.abs(straight(Composite.blue(a), alphaA) - straight(Composite.blue(b), alphaB)),
        )
    }

    private fun straight(channel: Int, alpha: Int): Int {
        if (alpha == 0) return 0
        return minOf(CHANNEL_MASK, (channel * CHANNEL_MASK + alpha / 2) / alpha)
    }

    private fun pollRow(row: Int, isCancelled: () -> Boolean): Boolean =
        row % CANCEL_POLL_ROWS == 0 && isCancelled()

    private class Bounds {
        private var left = Int.MAX_VALUE
        private var top = Int.MAX_VALUE
        private var right = Int.MIN_VALUE
        private var bottom = Int.MIN_VALUE

        val isEmpty: Boolean get() = left == Int.MAX_VALUE

        fun include(x: Int, y: Int) {
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }

        fun toRect(): IntRect = if (isEmpty) IntRect.EMPTY else IntRect(left, top, right, bottom)
    }

    private class SpanStack {
        private var rows = IntArray(INITIAL_SPAN_CAPACITY)
        private var lefts = IntArray(INITIAL_SPAN_CAPACITY)
        private var rights = IntArray(INITIAL_SPAN_CAPACITY)
        private var size = 0

        val isNotEmpty: Boolean get() = size > 0

        fun push(y: Int, left: Int, right: Int) {
            if (size == rows.size) grow()
            rows[size] = y
            lefts[size] = left
            rights[size] = right
            size++
        }

        fun pop(): Span {
            size--
            return Span(rows[size], lefts[size], rights[size])
        }

        private fun grow() {
            val capacity = rows.size * 2
            rows = rows.copyOf(capacity)
            lefts = lefts.copyOf(capacity)
            rights = rights.copyOf(capacity)
        }
    }

    private data class Span(val y: Int, val left: Int, val right: Int)

    private companion object {
        const val CHANNEL_MASK = 0xFF
        const val EMPTY_BYTE: Byte = 0
        const val COVERED_BYTE: Byte = -1
        const val NO_POSITION = -1
        const val INITIAL_SPAN_CAPACITY = 64
        const val CANCEL_POLL_ROWS = 64
        const val PROGRESS_SPANS = 64
        const val AA_RADIUS = 1
        const val WALL_TOLERANCE_SCALE = 2f
        const val MIN_WALL_THRESHOLD = 0.5f
        const val DISTANCE_EPSILON = 1e-4f
        const val PROGRESS_REGION = 0.5f
        const val PROGRESS_EXPAND_HALF = 0.15f
        const val PROGRESS_AFTER_EXPAND = PROGRESS_REGION + PROGRESS_EXPAND_HALF * 2f
        const val PROGRESS_AA = 0.2f
        const val PROGRESS_COMPLETE = 1f
    }
}
