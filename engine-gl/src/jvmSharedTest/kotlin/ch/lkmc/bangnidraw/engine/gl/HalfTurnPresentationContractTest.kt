package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class HalfTurnPresentationContractTest {

    @Test
    fun `half turn uses one effective transform for presentation and damage`() {
        val source = source(CANVAS_RENDERER_PATH)
        val frame = section(source, FRAME_START, STROKE_START)
        val stroke = section(source, STROKE_START, STROKE_END)
        val policy = section(source, EFFECTIVE_TRANSFORM_START, PRESENT_START)

        assertTrue(
            EFFECTIVE_TRANSFORM_DECLARATION.containsMatchIn(frame),
            "full frames must resolve the Samsung-safe effective buffer transform",
        )
        assertTrue(
            FRAME_PRESENT_USES_EFFECTIVE_TRANSFORM.containsMatchIn(frame),
            "full-frame presentation must consume the effective transform",
        )
        assertTrue(
            EFFECTIVE_TRANSFORM_DECLARATION.containsMatchIn(stroke),
            "front frames must resolve the Samsung-safe effective buffer transform",
        )
        assertTrue(
            FRONT_SCISSOR_USES_EFFECTIVE_TRANSFORM.containsMatchIn(stroke),
            "front damage and presentation must stay in the same effective buffer space",
        )
        assertTrue(
            FRONT_PRESENT_USES_EFFECTIVE_TRANSFORM.containsMatchIn(stroke),
            "front presentation must consume the same transform as its damage scissor",
        )
        assertTrue(
            POLICY_DECISION.containsMatchIn(policy) &&
                PRESERVE_LIBRARY_TRANSFORM.containsMatchIn(policy) &&
                NEUTRALIZE_TO_IDENTITY.containsMatchIn(policy),
            "renderer wiring must preserve non-half-turns and neutralize only the policy match",
        )
    }

    @Test
    fun `half turn forces identity consumer transform on both surface layers`() {
        val source = source(ENGINE_SESSION_PATH)
        val frontDraw = section(source, FRONT_DRAW_START, MULTI_DRAW_START)
        val multiDraw = section(source, MULTI_DRAW_START, FRONT_COMPLETE_START)
        val frontComplete = section(source, FRONT_COMPLETE_START, MULTI_COMPLETE_START)
        val multiComplete = section(source, MULTI_COMPLETE_START, CALLBACK_END)
        val capture = section(source, CAPTURE_START, CALLBACK_END)

        assertTrue(
            HALF_TURN_CAPTURE.containsMatchIn(frontDraw),
            "front rendering must remember whether its producer matrix is a half turn",
        )
        assertTrue(
            HALF_TURN_CAPTURE.containsMatchIn(multiDraw),
            "multi rendering must remember whether its producer matrix is a half turn",
        )
        assertTrue(
            identityOverride(FRONT_SURFACE).containsMatchIn(frontComplete),
            "the front transaction must neutralize graphics-core's half-turn consumer transform",
        )
        assertTrue(
            identityOverride(MULTI_SURFACE).containsMatchIn(multiComplete),
            "the multi transaction must neutralize graphics-core's half-turn consumer transform",
        )
        assertTrue(
            POLICY_DECISION.containsMatchIn(capture) &&
                HALF_TURN_DECISION.containsMatchIn(capture),
            "surface-control wiring must consume the same pure half-turn decision",
        )
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue(startIndex >= 0, "section start is missing: $start")
        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        assertTrue(endIndex >= contentStart, "section end is missing: $end")
        return source.substring(contentStart, endIndex)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private fun identityOverride(surface: String): Regex = Regex(
        """if\s*\(\s*\w*[Hh]alfTurn\w*\s*\)\s*\{?""" +
            """[\s\S]{0,300}?transaction\.setBufferTransform\(\s*""" +
            Regex.escape(surface) +
            """\s*,\s*(?:SurfaceControlCompat\.)?BUFFER_TRANSFORM_IDENTITY\s*,?\s*\)""",
    )

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val CANVAS_RENDERER_PATH =
            "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
        const val ENGINE_SESSION_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/EngineSession.kt"
        const val FRAME_START = "fun drawFrame("
        const val STROKE_START = "fun drawStrokeFrame("
        const val STROKE_END = "/**\n     * Hands one front-buffered frame's timings"
        const val FRONT_DRAW_START = "override fun onDrawFrontBufferedLayer("
        const val MULTI_DRAW_START = "override fun onDrawMultiBufferedLayer("
        const val FRONT_COMPLETE_START = "override fun onFrontBufferedLayerRenderComplete("
        const val MULTI_COMPLETE_START = "override fun onMultiBufferedLayerRenderComplete("
        const val CAPTURE_START = "private fun captureHalfTurn("
        const val CALLBACK_END = "\n    }\n\n    private fun dispatch("
        const val EFFECTIVE_TRANSFORM_START = "private fun effectiveBufferTransform("
        const val PRESENT_START = "private fun presentToWindow("
        const val FRONT_SURFACE = "frontBufferedLayerSurfaceControl"
        const val MULTI_SURFACE = "multiBufferedLayerSurfaceControl"

        val EFFECTIVE_TRANSFORM_DECLARATION = Regex(
            """val\s+effectiveBufferTransform\s*=""",
        )
        val FRAME_PRESENT_USES_EFFECTIVE_TRANSFORM = Regex(
            """presentToWindow\(\s*frameBufferId,\s*bufferWidth,\s*bufferHeight,""" +
                """\s*effectiveBufferTransform,\s*null,?\s*\)""",
        )
        val FRONT_SCISSOR_USES_EFFECTIVE_TRANSFORM = Regex(
            """BufferScissor\.bounds\(\s*presentWindowRect,""" +
                """\s*effectiveBufferTransform,\s*bufferWidth,\s*bufferHeight,?\s*\)""",
        )
        val FRONT_PRESENT_USES_EFFECTIVE_TRANSFORM = Regex(
            """presentToWindow\(\s*frameBufferId,\s*bufferWidth,\s*bufferHeight,""" +
                """\s*effectiveBufferTransform,\s*bufferRect,?\s*\)""",
        )
        val HALF_TURN_CAPTURE = Regex(
            """captureHalfTurn\(\s*width,\s*height,\s*bufferInfo,""" +
                """\s*transform,?\s*\)""",
        )
        val POLICY_DECISION = Regex("""BufferPresentationPolicy\.decide\(""")
        val PRESERVE_LIBRARY_TRANSFORM = Regex(
            """BufferPresentationDecision\.USE_LIBRARY_TRANSFORM\s*->\s*bufferTransform""",
        )
        val NEUTRALIZE_TO_IDENTITY = Regex(
            """BufferPresentationDecision\.NEUTRALIZE_HALF_TURN\s*->\s*identity""",
        )
        val HALF_TURN_DECISION = Regex(
            """==\s*BufferPresentationDecision\.NEUTRALIZE_HALF_TURN""",
        )
    }
}
