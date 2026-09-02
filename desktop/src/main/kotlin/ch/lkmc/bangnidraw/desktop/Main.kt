@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.CanvasPresetId
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DabSpacingPolicy
import ch.lkmc.bangnidraw.engine.core.HsvColor
import ch.lkmc.bangnidraw.engine.core.HsvSelection
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.RgbMixer
import ch.lkmc.bangnidraw.engine.core.RmwStrokePolicy
import ch.lkmc.bangnidraw.engine.core.StrokeMode
import ch.lkmc.bangnidraw.engine.core.StrokeDriver
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.mixbox.MixboxBinding
import ch.lkmc.bangnidraw.input.CanvasInputHost
import ch.lkmc.bangnidraw.input.CanvasTouchHandler
import ch.lkmc.bangnidraw.input.FrameScheduler
import ch.lkmc.bangnidraw.input.PointerSample
import java.awt.Dimension

/**
 * The desktop shell (DESKTOP.md Phase 2, M4). Minimal by design: canvas,
 * brush picker, color, undo/redo, save. String literals are English v1 —
 * the full string migration is a later, separate effort.
 *
 * Compositing is DESKTOP.md's architecture 1: the engine renders its
 * offscreen FBO on its own GL thread, the pixels travel here as an
 * [androidx.compose.ui.graphics.ImageBitmap], and Compose shows them. The
 * per-frame `glReadPixels` copy is the known cost of that architecture —
 * accepted for v1, revisitable with cross-API texture sharing later.
 */

private sealed interface DesktopStartup {
    data class Ready(
        val memory: ch.lkmc.bangnidraw.engine.core.DeviceMemory,
        val context: GlfwEsContext,
    ) : DesktopStartup

    data class Failed(val message: String) : DesktopStartup
}

private enum class DesktopLaunchMode {
    Interactive,
    VerifyRuntime,
    SmokeStartupFailure,
    SmokeWindow;

    companion object {
        fun from(args: Array<String>): DesktopLaunchMode = when {
            VERIFY_RUNTIME_FLAG in args -> VerifyRuntime
            SMOKE_STARTUP_FAILURE_FLAG in args -> SmokeStartupFailure
            SMOKE_WINDOW_FLAG in args -> SmokeWindow
            else -> Interactive
        }
    }
}

fun main(args: Array<String>) {
    val mode = DesktopLaunchMode.from(args)
    if (mode == DesktopLaunchMode.VerifyRuntime) {
        verifyPackagedRuntime()
        return
    }

    val startup = createDesktopStartup()
    try {
        application { DesktopApplication(startup, mode) }
    } finally {
        (startup as? DesktopStartup.Ready)?.context?.destroy()
    }
}

private fun verifyPackagedRuntime() {
    val memory = DesktopPlatform.deviceMemory()
    println("${DesktopBrand.displayName} runtime OK: ${memory.totalMemBytes} bytes")
}

private fun createDesktopStartup(): DesktopStartup = try {
    val memory = DesktopPlatform.deviceMemory()
    DesktopNativeBootstrap.prepare().use { environment ->
        val context = GlfwEsContext.create(INITIAL_GL_WIDTH, INITIAL_GL_HEIGHT, environment.backend)
        if (context == null) {
            DesktopStartup.Failed(NO_GLES_MESSAGE)
        } else {
            DesktopStartup.Ready(memory, context)
        }
    }
} catch (failure: Throwable) {
    val detail = failure.message ?: failure::class.simpleName ?: "unknown failure"
    DesktopStartup.Failed("Desktop startup failed: $detail\n\n$NO_GLES_MESSAGE")
}

