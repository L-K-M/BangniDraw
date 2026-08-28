package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class EngineSessionRenderContractTest {

    @Test
    fun `raw render calls stay in one gated dispatcher`() {
        val source = source(ENGINE_SESSION_PATH)
        val dispatcher = section(source, DISPATCH_START, DISPATCH_END)

        assertEquals(
            1,
            DIRECT_MULTI_CALL.findAll(source).count(),
            "exactly one dispatcher branch may seed the attachment with a direct multi frame",
        )
        assertEquals(1, COMMIT_CALL.findAll(source).count(), "raw commit must have one owner")
        assertEquals(1, FRONT_CALL.findAll(source).count(), "raw front render must have one owner")
        assertTrue(
            BOOTSTRAP_DISPATCH.containsMatchIn(dispatcher),
            "only BOOTSTRAP may issue the direct multi frame",
        )
        assertTrue(COMMIT_CALL.containsMatchIn(dispatcher), "dispatcher must own commit")
        assertTrue(FRONT_CALL.containsMatchIn(dispatcher), "dispatcher must own front render")
    }

    @Test
    fun `surface readiness crosses the GL FIFO marker`() {
        val source = source(ENGINE_SESSION_PATH)
        val lifecycle = section(source, SURFACE_CALLBACK_START, SUPPORT_START)
        val initialization = section(source, ENGINE_INIT_START, SUPPORT_START)
        val marker = section(source, SURFACE_MARKER_START, DISPATCH_START)
        val release = section(source, RELEASE_START, RELEASE_END)

        assertTrue(SURFACE_CHANGED_CALL in lifecycle, "surface changes must start a generation")
        assertTrue(DISCARD_SURFACE_CALL in lifecycle, "surface destruction must invalidate it")
        assertTrue(CALLBACK_REGISTRATION in lifecycle, "the attachment callback must be registered")
        assertTrue(VALID_SURFACE_CHECK in initialization, "an already-valid holder must attach")
        assertTrue(
            INITIAL_DRIVER_CREATION in initialization,
            "an already-valid holder must create a driver",
        )
        assertTrue(GL_MARKER_CALL in marker, "readiness must cross the graphics-core GL queue")
        assertTrue(SURFACE_READY_CALL in marker, "only the marker's generation may become ready")
        assertTrue(
            marker.indexOf(GL_MARKER_CALL) < marker.indexOf(SURFACE_READY_CALL),
            "readiness must follow the graphics-core GL marker",
        )
        assertTrue(CALLBACK_REMOVAL in release, "release must remove the attachment callback")
    }

    @Test
    fun `each surface generation gets a fresh graphics-core driver`() {
        val source = source(ENGINE_SESSION_PATH)
        val lifecycle = section(source, SURFACE_CALLBACK_START, SUPPORT_START)
        val driver = section(source, DRIVER_FACTORY_START, DRIVER_FACTORY_END)
        val adapter = section(source, DRIVER_ADAPTER_START, RELEASE_START)

        assertTrue(SHARED_GL_DECLARATION in source, "the EGL context must outlive drivers")
        assertTrue(CALLBACK2_DECLARATION in lifecycle, "redraw callbacks must be intercepted")
        assertTrue(SYNC_REDRAW_CALLBACK in lifecycle, "sync redraw must replace its driver")
        assertTrue(ASYNC_REDRAW_CALLBACK in lifecycle, "async redraw must replace its driver")
        val changed = driver.indexOf(SURFACE_GENERATION_CALL)
        val discarded = driver.indexOf(DISCARD_DRIVER_CALL)
        val created = driver.indexOf(DIRECT_DRIVER_CREATION)
        assertTrue(changed >= 0, "driver replacement must start a generation")
        assertTrue(discarded > changed, "the stale driver must be discarded first")
        assertTrue(created > discarded, "sync redraw must create its driver before waiting")
        assertTrue(SURFACE_DESTROYED_CALL in driver, "surface loss must invalidate its generation")
        assertTrue(GENERATION_DRIVER in driver, "each driver must capture its generation")
        assertTrue(SHARED_GL_DRIVER in driver, "drivers must share the persistent EGL context")
        assertEquals(
            DRIVER_CALLBACK_COUNT,
            GENERATION_GUARD.findAll(adapter).count(),
            "every driver callback must reject stale generations",
        )
        assertFalse(WRAPPER_EXECUTE in source, "commands must not depend on a discarded driver")
        assertFalse(DRIVER_GENERATION_FIELD in source, "generation decisions belong to the pure gate")
    }

    @Test
    fun `surface redraw completion follows the current multi presentation`() {
        val source = source(ENGINE_SESSION_PATH)
        val sync = section(source, SYNC_REDRAW_CALLBACK, ASYNC_REDRAW_CALLBACK)
        val async = section(source, ASYNC_REDRAW_CALLBACK, SURFACE_CALLBACK_END)
        val completion = section(source, DRIVER_MULTI_COMPLETE_START, DRIVER_MULTI_COMPLETE_END)

        assertTrue(SYNC_WAIT.containsMatchIn(sync), "sync redraw must wait for its frame")
        assertFalse(
            DIRECT_ASYNC_FINISH in async,
            "async redraw must not finish before its frame",
        )
        assertTrue(
            DRAWING_FINISHED.findAll(async).count() >= FORWARDED_PARAMETER_OCCURRENCES,
            "async redraw must retain its completion",
        )
        assertTrue(QUEUE_REDRAW_COMPLETION in source, "redraw completions must be retained")
        assertTrue(
            QUEUE_WITH_GENERATION in source,
            "redraw completions must be keyed by generation",
        )

        val guard = completion.indexOf(GENERATION_GUARD_TEXT)
        val marker = completion.indexOf(COMPLETION_GL_MARKER)
        val finish = REDRAW_COMPLETION.find(completion)?.range?.first ?: -1
        assertTrue(guard >= 0, "redraw completion must reject stale drivers")
        assertTrue(marker > guard, "redraw completion must cross the GL FIFO")
        assertTrue(finish > marker, "the presented frame must finish redraw callbacks")
        assertTrue(FINISH_WITH_GENERATION in completion, "only the accepted generation may finish")
        assertFalse(
            TRANSACTION_COMMITTED_LISTENER in source,
            "API 29 and 30 do not deliver transaction committed listeners",
        )
    }

    @Test
    fun `accepted work stays bound to its driver generation`() {
        val source = source(ENGINE_SESSION_PATH)
        val completion = section(source, COMPLETE_TICK_START, COMPLETE_TICK_END)
        val creation = section(source, CREATE_DRIVER_START, DISCARD_SURFACE_START)
        val dispatcher = section(source, DISPATCH_START, DISPATCH_END)

        assertTrue(DRIVER_ATTACHMENT_DECLARATION in source, "driver and generation must be atomic")
        assertTrue(RENDER_PLAN_DECLARATION in source, "gate actions must retain their generation")
        assertTrue(
            PLAN_DISPATCH.containsMatchIn(completion),
            "completion must target its accepted driver",
        )
        assertEquals(
            SURFACE_READY_DISPATCH_COUNT,
            SURFACE_READY_CALL_PATTERN.findAll(creation).count(),
            "each readiness branch must request its generation",
        )
        assertEquals(
            SURFACE_READY_DISPATCH_COUNT,
            READY_PLAN_DISPATCH.findAll(creation).count(),
            "each readiness branch must target its generation",
        )
        assertTrue(GENERATION_MATCH in dispatcher, "dispatcher must reject a replaced driver")
        assertFalse(
            UNBOUND_DISPATCH.containsMatchIn(source),
            "raw render dispatch must never lose its generation",
        )
    }

    @Test
    fun `release cleans GL resources before stopping the shared context`() {
        val source = source(ENGINE_SESSION_PATH)
        val release = section(source, RELEASE_START, RELEASE_END)

        val cleanup = release.indexOf(RENDERER_RELEASE_CALL)
        val stop = release.indexOf(SHARED_GL_STOP_CALL)
        val redraws = release.indexOf(ABANDON_REDRAW_COMPLETIONS)
        assertTrue(redraws >= 0, "release must unblock SurfaceHolder redraw callbacks")
        assertTrue(cleanup >= 0, "canvas GL cleanup is missing")
        assertTrue(stop > cleanup, "the shared context must stop after canvas cleanup is queued")
        assertTrue(WET_TICK_REMOVAL in release, "release must stop wet-overlay refreshes")
    }

    @Test
    fun `wet overlay refreshes until its final clearing frame`() {
        val source = source(ENGINE_SESSION_PATH)
        val refresh = section(source, WET_REFRESH_START, WET_REFRESH_END)

        assertTrue("renderer.refreshWatercolorOverlay()" in refresh)
        assertTrue("WatercolorOverlayKernel.Refresh.REDRAW_AND_CONTINUE" in refresh)
        assertTrue("WatercolorOverlayKernel.REFRESH_MILLIS" in refresh)
        assertTrue("requestWetOverlayRedraw()" in refresh)
        assertTrue("pendingWetOverlayDirty" in source)
        assertTrue("attachmentGate.requestFront()" in refresh)
    }

    @Test
    fun `wet overlay clock coalesces instead of postponing active refreshes`() {
        val source = source(ENGINE_SESSION_PATH)
        val refresh = section(source, WET_REFRESH_START, WET_REFRESH_END)
        val tick = refresh.substringBefore("private fun applyWetOverlayRefresh(")
        val pump = refresh.substringAfter("private fun pumpWetOverlay()")

        assertFalse("pollHandler.removeCallbacks(wetOverlayTick)" in pump)
        assertTrue("wetOverlayTickScheduled.compareAndSet(false, true)" in pump)
        assertTrue(
            tick.indexOf("wetOverlayTickScheduled.set(false)") in
                0 until tick.indexOf("renderer.refreshWatercolorOverlay()"),
            "the running tick must release the coalescing latch before it can re-arm",
        )
    }

    @Test
    fun `wet overlay damage survives a failed scene presentation`() {
        val source = source(ENGINE_SESSION_PATH)
        val front = section(source, FRONT_DRAW_START, FRONT_DRAW_END)
        val multi = source.substringAfter("private fun onDrawMultiBufferedLayer(")
            .substringBefore("private fun ensureContext()")
        val refresh = section(source, WET_REFRESH_START, WET_REFRESH_END)
        val retry = refresh.substringAfter("private fun retryWetOverlayPresentation()")
            .substringBefore("private fun clearPendingWetOverlay()")

        assertTrue("val presented = renderer.drawFrame(" in multi)
        assertPresentationAcknowledged(front)
        assertPresentationAcknowledged(multi)
        assertTrue("refresh.copy(dirty = pendingWetOverlayDirty)" in refresh)
        val budgetGuard = retry.indexOf("wetOverlayPresentationRetries <= 0")
        val decrement = retry.indexOf("wetOverlayPresentationRetries -= 1")
        val repump = retry.indexOf("pumpWetOverlay()")
        assertTrue(budgetGuard >= 0 && decrement > budgetGuard && repump > decrement)
        assertTrue("const val WET_OVERLAY_PRESENT_RETRIES = 1" in source)
    }

    private fun assertPresentationAcknowledged(source: String) {
        val success = source.indexOf("if (presented) {")
        val clear = source.indexOf("clearPendingWetOverlay()", success)
        val failure = source.indexOf("else {", clear)
        val retry = source.indexOf("retryWetOverlayPresentation()", failure)

        assertTrue(success >= 0 && clear > success && failure > clear && retry > failure)
    }

    @Test
    fun `not merged completions return on the main thread`() {
        val source = source(ENGINE_SESSION_PATH)
        val completion = section(source, STROKE_FALLBACK_START, STROKE_FALLBACK_END)
        val release = section(source, RELEASE_START, RELEASE_END)

        assertTrue(
            POST_FALLBACK.containsMatchIn(completion),
            "ordinary fallback must post to main",
        )
        assertTrue(
            POST_DROPPED_FALLBACK.containsMatchIn(release),
            "release fallback must post to main",
        )
    }

    @Test
    fun `all scene and front entry points use the gate`() {
        val source = source(ENGINE_SESSION_PATH)
        val redraw = section(source, REDRAW_START, REDRAW_END)
        val stamp = section(source, STAMP_START, STAMP_END)
        val end = section(source, END_STROKE_START, END_STROKE_END)
        val completion = section(source, COMPLETE_TICK_START, COMPLETE_TICK_END)
        val completionCallback = section(
            source,
            DRIVER_MULTI_COMPLETE_START,
            DRIVER_MULTI_COMPLETE_END,
        )

        assertTrue(SCENE_GATE_CALL in redraw, "redraw must request a gated scene")
        assertTrue(FRONT_GATE_CALL in stamp, "dabs must request a gated front render")
        assertTrue(COMPLETION_GL_MARKER in completionCallback, "completion must retain its generation")
        assertTrue(COMPLETION_GATE_CALL in completion, "stale completions must re-enter the gate")
        assertTrue(FRONT_GATE_CALL in completion, "front resume must re-enter the gate")
        assertTrue(END_GATE_CALL in end, "pen-up must request a gated commit")
        val merge = end.indexOf(EXECUTE_CALL)
        val commit = end.indexOf(END_GATE_CALL)
        assertTrue(merge >= 0, "stroke merge must be queued")
        assertTrue(
            merge < commit,
            "stroke merge must queue before its commit",
        )
        assertTrue(
            stamp.indexOf(PENDING_BATCH_ADD) < stamp.indexOf(FRONT_GATE_CALL),
            "dabs must enter the owned queue before their render request",
        )
        assertFalse(LITERAL_RENDER_DISPATCH.containsMatchIn(source), "entry points must use the gate")
        assertFalse(SHARED_COMPLETION_TICK in source, "completion work must capture its generation")

        val gate = completion.indexOf(COMPLETION_GATE_CALL)
        val accepted = completion.indexOf(ACCEPTED_COMPLETION_GUARD)
        val policy = completion.indexOf(RENDER_POLICY_COMPLETION)
        assertTrue(gate >= 0, "completion must enter the attachment gate")
        assertTrue(accepted > gate, "ignored completions must return atomically")
        assertTrue(policy > accepted, "ignored completions must not mutate stroke recovery")
    }

    @Test
    fun `front frames snapshot batches while terminal drains are exhaustive`() {
        val source = source(ENGINE_SESSION_PATH)
        val front = section(source, FRONT_DRAW_START, FRONT_DRAW_END)
        val end = section(source, END_STROKE_START, END_STROKE_END)
        val cancel = section(source, CANCEL_STROKE_START, CANCEL_STROKE_END)

        assertTrue(
            FRAME_SNAPSHOT_SCOPE in front,
            "a front frame must not chase batches published while it is drawing",
        )
        assertTrue(
            EXHAUSTIVE_SCOPE in end,
            "pen-up must consume every batch before merging",
        )
        assertTrue(
            EXHAUSTIVE_SCOPE in cancel,
            "cancel must return every queued ring slot",
        )
    }

    @Test
    fun `front completion reenters the generation gate after its GL marker`() {
        val source = source(ENGINE_SESSION_PATH)
        val completion = section(source, DRIVER_FRONT_COMPLETE_START, DRIVER_FRONT_COMPLETE_END)

        val guard = completion.indexOf(GENERATION_GUARD_TEXT)
        val marker = completion.indexOf(COMPLETION_GL_MARKER)
        val gate = completion.indexOf(FRONT_COMPLETION_GATE_CALL)
        val accepted = completion.indexOf(ACCEPTED_COMPLETION_GUARD)
        val dispatch = completion.indexOf(PLAN_DISPATCH_CALL)

        assertTrue(guard >= 0, "front completion must reject stale drivers")
        assertTrue(marker > guard, "front completion must cross the GL FIFO")
        assertTrue(gate > marker, "front completion must re-enter the pure gate")
        assertTrue(accepted > gate, "duplicate front completions must stop atomically")
        assertTrue(dispatch > accepted, "accepted work must target the completing generation")
    }

    @Test
    fun `canvas startup configures one scene before one redraw`() {
        val source = source(CANVAS_SURFACE_PATH)
        val factory = section(source, FACTORY_START, FACTORY_END)
        val configure = section(factory, CONFIGURE_CALL, CONFIGURE_CALL_END)

        assertTrue(CONFIGURE_CALL in factory, "startup scene configuration is missing")
        for (argument in CONFIGURE_ARGUMENTS) {
            assertTrue(argument in configure, "startup configuration is missing $argument")
        }
        assertFalse(SET_STACK_CALL in factory, "startup must not queue a stack redraw")
        assertFalse(SET_PAPER_CALL in factory, "startup must not queue a paper redraw")
        assertFalse(SET_VIEW_CALL in factory, "startup must not queue a view redraw")
    }

    @Test
    fun `scene configuration applies all values before one redraw`() {
        val source = source(ENGINE_SESSION_PATH)
        val configure = section(source, CONFIGURE_START, CONFIGURE_END)

        assertTrue(RENDERER_STACK_CALL in configure, "startup stack configuration is missing")
        assertTrue(RENDERER_PAPER_CALL in configure, "startup paper configuration is missing")
        assertTrue(RENDERER_VIEW_CALL in configure, "startup view configuration is missing")
        assertEquals(1, REDRAW_CALL.findAll(configure).count(), "startup must redraw once")
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

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val ENGINE_SESSION_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/EngineSession.kt"
        const val CANVAS_SURFACE_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasSurface.kt"
        const val REDRAW_START = "private fun redrawNow()"
        const val REDRAW_END = "/** Runs [block] on the GL thread. */"
        const val STAMP_START = "fun stampDabs(batch: DabBatch)"
        const val STAMP_END = "/** The next commit revision"
        const val END_STROKE_START =
            "fun endStroke(opacityCeiling: Float, onCommitted: () -> Unit)"
        const val END_STROKE_END = "/**\n     * §10.1's between-frame poll"
        const val FRONT_DRAW_START = "private fun onDrawFrontBufferedLayer("
        const val FRONT_DRAW_END = "/**\n     * Consumes every published batch"
        const val CANCEL_STROKE_START = "internal fun cancelStroke("
        const val CANCEL_STROKE_END = "fun invalidate(op: SandwichPolicy.Op)"
        const val COMPLETE_TICK_START = "private fun completeMultiDraw(generation: Long)"
        const val COMPLETE_TICK_END = "var onRmwStarted:"
        const val SURFACE_CALLBACK_START = "private val surfaceCallback = object"
        const val SURFACE_CALLBACK_END = "\n    }\n\n    init {"
        const val ENGINE_INIT_START = "\n    init {"
        const val SUPPORT_START = "/**\n     * True once the device has been probed"
        const val SURFACE_MARKER_START = "private fun scheduleDriverCreation("
        const val DISPATCH_START = "private fun dispatch("
        const val DISPATCH_END = "// ---------------------------------------------------------------- façade"
        const val RELEASE_START = "fun release()"
        const val RELEASE_END = "private companion object"
        const val WET_REFRESH_START = "private val wetOverlayTick = Runnable"
        const val WET_REFRESH_END = "/** Renders panel thumbnails"
        const val WET_TICK_REMOVAL = "pollHandler.removeCallbacks(wetOverlayTick)"
        const val STROKE_FALLBACK_START = "private fun completeStrokeWithoutMerge("
        const val STROKE_FALLBACK_END = "/**\n     * §10.1's between-frame poll"
        const val DRIVER_FACTORY_START = "private fun scheduleDriverCreation("
        const val DRIVER_FACTORY_END = "private inner class GenerationCallback"
        const val CREATE_DRIVER_START = "private fun createDriver("
        const val DISCARD_SURFACE_START = "private fun discardSurface()"
        const val DRIVER_ADAPTER_START = "private inner class GenerationCallback"
        const val CONFIGURE_START = "internal fun configure("
        const val CONFIGURE_END = "/**\n     * Sets the view transform and redraws."
        const val FACTORY_START = "factory = { ctx ->"
        const val FACTORY_END = "update = { surface ->"
        val DIRECT_MULTI_CALL = Regex(
            """\b(?:frontBuffered|driver)\??\.renderMultiBufferedLayer\(""",
        )
        val BOOTSTRAP_DISPATCH = Regex(
            """RenderDispatch\.BOOTSTRAP\s*->\s*driver\.renderMultiBufferedLayer\(emptyList\(\)\)""",
        )
        val COMMIT_CALL = Regex("""\b(?:frontBuffered|driver)\??\.commit\(\)""")
        val FRONT_CALL = Regex(
            """\b(?:frontBuffered|driver)\??\.renderFrontBufferedLayer\(""",
        )
        const val SURFACE_CHANGED_CALL = "scheduleDriverCreation()"
        const val SURFACE_DESTROYED_CALL = "attachmentGate.surfaceDestroyed()"
        const val DISCARD_SURFACE_CALL = "discardSurface()"
        const val CALLBACK_REGISTRATION = "surfaceView.holder.addCallback(surfaceCallback)"
        const val VALID_SURFACE_CHECK = "surfaceView.holder.surface.isValid"
        const val INITIAL_DRIVER_CREATION = "scheduleDriverCreation()"
        const val GL_MARKER_CALL = "glRenderer.execute {"
        const val SURFACE_READY_CALL = "attachmentGate.surfaceReady(generation)"
        val SURFACE_READY_CALL_PATTERN = Regex("""attachmentGate\.surfaceReady\(generation\)""")
        const val CALLBACK_REMOVAL = "surfaceView.holder.removeCallback(surfaceCallback)"
        const val SHARED_GL_DECLARATION = "private val glRenderer = GLRenderer().apply { start() }"
        const val CALLBACK2_DECLARATION = ": SurfaceHolder.Callback2"
        const val SYNC_REDRAW_CALLBACK = "override fun surfaceRedrawNeeded("
        const val ASYNC_REDRAW_CALLBACK = "override fun surfaceRedrawNeededAsync("
        const val DRIVER_MULTI_COMPLETE_START =
            "override fun onMultiBufferedLayerRenderComplete("
        const val DRIVER_FRONT_COMPLETE_START =
            "override fun onFrontBufferedLayerRenderComplete("
        const val DRIVER_FRONT_COMPLETE_END =
            "override fun onMultiBufferedLayerRenderComplete("
        const val DRIVER_MULTI_COMPLETE_END = "\n        }\n    }\n\n    private fun dispatch("
        const val SURFACE_GENERATION_CALL = "attachmentGate.surfaceChanged()"
        const val DISCARD_DRIVER_CALL = "discardDriver()"
        const val DIRECT_DRIVER_CREATION = "createDriver(generation)"
        const val GENERATION_DRIVER = "GenerationCallback(generation)"
        const val DRIVER_ATTACHMENT_DECLARATION = "private data class DriverAttachment("
        const val RENDER_PLAN_DECLARATION = "plan: AttachmentRenderPlan"
        val PLAN_DISPATCH = Regex("""dispatch\(plan\)""")
        val READY_PLAN_DISPATCH = Regex(
            """dispatch\(attachmentGate\.surfaceReady\(generation\)\)""",
        )
        const val SURFACE_READY_DISPATCH_COUNT = 2
        const val GENERATION_MATCH =
            "attachment.generation != plan.generation"
        val UNBOUND_DISPATCH = Regex(
            """private fun dispatch\(\s*plan:\s*RenderDispatch""",
        )
        const val SHARED_GL_DRIVER = "glRenderer = glRenderer"
        val GENERATION_GUARD = Regex(
            """if \(!attachmentGate\.acceptsDriverCallback\(generation\)\) return""",
        )
        const val GENERATION_GUARD_TEXT =
            "if (!attachmentGate.acceptsDriverCallback(generation)) return"
        val SYNC_WAIT = Regex("""\.(?:await|join|get)\(\)""")
        const val DIRECT_ASYNC_FINISH = "drawingFinished.run()"
        val DRAWING_FINISHED = Regex("""\bdrawingFinished\b""")
        const val FORWARDED_PARAMETER_OCCURRENCES = 2
        const val QUEUE_REDRAW_COMPLETION = "queueRedrawCompletion"
        const val QUEUE_WITH_GENERATION = "queueRedrawCompletion(generation, it)"
        const val TRANSACTION_COMMITTED_LISTENER = "addTransactionCommittedListener"
        const val COMPLETION_GL_MARKER = "glRenderer.execute {"
        val REDRAW_COMPLETION = Regex("""\b(?:complete|finish)\w*Redraw\w*\(generation\)""")
        const val FINISH_WITH_GENERATION = "finishRedrawCompletions(generation)"
        const val DRIVER_CALLBACK_COUNT = 4
        const val WRAPPER_EXECUTE = "frontBuffered.execute"
        const val DRIVER_GENERATION_FIELD = "driverGeneration"
        const val RENDERER_RELEASE_CALL = "renderer.release()"
        const val SHARED_GL_STOP_CALL = "glRenderer.stop(false)"
        const val ABANDON_REDRAW_COMPLETIONS = "abandonRedrawCompletions()"
        val POST_FALLBACK = Regex("""pollHandler\.post\(fallback\)""")
        val POST_DROPPED_FALLBACK = Regex(
            """pollHandler\.post\s*\{\s*droppedStroke\?\.invoke\(\)""",
        )
        const val SCENE_GATE_CALL = "attachmentGate.requestScene()"
        const val FRONT_GATE_CALL = "attachmentGate.requestFront()"
        const val END_GATE_CALL = "attachmentGate.endStroke()"
        const val PENDING_BATCH_ADD = "pendingBatches.add(batch)"
        val LITERAL_RENDER_DISPATCH = Regex(
            """dispatch\(RenderDispatch\.(?:BOOTSTRAP|COMMIT|FRONT)""",
        )
        const val COMPLETION_GATE_CALL = "attachmentGate.multiDrawCompleted(generation)"
        const val FRONT_COMPLETION_GATE_CALL = "attachmentGate.frontDrawCompleted(generation)"
        const val ACCEPTED_COMPLETION_GUARD =
            "completion !is AttachmentCompletion.Accepted"
        const val PLAN_DISPATCH_CALL = "dispatch(completion.plan)"
        const val RENDER_POLICY_COMPLETION = "renderPolicy.onMultiDrawCompleted()"
        const val SHARED_COMPLETION_TICK = "multiCompleteTick"
        const val EXECUTE_CALL = "glRenderer.execute {"
        const val FRAME_SNAPSHOT_SCOPE = "PendingBatchDrainScope.FRAME_SNAPSHOT"
        const val EXHAUSTIVE_SCOPE = "PendingBatchDrainScope.EXHAUSTIVE"
        const val CONFIGURE_CALL = "session.configure("
        const val CONFIGURE_CALL_END = "onSession(session)"
        const val SET_STACK_CALL = "session.setStack(stack)"
        const val SET_PAPER_CALL = "session.setPaperColor(paperColor)"
        const val SET_VIEW_CALL = "session.setView(view)"
        const val RENDERER_STACK_CALL = "renderer.setStack(stack)"
        const val RENDERER_PAPER_CALL = "renderer.setPaperColor(paperColor)"
        const val RENDERER_VIEW_CALL = "renderer.setView(view)"
        val REDRAW_CALL = Regex("""\bredraw\(\)""")
        val CONFIGURE_ARGUMENTS = listOf(
            "stack = stack,",
            "paperColor = paperColor,",
            "appearance = appearance,",
            "view = view,",
            "tracingReference = tracingReference,",
        )
    }
}
