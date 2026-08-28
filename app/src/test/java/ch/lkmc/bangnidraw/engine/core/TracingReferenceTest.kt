package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TracingReferenceTest {

    @Test
    fun `fit centres the image without cropping it`() {
        val transform = ReferenceTransform.fit(
            imageWidth = 1_000,
            imageHeight = 500,
            canvasWidth = 2_000,
            canvasHeight = 2_000,
        )

        assertEquals(2f, transform.xx)
        assertEquals(2f, transform.yy)
        assertEquals(0f, transform.xy)
        assertEquals(0f, transform.yx)
        assertEquals(0f, transform.tx)
        assertEquals(500f, transform.ty)
    }

    @Test
    fun `gesture keeps its pivot fixed before applying pan`() {
        val transform = ReferenceTransform.IDENTITY.gesture(
            pivotX = 40f,
            pivotY = 30f,
            panX = 7f,
            panY = -3f,
            zoom = 2f,
            rotationDelta = (PI / 2.0).toFloat(),
        )

        assertPoint(47f, 27f, transform.apply(40f, 30f))
        assertPoint(47f, 47f, transform.apply(50f, 30f))
    }

    @Test
    fun `canvas screen transform composes after the reference`() {
        val reference = ReferenceTransform(
            xx = 2f,
            xy = 0f,
            yx = 0f,
            yy = 3f,
            tx = 5f,
            ty = 7f,
        )
        val canvas = ScreenTransform(a = 0f, b = 2f, tx = 100f, ty = 200f)

        val screen = reference.followedBy(canvas)

        assertPoint(74f, 214f, screen.apply(1f, 2f))
    }

    @Test
    fun `source bounds invert an affine transform and clip to the image`() {
        val transform = ReferenceTransform(
            xx = 2f,
            xy = 0f,
            yx = 0f,
            yy = 2f,
            tx = 10f,
            ty = 20f,
        )

        assertEquals(
            IntRect(0, 0, 45, 40),
            transform.sourceBoundsOf(
                destination = IntRect(0, 0, 100, 100),
                sourceWidth = 50,
                sourceHeight = 50,
            ),
        )
    }

    @Test
    fun `image canvas bounds report whether the reference leaves the canvas`() {
        val fitted = ReferenceTransform.fit(1_000, 500, 2_000, 2_000)

        // Fitted: the whole image sits inside the canvas rect.
        assertEquals(IntRect(0, 500, 2_000, 1_500), fitted.imageCanvasBounds(1_000, 500))

        // Enlarged about the canvas centre: the bounds escape on every side,
        // which is the state the renderer must keep drawing over the void.
        val magnified = fitted.gesture(
            pivotX = 1_000f,
            pivotY = 1_000f,
            panX = 0f,
            panY = 0f,
            zoom = 2f,
            rotationDelta = 0f,
        )
        val bounds = magnified.imageCanvasBounds(1_000, 500)

        assertTrue(bounds.left < 0, "left $bounds must escape the canvas")
        assertTrue(bounds.top < 500, "top $bounds must escape the canvas")
        assertTrue(bounds.right > 2_000, "right $bounds must escape the canvas")
        assertTrue(bounds.bottom > 1_500, "bottom $bounds must escape the canvas")

        assertEquals(IntRect.EMPTY, ReferenceTransform.IDENTITY.imageCanvasBounds(0, 100))
    }

    @Test
    fun `crop and resize mappings preserve reference alignment`() {
        val reference = ReferenceTransform(
            xx = 1f,
            xy = 0f,
            yx = 0f,
            yy = 1f,
            tx = 20f,
            ty = 30f,
        )
        val cropThenResize = ReferenceTransform(
            xx = 2f,
            xy = 0f,
            yx = 0f,
            yy = 3f,
            tx = -10f,
            ty = -21f,
        )

        assertPoint(
            expectedX = 50f,
            expectedY = 99f,
            actual = reference.followedByCanvas(cropThenResize).apply(10f, 10f),
        )
    }

    @Test
    fun `reference validates persisted values`() {
        assertFailsWith<IllegalArgumentException> {
            TracingReference(
                assetName = "../reference.png",
                imageWidth = 100,
                imageHeight = 100,
                transform = ReferenceTransform.IDENTITY,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TracingReference(
                assetName = "reference.png",
                imageWidth = 100,
                imageHeight = 100,
                transform = ReferenceTransform.IDENTITY,
                opacity = 1.1f,
            )
        }
    }

    @Test
    fun `reference reserves one layer of tile memory`() {
        val reference = TracingReference(
            assetName = "reference.png",
            imageWidth = 100,
            imageHeight = 100,
            transform = ReferenceTransform.IDENTITY,
        )

        assertEquals(
            ReferenceImportDecision.ACCEPT,
            TracingReferencePolicy.importDecision(
                layerCount = 3,
                maxLayers = 4,
                transientImageBytes = PerfConstants.TILE_BYTES.toLong(),
                layerReserve = ReferenceLayerReserve.REQUIRED,
            ),
        )
        assertEquals(
            ReferenceImportDecision.REFUSE_LAYER_BUDGET,
            TracingReferencePolicy.importDecision(
                layerCount = 4,
                maxLayers = 4,
                transientImageBytes = PerfConstants.TILE_BYTES.toLong(),
                layerReserve = ReferenceLayerReserve.REQUIRED,
            ),
        )
        assertEquals(
            ReferenceImportDecision.REFUSE_TRANSIENT_BUDGET,
            TracingReferencePolicy.importDecision(
                layerCount = 3,
                maxLayers = 4,
                transientImageBytes = PerfConstants.TILE_BYTES - 1L,
                layerReserve = ReferenceLayerReserve.HELD,
            ),
        )
        assertEquals(
            3,
            TracingReferencePolicy.layerCap(
                layerCount = 2,
                maxLayers = 4,
                reference = reference,
            ),
        )
        assertEquals(
            4,
            TracingReferencePolicy.layerCap(
                layerCount = 2,
                maxLayers = 4,
                reference = null,
            ),
        )
    }

    @Test
    fun `import decode is bounded to the canvas without upscaling`() {
        assertEquals(
            2_000 to 1_000,
            TracingReferencePolicy.normalizedSize(
                sourceWidth = 4_000,
                sourceHeight = 2_000,
                canvasWidth = 2_000,
                canvasHeight = 2_000,
                maxPixelBytes = Long.MAX_VALUE,
            ),
        )
        assertEquals(
            320 to 240,
            TracingReferencePolicy.normalizedSize(
                sourceWidth = 320,
                sourceHeight = 240,
                canvasWidth = 2_000,
                canvasHeight = 2_000,
                maxPixelBytes = Long.MAX_VALUE,
            ),
        )
        val constrained = TracingReferencePolicy.normalizedSize(
            sourceWidth = 4_000,
            sourceHeight = 2_000,
            canvasWidth = 4_000,
            canvasHeight = 4_000,
            maxPixelBytes = 4_000_000L,
        )

        assertTrue(constrained.first.toLong() * constrained.second * 4L <= 4_000_000L)
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedX, actual.first, absoluteTolerance = 0.0001f)
        assertEquals(expectedY, actual.second, absoluteTolerance = 0.0001f)
    }
}