@Composable
private fun androidx.compose.ui.window.ApplicationScope.DesktopApplication(
    startup: DesktopStartup,
    mode: DesktopLaunchMode,
) {
    val canvasSize = CanvasSize(CANVAS_EDGE, CANVAS_EDGE)
    val fatal = remember {
        mutableStateOf((startup as? DesktopStartup.Failed)?.message)
    }
    val frameState = remember { mutableStateOf<DesktopEngine.Frame?>(null) }
    val mixboxAttribution = remember {
        if (MixboxBinding.create() == null) {
            MixboxAttribution.Excluded
        } else {
            MixboxAttribution.Included
        }
    }
    var showAbout by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        val handler = DesktopAboutHandler.install {
            java.awt.EventQueue.invokeLater { showAbout = true }
        }
        onDispose { handler.close() }
    }

    val engine = remember(startup) {
        val ready = startup as? DesktopStartup.Ready ?: return@remember null
        DesktopEngine(
            canvas = canvasSize,
            memory = ready.memory,
            context = ready.context,
            onFrame = { frame ->
                java.awt.EventQueue.invokeLater { frameState.value = frame }
            },
            onFatal = { message ->
                java.awt.EventQueue.invokeLater { fatal.value = message }
            },
        )
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = DesktopBrand.displayName,
        icon = painterResource("bangnidraw.png"),
    ) {
        window.minimumSize = Dimension(WINDOW_MIN_W, WINDOW_MIN_H)

        MaterialTheme(colorScheme = darkColorScheme()) {
            val fatalMessage = fatal.value
            if (fatalMessage != null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(fatalMessage, style = MaterialTheme.typography.bodyLarge)
                }
            } else if (engine != null) {
                Shell(
                    engine = engine,
                    frame = frameState.value,
                    canvasSize = canvasSize,
                    mixboxAttribution = mixboxAttribution,
                    onAbout = { showAbout = true },
                )
            }

            if (showAbout) {
                AlertDialog(
                    onDismissRequest = { showAbout = false },
                    title = { Text("About " + DesktopBrand.displayName) },
                    text = { Text(DesktopAbout.body(mixboxAttribution)) },
                    confirmButton = {
                        Button(onClick = { showAbout = false }) { Text("Close") }
                    },
                )
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(engine) {
        engine?.start()
        onDispose { engine?.stopAndJoin() }
    }

    LaunchedEffect(frameState.value, mode) {
        val frame = frameState.value ?: return@LaunchedEffect
        if (mode != DesktopLaunchMode.SmokeWindow) return@LaunchedEffect

        check(frame.pixels.any { it != 0.toByte() }) {
            "desktop smoke frame contains no rendered pixels"
        }
        println("${DesktopBrand.displayName} window OK: ${frame.width}x${frame.height}")
        exitApplication()
    }

    LaunchedEffect(fatal.value, mode) {
        val message = fatal.value ?: return@LaunchedEffect
        if (mode != DesktopLaunchMode.SmokeStartupFailure) return@LaunchedEffect

        // Wait for a Compose frame: this proves startup produced a window,
        // not only the process-level macOS menu that hid the original crash.
        androidx.compose.runtime.withFrameNanos { }
        println("${DesktopBrand.displayName} error window OK: ${message.lineSequence().first()}")
        exitApplication()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(
    engine: DesktopEngine,
    frame: DesktopEngine.Frame?,
    canvasSize: CanvasSize,
    mixboxAttribution: MixboxAttribution,
    onAbout: () -> Unit,
) {
    val brushes = remember { DesktopBrushes.loadAll() }
    val prefs = remember { DesktopPrefs() }
    val restoreGate = remember { DesktopPreferenceRestoreGate() }
    androidx.compose.runtime.DisposableEffect(prefs) {
        onDispose { prefs.close() }
    }
    var selectedBrush by remember { mutableStateOf(brushes.first { it.id == BrushPresets.INK_PEN_ID }) }
    var brushSize by remember { mutableStateOf(selectedBrush.size) }
    var colorSelection by remember {
        mutableStateOf(HsvSelection.fromArgb(DesktopPalette.SWATCHES.first()))
    }
    val colorArgb = colorSelection.argb
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var preferencesReady by remember { mutableStateOf(false) }

    // Input stays disabled until the initial choices are resolved.
    LaunchedEffect(Unit) {
        try {
            val brushId = prefs.readBrushId()
            if (restoreGate.allows(DesktopPreferenceKind.Brush)) {
                brushId?.let { id ->
                    brushes.firstOrNull { it.id == id }?.let {
                        selectedBrush = it
                        brushSize = it.size
                    }
                }
            }

            val color = prefs.readColorArgb()
            if (restoreGate.allows(DesktopPreferenceKind.Color)) {
                color?.let { colorSelection = colorSelection.sync(it) }
            }
        } catch (failure: java.io.IOException) {
            System.err.println("desktop preferences could not be read: ${failure.message}")
        } finally {
            preferencesReady = true
        }
    }

    val mixer = remember { MixboxBinding.create() ?: RgbMixer }
    val bitmap = remember(frame) { frame?.toImageBitmap() }
    val handler = remember(engine) { DesktopShell.handler(engine) }

    Column(Modifier.fillMaxSize()) {
        Toolbar(
            brushSize = brushSize,
            brushSizeRange = DesktopBrushUi.sizeRange(selectedBrush),
            onBrushSize = {
                restoreGate.markChanged(DesktopPreferenceKind.Brush)
                brushSize = it
            },
            onSave = {
                engine.savePng { result ->
                    val message = when (result) {
                        is DesktopSaveResult.Saved -> result.path
                        is DesktopSaveResult.Failed -> "Save failed: ${result.message}"
                    }
                    java.awt.EventQueue.invokeLater { savedMessage = message }
                }
            },
            onUndo = { engine.undo() },
            onRedo = { engine.redo() },
            canUndo = engine.canUndo(),
            canRedo = engine.canRedo(),
            savedMessage = savedMessage,
        )

        Row(Modifier.weight(1f)) {
            // The canvas viewport: pointer px are viewport px, one to one.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { size ->
                        engine.setViewportSize(size.width, size.height)
                        handler.setViewport(canvasSize, size.width, size.height)
                    }
                    .background(VIEWPORT_VOID),
            ) {
                val canvasInput = if (preferencesReady) {
                    Modifier.pointerInput(selectedBrush, brushSize, colorArgb, mixer) {
                        awaitPointerEvents(handler, selectedBrush, brushSize, colorArgb, mixer)
                    }
                } else {
                    Modifier
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "canvas",
                        modifier = Modifier.fillMaxSize().then(canvasInput),
                    )
                }
            }

            SidePanel(
                brushes = brushes,
                selectedBrush = selectedBrush,
                onSelectBrush = {
                    restoreGate.markChanged(DesktopPreferenceKind.Brush)
                    selectedBrush = it
                    brushSize = it.size
                    prefs.writeBrush(it)
                },
                onColor = { argb ->
                    restoreGate.markChanged(DesktopPreferenceKind.Color)
                    colorSelection = colorSelection.commit(argb)
                    prefs.writeColor(argb)
                },
                colorSelection = colorSelection,
                onColorSelection = { selection ->
                    restoreGate.markChanged(DesktopPreferenceKind.Color)
                    colorSelection = selection
                    prefs.writeColor(selection.argb)
                },
                mixboxAttribution = mixboxAttribution,
                onAbout = onAbout,
            )
        }
    }
}

