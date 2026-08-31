@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ch.lkmc.bangnidraw.desktop
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ch.lkmc.bangnidraw.engine.core.BrushMixingPolicy
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.CanvasPresetId
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DabSpacingPolicy
import ch.lkmc.bangnidraw.engine.core.HsvColor
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.RgbMixer
import ch.lkmc.bangnidraw.engine.core.StrokeDriver
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
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
fun main() = application {
    val canvasSize = CanvasSize(CANVAS_EDGE, CANVAS_EDGE)
    val fatal = mutableStateOf<String?>(null)
    val frameState = mutableStateOf<DesktopEngine.Frame?>(null)

    val engine = remember {
        DesktopEngine(
            canvas = canvasSize,
            memory = DesktopPlatform.deviceMemory(),
            onFrame = { frameState.value = it },
            onFatal = { fatal.value = it },
        )
    }

    Window(onCloseRequest = ::exitApplication, title = WINDOW_TITLE) {
        window.minimumSize = Dimension(WINDOW_MIN_W, WINDOW_MIN_H)

        val fatalMessage = fatal.value
        if (fatalMessage != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(fatalMessage, style = MaterialTheme.typography.bodyLarge)
            }
            return@Window
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Shell(engine, frameState.value, canvasSize)
        }
    }

    // Start and stop with the composition: a daemon GL thread still owns a
    // GLFW context and GL objects that must not outlive the window.
    androidx.compose.runtime.DisposableEffect(Unit) {
        engine.start()
        onDispose { engine.stop() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(engine: DesktopEngine, frame: DesktopEngine.Frame?, canvasSize: CanvasSize) {
    val brushes = remember { DesktopBrushes.loadAll() }
    val prefs = remember { DesktopPrefs() }
    var selectedBrush by remember { mutableStateOf(brushes.first { it.id == BrushPresets.INK_PEN_ID }) }
    var brushSize by remember { mutableStateOf(selectedBrush.size) }
    var colorArgb by remember { mutableStateOf(DesktopPalette.SWATCHES.first()) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    // Restore the persisted choice once, before the first stroke.
    LaunchedEffect(Unit) {
        prefs.readBrushId()?.let { id -> brushes.firstOrNull { it.id == id }?.let { selectedBrush = it } }
        prefs.readColorArgb()?.let { colorArgb = it }
    }

    val mixer = remember { MixboxBinding.create() ?: RgbMixer }
    val bitmap = remember(frame) { frame?.toImageBitmap() }
    val handler = remember(engine) { DesktopShell.handler(engine) }

    Column(Modifier.fillMaxSize()) {
        Toolbar(
            brushSize = brushSize,
            onBrushSize = { brushSize = it },
            onSave = {
                engine.savePng { path -> savedMessage = path }
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
                    .onSizeChanged { size ->
                        engine.setViewportSize(size.width, size.height)
                        handler.setViewport(canvasSize, size.width, size.height)
                    }
                    .background(VIEWPORT_VOID),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "canvas",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(selectedBrush, brushSize, colorArgb, mixer) {
                                awaitPointerEvents(handler, selectedBrush, brushSize, colorArgb, mixer)
                            },
                    )
                }
            }

            SidePanel(
                brushes = brushes,
                selectedBrush = selectedBrush,
                onSelectBrush = {
                    selectedBrush = it
                    brushSize = it.size
                    prefs.writeBrush(it)
                },
                colorArgb = colorArgb,
                onColor = {
                    colorArgb = it
                    prefs.writeColor(it)
                },
            )
        }
    }
}

@Composable
private fun Toolbar(
    brushSize: Float,
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
        Text(WINDOW_TITLE, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onUndo, enabled = canUndo) { Text("Undo") }
        Button(onClick = onRedo, enabled = canRedo) { Text("Redo") }
        Button(onClick = onSave) { Text("Save PNG") }
        Text("size ${brushSize.toInt()}", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = brushSize,
            onValueChange = onBrushSize,
            valueRange = 1f..120f,
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
    colorArgb: Int,
    onColor: (Int) -> Unit,
) {
    Column(
        Modifier.width(230.dp).fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Brush", style = MaterialTheme.typography.titleSmall)
        Column(
            Modifier.height(240.dp).horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (column in brushes.chunked(BRUSH_ROWS)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (brush in column) {
                            Button(onClick = { onSelectBrush(brush) }) {
                                Text(
                                    if (brush.id == selectedBrush.id) "▸ ${brush.name}" else brush.name,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
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

        HsvSliders(colorArgb, onColor)

        Text(
            "Strokes save to\n${DesktopPlatform.picturesDir()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Pigment mixing: Mixbox © Secret Weapons,\nCC BY-NC 4.0 — non-commercial.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HsvSliders(colorArgb: Int, onColor: (Int) -> Unit) {
    val hsv = remember(colorArgb) { HsvColor.fromArgb(colorArgb) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = hsv.h, onValueChange = { h -> onColor(HsvColor(h, hsv.s, hsv.v).toArgb()) },
            valueRange = 0f..1f,
        )
        Slider(
            value = hsv.s, onValueChange = { s -> onColor(HsvColor(hsv.h, s, hsv.v).toArgb()) },
            valueRange = 0f..1f,
        )
        Slider(
            value = hsv.v, onValueChange = { v -> onColor(HsvColor(hsv.h, hsv.s, v).toArgb()) },
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

    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            val position = change.position
            val timeNs = System.nanoTime()

            when (event.type) {
                PointerEventType.Press -> {
                    // This version exposes no PointerButtons.isPressed helpers;
                    // the event's own button id decides.
                    val secondary = event.button == PointerButton.Secondary
                    DesktopShell.erasing = secondary
                    val tool = if (secondary) PointerTool.ERASER else PointerTool.MOUSE
                    handler.onPointerDown(
                        sample.set(
                            MOUSE_POINTER_ID, tool,
                            position.x, position.y, SYNTHETIC_PRESSURE, 0f, 0f, timeNs,
                        ),
                    )
                }

                PointerEventType.Move -> {
                    if (change.pressed) {
                        val tool = if (DesktopShell.erasing) PointerTool.ERASER else PointerTool.MOUSE
                        handler.onPointerMove(
                            sample.set(
                                MOUSE_POINTER_ID, tool,
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

                PointerEventType.Release -> {
                    handler.onPointerUp(
                        sample.set(
                            MOUSE_POINTER_ID, PointerTool.MOUSE,
                            position.x, position.y, SYNTHETIC_PRESSURE, 0f, 0f, timeNs,
                        ),
                    )
                    DesktopShell.erasing = false
                }
            }
        }
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
object DesktopShell {
    data class Tool(
        val preset: BrushPreset,
        val colorArgb: Int,
        val mixer: ch.lkmc.bangnidraw.engine.core.ColorMixer,
    )

    var tool: Tool? = null
    var erasing = false

    fun handler(engine: DesktopEngine): CanvasTouchHandler {
        lateinit var handler: CanvasTouchHandler
        handler = CanvasTouchHandler(density = 1f, host = DesktopInputHost(engine) { handler })
        handler.frameScheduler = SwingFrameScheduler
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

        this.source = source
        val spec = StrokeSpec(
            layerId = active.id,
            mode = BrushMixingPolicy.mode(tool.preset, tool.mixer),
            opacity = tool.preset.opacity,
            alphaLock = active.props.alphaLock,
            dilution = tool.preset.dilution,
            grainMode = tool.preset.grainMode,
            brushModel = tool.preset.model,
            rmw = null,
        )
        driver = StrokeDriver(
            tool.preset,
            seed = System.nanoTime(),
            // The live canvas→screen scale, exactly as the Android host
            // passes `handler.canvasToScreenScale`: dab spacing follows the
            // zoom the user actually sees.
            zoom = handler().canvasToScreenScale,
            spacingPolicy = DabSpacingPolicy.Brush,
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
private const val CANVAS_EDGE = 2048
private const val BRUSH_ROWS = 8
private const val WINDOW_TITLE = "BangniDraw Desktop"
private const val WINDOW_MIN_W = 960
private const val WINDOW_MIN_H = 600