@Composable
private fun Toolbar(
    brushSize: Float,
    brushSizeRange: ClosedFloatingPointRange<Float>,
    onBrushSize: (Float) -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    savedMessage: String?,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(DesktopBrand.displayName, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onUndo, enabled = canUndo) { Text("Undo") }
        Button(onClick = onRedo, enabled = canRedo) { Text("Redo") }
        Button(onClick = onSave) { Text("Save PNG") }
        Text("size ${brushSize.toInt()}", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = brushSize,
            onValueChange = onBrushSize,
            valueRange = brushSizeRange,
            modifier = Modifier.width(150.dp),
        )
        if (savedMessage != null) {
            Text(
                savedMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SidePanel(
    brushes: List<BrushPreset>,
    selectedBrush: BrushPreset,
    onSelectBrush: (BrushPreset) -> Unit,
    onColor: (Int) -> Unit,
    colorSelection: HsvSelection,
    onColorSelection: (HsvSelection) -> Unit,
    mixboxAttribution: MixboxAttribution,
    onAbout: () -> Unit,
) {
    Column(
        Modifier.width(230.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Brush", style = MaterialTheme.typography.titleSmall)
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (brush in brushes) {
                val label = DesktopBrushUi.label(brush)
                Button(
                    onClick = { onSelectBrush(brush) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (brush.id == selectedBrush.id) "▸ " + label else label,
                        maxLines = 1,
                    )
                }
            }
        }

        Text("Color", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(DesktopPalette.SWATCHES) { swatch ->
                Box(
                    Modifier
                        .size(26.dp)
                        .background(Color(swatch))
                        .border(1.dp, Color.White.copy(alpha = 0.4f))
                        .pointerInput(swatch) {
                            awaitPointerEventsSwatch(swatch, onColor)
                        },
                )
            }
        }

        HsvSliders(colorSelection, onColorSelection)

        Text(
            "Strokes save to\n${DesktopPlatform.picturesDir()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (mixboxAttribution == MixboxAttribution.Included) {
            Text(
                "Pigment mixing: Mixbox © Secret Weapons,\nCC BY-NC 4.0 — non-commercial.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onAbout) { Text("About") }
    }
}

@Composable
private fun HsvSliders(
    selection: HsvSelection,
    onSelection: (HsvSelection) -> Unit,
) {
    val hsv = selection.hsv
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = hsv.h,
            onValueChange = { h -> onSelection(selection.commit(HsvColor(h, hsv.s, hsv.v))) },
            valueRange = 0f..HUE_MAX_DEGREES,
        )
        Slider(
            value = hsv.s,
            onValueChange = { s -> onSelection(selection.commit(HsvColor(hsv.h, s, hsv.v))) },
            valueRange = 0f..1f,
        )
        Slider(
            value = hsv.v,
            onValueChange = { v -> onSelection(selection.commit(HsvColor(hsv.h, hsv.s, v))) },
            valueRange = 0f..1f,
        )
    }
}

// ------------------------------------------------------------- input glue

/**
 * Mouse → [PointerSample] records (M3). Left button draws, right button
 * erases (an eraser-end analog); motion without buttons is hover. The
 * pressure is constant — synthetic pressure is the documented v1 posture
 * (DESKTOP.md "Input").
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitPointerEvents(
    handler: CanvasTouchHandler,
    preset: BrushPreset,
    size: Float,
    colorArgb: Int,
    mixer: ch.lkmc.bangnidraw.engine.core.ColorMixer,
) {
    val sample = PointerSample()
    DesktopShell.tool = DesktopShell.Tool(preset.copy(size = size), colorArgb, mixer)

    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                val position = change.position
                val timeNs = System.nanoTime()

                when (event.type) {
                    PointerEventType.Press -> {
                        val secondary = event.button == PointerButton.Secondary
                        DesktopShell.mouseMode = if (secondary) DesktopMouseMode.Erase else DesktopMouseMode.Draw
                        handler.onPointerDown(
                            sample.set(
                                MOUSE_POINTER_ID, PointerTool.MOUSE,
                                position.x, position.y, SYNTHETIC_PRESSURE, 0f, 0f, timeNs,
                            ),
                        )
                    }

                    PointerEventType.Move -> {
                        if (change.pressed) {
                            handler.onPointerMove(
                                sample.set(
                                    MOUSE_POINTER_ID, PointerTool.MOUSE,
                                    position.x, position.y, SYNTHETIC_PRESSURE, 0f, 0f, timeNs,
                                ),
                            )
                            handler.onPointerMoveEnd(timeNs)
                        } else {
                            handler.onHoverMove(
                                sample.setHover(
                                    MOUSE_POINTER_ID, PointerTool.MOUSE,
                                    position.x, position.y, 0f, timeNs,
                                ),
                            )
                        }
                    }

                    PointerEventType.Scroll -> {
                        handler.onScroll(
                            position.x,
                            position.y,
                            -change.scrollDelta.y / SCROLL_PIXELS_PER_TICK,
                            pointerClass = true,
                        )
                    }

                    PointerEventType.Release -> {
                        handler.onPointerUp(
                            sample.set(
                                MOUSE_POINTER_ID, PointerTool.MOUSE,
                                position.x, position.y, SYNTHETIC_PRESSURE, 0f, 0f, timeNs,
                            ),
                        )
                        DesktopShell.mouseMode = DesktopMouseMode.Draw
                    }
                }
            }
        }
    } finally {
        handler.onPointerCancel(System.nanoTime())
        DesktopShell.mouseMode = DesktopMouseMode.Draw
    }
}
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitPointerEventsSwatch(
    swatch: Int,
    onColor: (Int) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press) onColor(swatch)
        }
    }
}

// ------------------------------------------------------------ shell state

/** The tool state the [DesktopInputHost] reads; v1 shell simplicity. */
private object DesktopShell {
    data class Tool(
        val preset: BrushPreset,
        val colorArgb: Int,
        val mixer: ch.lkmc.bangnidraw.engine.core.ColorMixer,
    )

    var tool: Tool? = null
    var mouseMode = DesktopMouseMode.Draw

    fun handler(engine: DesktopEngine): CanvasTouchHandler {
        lateinit var handler: CanvasTouchHandler
        handler = CanvasTouchHandler(density = 1f, host = DesktopInputHost(engine) { handler })
        handler.frameScheduler = SwingFrameScheduler
        handler.attachDeadlineScheduler(SwingGestureDeadlineScheduler())
        return handler
    }
}

/** The stroke plumbing the Android CanvasScreen carries, minus its stylus-only features. */
private class DesktopInputHost(
    private val engine: DesktopEngine,
    // Late-bound: the handler the host reports view scale from is the one
    // being constructed with this host.
    private val handler: () -> CanvasTouchHandler,
) : CanvasInputHost {
    private var driver: StrokeDriver? = null
    private var source = StrokeSource.MOUSE

    override fun onViewChanged(view: ViewTransform) {
        engine.setView(view)
    }

    override fun onUndoRequested() = engine.undo()
    override fun onRedoRequested() = engine.redo()
    override fun onRotationSnapped() = Unit
    override fun onColorPick(x: Float, y: Float) = Unit

    override fun onStrokeBegin(pointerId: Int, source: StrokeSource) {
        val tool = DesktopShell.tool ?: return
        val active = engine.stack.layers.getOrNull(engine.stack.activeIndex) ?: return

        driver?.cancel()
        if (driver != null) engine.cancelStroke()
        driver = null

        val resolvedSource = DesktopStrokePolicy.source(source, DesktopShell.mouseMode)
        this.source = resolvedSource
        val mode = DesktopStrokePolicy.mode(resolvedSource, tool.preset, tool.mixer)
        val rmw = if (resolvedSource == StrokeSource.ERASER_END) {
            null
        } else {
            RmwStrokePolicy.spec(ToolKind.Brush(tool.preset), tool.mixer)
        }
        val spec = StrokeSpec(
            layerId = active.id,
            mode = mode,
            opacity = tool.preset.opacity,
            alphaLock = active.props.alphaLock,
            dilution = if (mode == StrokeMode.MIX) tool.preset.dilution else 0f,
            grainMode = tool.preset.grainMode,
            brushModel = tool.preset.model,
            rmw = rmw,
        )
        driver = StrokeDriver(
            tool.preset,
            seed = System.nanoTime(),
            zoom = handler().canvasToScreenScale,
            spacingPolicy = if (rmw == null) {
                DabSpacingPolicy.Brush
            } else {
                DabSpacingPolicy.ReadModifyWrite
            },
        )

        val rgb = DesktopPalette.toStrokeRgb(tool.colorArgb)
        engine.beginStroke(spec, tool.preset.bufferMode, rgb[0], rgb[1], rgb[2])
    }

    override fun onStrokeSample(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
    ) {
        val driver = driver ?: return
        var batch = engine.acquireDabBatch() ?: return
        var emitted = if (driver.isActive) {
            driver.sample(x, y, pressure, tilt, orientation, timeNs, source, batch)
        } else {
            driver.begin(x, y, pressure, tilt, orientation, timeNs, source, batch)
        }

        while (true) {
            if (emitted == 0) {
                engine.releaseDabBatch(batch)
            } else {
                engine.stampDabs(batch)
            }
            if (!driver.hasPendingDabs) return

            batch = engine.acquireDabBatch() ?: return
            emitted = driver.resumeDabs(batch)
        }
    }

    override fun onStrokeEnd(pointerId: Int) {
        val driver = driver ?: return
        this.driver = null

        while (driver.isActive) {
            val batch = engine.acquireDabBatch() ?: run {
                driver.cancel()
                // The engine-side stroke began at pen-down; leaving it open
                // would wedge every later beginStroke (§4's pairing).
                engine.cancelStroke()
                return
            }
            val emitted = driver.end(batch)
            if (emitted == 0) engine.releaseDabBatch(batch) else engine.stampDabs(batch)
        }
        engine.endStroke(driver.opacityCeiling) {}
    }

    override fun onStrokeCancel() {
        driver?.cancel()
        driver = null
        engine.cancelStroke()
    }
}

/** Swing's invokeLater drives hover coalescing onto the UI thread. */
private object SwingFrameScheduler : FrameScheduler {
    override fun post(callback: Runnable) {
        javax.swing.SwingUtilities.invokeLater(callback)
    }

    override fun cancel(callback: Runnable) {
        // invokeLater cannot be undone; the handler's posted flags make a
        // straggling hover notification after teardown harmless (it fires
        // once against a dead host and nothing observes it).
    }
}

// --------------------------------------------------------------- helpers

private fun DesktopEngine.Frame.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val image = org.jetbrains.skia.Image.makeRaster(
        imageInfo = org.jetbrains.skia.ImageInfo(
            width, height,
            org.jetbrains.skia.ColorType.RGBA_8888,
            org.jetbrains.skia.ColorAlphaType.PREMUL,
        ),
        bytes = pixels,
        rowBytes = width * RGBA_BYTES,
    )
    return image.toComposeImageBitmap()
}

private val VIEWPORT_VOID = Color(0xFF2A2A2E)
private const val RGBA_BYTES = 4
private const val MOUSE_POINTER_ID = 0
private const val SYNTHETIC_PRESSURE = 1f
private const val SCROLL_PIXELS_PER_TICK = 40f
private const val HUE_MAX_DEGREES = 360f
private const val CANVAS_EDGE = 2048
private const val VERIFY_RUNTIME_FLAG = "--verify-runtime"
private const val SMOKE_STARTUP_FAILURE_FLAG = "--smoke-startup-failure"
private const val SMOKE_WINDOW_FLAG = "--smoke-window"
private const val INITIAL_GL_WIDTH = 1
private const val INITIAL_GL_HEIGHT = 1
private val NO_GLES_MESSAGE = """
OpenGL ES 3.0 is unavailable.

Linux: install Mesa libEGL and libGLESv2, or your GPU vendor driver.
macOS: provide libEGL.dylib and libGLESv2.dylib with
-Dbangnidraw.angle.dir=/path/to/angle, or place both in the app resources.
""".trimIndent()
private const val WINDOW_MIN_W = 960
private const val WINDOW_MIN_H = 600
