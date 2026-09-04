@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DabSpacingPolicy
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HsvSelection
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.PaintSlotAssignments
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.EyedropperSampleGate
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.RmwDabPreset
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.RgbMixer
import ch.lkmc.bangnidraw.engine.core.RmwStrokePolicy
import ch.lkmc.bangnidraw.engine.core.StrokeDriver
import ch.lkmc.bangnidraw.engine.core.StrokeLayerDecision
import ch.lkmc.bangnidraw.engine.core.StrokeLayerPolicy
import ch.lkmc.bangnidraw.engine.core.StrokeMode
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.mixbox.MixboxBinding
import ch.lkmc.bangnidraw.ui.shared.BangniTypography
import ch.lkmc.bangnidraw.input.CanvasInputHost
import ch.lkmc.bangnidraw.input.CanvasTouchHandler
import ch.lkmc.bangnidraw.input.FrameScheduler
import ch.lkmc.bangnidraw.input.PointerSample
import java.awt.Dimension
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * The desktop shell (DESKTOP.md Phase 2, M4). It wears the Android
 * canvas chrome — a top strip over a full-bleed canvas with the tool rail
 * floating in the hand's corner (`docs/plan/08-ui-and-layout.md` §3) —
 * built from the shared [LayoutSpec] rather than a desktop layout of its
 * own. String literals are English v1: the full string migration is a
 * later, separate effort.
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
        val context: DesktopEsContext,
    ) : DesktopStartup

    data class Failed(val message: String) : DesktopStartup
}

private enum class DesktopLaunchMode {
    Interactive,
    VerifyRuntime,
    GlReport,
    SmokeStartupFailure,
    SmokeWindow;

    companion object {
        fun from(args: Array<String>): DesktopLaunchMode = when {
            VERIFY_RUNTIME_FLAG in args -> VerifyRuntime
            GL_REPORT_FLAG in args -> GlReport
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
    if (mode == DesktopLaunchMode.GlReport) {
        printGlReport()
        return
    }

    val startup = createDesktopStartup()
    try {
        application { DesktopApplication(startup, mode) }
    } finally {
        (startup as? DesktopStartup.Ready)?.context?.destroy()
    }

    // A smoke run has printed its verdict and torn the context down, but AWT's
    // threads are not daemons and the JVM lingers here about one run in three.
    // CI reads that output through a pipe, so a lingering process wedges the
    // step rather than ending it. The interactive path is deliberately left to
    // exit on its own: it may still be flushing preferences.
    if (mode != DesktopLaunchMode.Interactive) exitProcess(0)
}

private fun verifyPackagedRuntime() {
    val memory = DesktopPlatform.deviceMemory()
    println("${DesktopBrand.displayName} runtime OK: ${memory.totalMemBytes} bytes")
}

/**
 * Prints what the GL stack did, and exits. A user who double-clicks the app
 * never sees stdout, so the failure window points at this flag: one run, one
 * block of text to paste into a bug report.
 */
private fun printGlReport() {
    val report = DesktopGlReport()
    // A missing native library throws out of startup rather than returning a
    // report — and that is one of the environments this flag exists to explain,
    // so it must not be the one where nothing is printed.
    val startup = try {
        DesktopGlStartup.start(INITIAL_GL_WIDTH, INITIAL_GL_HEIGHT)
    } catch (failure: Throwable) {
        report.fail("startup", describe(failure))
        report.note(failure.stackTraceToString())
        DesktopGlStartup.Result(null, report)
    }

    val context = startup.context
    val host = startup.report.path
    // Creating a context is not the same as being able to make it current, and
    // a report that stopped at creation would call a broken driver a success.
    var usable = context != null
    if (context != null) {
        runCatching {
            context.activate()
            context.deactivate()
        }.onFailure {
            usable = false
            startup.report.fail(host.orEmpty(), describe(it))
        }
        // Destroying can refuse too; losing the report to it would waste the run.
        runCatching { context.destroy() }
            .onFailure { startup.report.fail(host.orEmpty(), "destroy: ${describe(it)}") }
    }

    println("${DesktopBrand.displayName} GL report")
    println(startup.report.text())
    println(glReportSummary(host, created = context != null, usable = usable))
    System.out.flush()

    // Startup has already initialized AWT, whose threads are not daemons, so
    // returning from main would leave the process alive with nothing to do —
    // and a caller reading its output through a pipe waiting forever.
    exitProcess(if (usable) 0 else 1)
}

/** The one line a reader sees first, so it must not call a broken driver absent. */
internal fun glReportSummary(host: String?, created: Boolean, usable: Boolean): String = when {
    usable -> "GLES 3.0 context via $host"
    created -> "GLES 3.0 context via $host could not be made current"
    else -> "no GLES 3.0 context"
}

private fun createDesktopStartup(): DesktopStartup = try {
    val memory = DesktopPlatform.deviceMemory()
    val startup = DesktopGlStartup.start(INITIAL_GL_WIDTH, INITIAL_GL_HEIGHT)
    val context = startup.context
    if (context == null) {
        DesktopStartup.Failed(DesktopGlDiagnostics.failure(startup.report))
    } else {
        DesktopStartup.Ready(memory, context)
    }
} catch (failure: Throwable) {
    DesktopStartup.Failed(
        "Desktop startup failed: ${describe(failure)}\n\n${DesktopGlDiagnostics.unavailable()}",
    )
}

private fun describe(failure: Throwable): String =
    failure.message ?: failure::class.simpleName ?: "unknown failure"

@Composable
private fun androidx.compose.ui.window.ApplicationScope.DesktopApplication(
    startup: DesktopStartup,
    mode: DesktopLaunchMode,
) {
    val fatal = remember { mutableStateOf((startup as? DesktopStartup.Failed)?.message) }
    val mixer = remember { MixboxBinding.create() ?: RgbMixer }
    val mixboxAttribution = if (mixer === RgbMixer) {
        MixboxAttribution.Excluded
    } else MixboxAttribution.Included
    var showAbout by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        val handler = DesktopAboutHandler.install {
            java.awt.EventQueue.invokeLater { showAbout = true }
        }
        onDispose { handler.close() }
    }

    val catalogue = remember { DesktopBrushes.loadAll() }
    val prefs = remember { DesktopPrefs() }
    val host = remember(startup) {
        val ready = startup as? DesktopStartup.Ready ?: return@remember null
        DesktopGlHost(ready.context) { message ->
            java.awt.EventQueue.invokeLater { fatal.value = message }
        }
    }
    val documents = remember(host) {
        val ready = startup as? DesktopStartup.Ready
        if (ready == null || host == null) {
            null
        } else {
            // The first painting is created here rather than in an effect:
            // the "last window closed" rule below reads the list during
            // composition, and an empty first frame would exit immediately.
            DesktopDocuments(ready.memory, host, catalogue, mixer, prefs).apply {
                create(CanvasSize(CANVAS_EDGE, CANVAS_EDGE))
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(documents) {
        onDispose {
            documents?.closeAll()
            host?.stopAndJoin()
            prefs.close()
        }
    }

    // A failed startup has no document to draw in; the report window is the
    // whole application.
    val fatalMessage = fatal.value
    if (documents == null || fatalMessage != null) {
        FailureWindow(fatalMessage ?: DesktopGlDiagnostics.unavailable())
    } else {
        for (document in documents.open) {
            key(document.id) {
                DocumentWindow(
                    document = document,
                    documents = documents,
                    onAbout = { showAbout = true },
                )
            }
        }
        // The last window closing ends the application, as it does in every
        // other document-based app on these platforms.
        LaunchedEffect(documents.open.size) {
            if (documents.open.isEmpty()) exitApplication()
        }
    }

    if (showAbout) {
        AboutWindow(mixboxAttribution) { showAbout = false }
    }

    LaunchedEffect(documents?.open?.firstOrNull()?.frame?.value, mode) {
        val frame = documents?.open?.firstOrNull()?.frame?.value ?: return@LaunchedEffect
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

/** One painting, in a window of its own. */
@Composable
private fun androidx.compose.ui.window.ApplicationScope.DocumentWindow(
    document: DesktopDocument,
    documents: DesktopDocuments,
    onAbout: () -> Unit,
) {
    val state = document.state
    var openError by remember { mutableStateOf<String?>(null) }

    Window(
        onCloseRequest = { requestClose(document, documents) },
        title = document.title,
        icon = painterResource("bangnidraw.png"),
    ) {
        window.minimumSize = Dimension(WINDOW_MIN_W, WINDOW_MIN_H)

        val save: (java.io.File?) -> Unit = { target ->
            val file = target ?: document.file
            if (file == null) {
                saveAs(window, document)
            } else {
                writeTo(document, file)
            }
        }

        DesktopMenuBar(
            document = document,
            onNew = { documents.create(CanvasSize(CANVAS_EDGE, CANVAS_EDGE)) },
            onOpen = {
                DesktopFileDialogs.open(window)?.let { file ->
                    openError = documents.openFile(file)
                }
            },
            onSave = { save(null) },
            onSaveAs = { saveAs(window, document) },
            onClose = { requestClose(document, documents) },
            onAbout = onAbout,
            onHelp = { state.showHelp = true },
        )

        MaterialTheme(colorScheme = DesktopTheme.colorScheme, typography = BangniTypography) {
            // Surface actually paints [colorScheme.background]; without it
            // the OS window chrome shows through and the Material widgets
            // sit on whatever the host desktop happens to be (macOS's own
            // white, GNOME's own grey), turning the SAFFRON palette
            // incoherent. Same wrap the Android CanvasScreen relies on.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Shell(
                        state = state,
                        frame = document.frame.value,
                        canvasSize = document.canvas,
                        onSave = { save(null) },
                        onAbout = onAbout,
                    )

                    if (document.confirmingClose) {
                        UnsavedChangesDialog(
                            name = document.file?.name ?: "this painting",
                            onSave = {
                                document.confirmingClose = false
                                val file = document.file
                                if (file == null) saveAs(window, document) else writeTo(document, file)
                                if (!document.dirty) documents.close(document)
                            },
                            onDiscard = {
                                document.confirmingClose = false
                                documents.close(document)
                            },
                            onCancel = { document.confirmingClose = false },
                        )
                    }

                    val failedOpen = openError
                    if (failedOpen != null) {
                        AlertDialog(
                            onDismissRequest = { openError = null },
                            title = { Text("Could not open that file") },
                            text = { Text(failedOpen) },
                            confirmButton = {
                                Button(onClick = { openError = null }) { Text("Close") }
                            },
                        )
                    }
                }
            }
        }
    }

    DesktopPanelWindows(state, document.file?.name ?: UNTITLED_NAME)
}

/** The window a failed GL startup gets instead of a canvas. */
@Composable
private fun androidx.compose.ui.window.ApplicationScope.FailureWindow(message: String) {
    Window(
        onCloseRequest = ::exitApplication,
        title = DesktopBrand.displayName,
        icon = painterResource("bangnidraw.png"),
    ) {
        MaterialTheme(colorScheme = DesktopTheme.colorScheme, typography = BangniTypography) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                // The startup report is long on purpose; a fixed-height window
                // must not swallow the half that names the failure.
                Box(
                    Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    // Selectable: this text is what a user pastes into a report.
                    SelectionContainer {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.ui.window.ApplicationScope.AboutWindow(
    attribution: MixboxAttribution,
    onClose: () -> Unit,
) {
    Window(
        onCloseRequest = onClose,
        title = "About " + DesktopBrand.displayName,
        icon = painterResource("bangnidraw.png"),
        alwaysOnTop = true,
    ) {
        MaterialTheme(colorScheme = DesktopTheme.colorScheme, typography = BangniTypography) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(DesktopAbout.body(attribution)) }
                }
            }
        }
    }
}

@Composable
private fun UnsavedChangesDialog(
    name: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save changes to $name?") },
        text = { Text("Closing without saving loses everything painted since the last save.") },
        confirmButton = { Button(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = onDiscard) {
                    Text("Don't save")
                }
                androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

/** Closing asks first when the painting has unsaved work. */
private fun requestClose(document: DesktopDocument, documents: DesktopDocuments) {
    if (document.dirty) {
        document.confirmingClose = true
        return
    }
    documents.close(document)
}

private fun saveAs(parent: java.awt.Frame?, document: DesktopDocument) {
    val suggested = document.file?.name
        ?: (DesktopBrand.exportFileStem(DesktopBrand.displayName) + "." + DesktopImageIo.EXTENSION)
    val target = DesktopFileDialogs.save(parent, suggested) ?: return
    writeTo(document, target)
}

private fun writeTo(document: DesktopDocument, file: java.io.File) {
    document.engine.savePng(file) { result ->
        java.awt.EventQueue.invokeLater {
            when (result) {
                is DesktopSaveResult.Saved -> {
                    document.file = file
                    document.dirty = false
                    document.state.savedMessage = result.path
                }
                is DesktopSaveResult.Failed ->
                    document.state.savedMessage = "Save failed: " + result.message
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(
    state: DesktopShellState,
    frame: DesktopEngine.Frame?,
    canvasSize: CanvasSize,
    onSave: () -> Unit,
    onAbout: () -> Unit,
) {
    val engine = state.engine
    val activeTool = state.activeTool
    val colorArgb = state.colorSelection.argb

    // The strip is not a log: clear the save path after a beat, as a snackbar
    // would. Keyed on the message so a second save restarts the countdown.
    LaunchedEffect(state.savedMessage) {
        if (state.savedMessage != null) {
            kotlinx.coroutines.delay(SAVED_MESSAGE_MS)
            state.savedMessage = null
        }
    }

    // Input stays disabled until the initial choices are resolved.
    LaunchedEffect(state) {
        try {
            val brushId = state.prefs.readBrushId()
            if (state.restoreGate.allows(DesktopPreferenceKind.Brush)) {
                brushId?.let { id ->
                    state.rail = DesktopRailPolicy.select(state.rail, id, state.presets)
                    // A restored paint must also occupy the active slot, or
                    // the rail would show it selected somewhere off screen.
                    if (state.rail.selectedId == id && state.rail.paintSelected()) {
                        state.paintSlots = state.paintSlots.assign(id)
                    }
                }
            }

            val color = state.prefs.readColorArgb()
            if (state.restoreGate.allows(DesktopPreferenceKind.Color)) {
                color?.let { state.colorSelection = state.colorSelection.sync(it) }
            }
        } catch (failure: java.io.IOException) {
            System.err.println("desktop preferences could not be read: ${failure.message}")
        } finally {
            state.preferencesReady = true
        }
    }

    val bitmap = remember(frame) { frame?.toImageBitmap() }
    // The real display density, not 1f: GestureArbiter converts TAP_SLOP_DP
    // to pixels once at construction, so a hardcoded 1f makes the tap slop
    // 8 px on a 2x display where it should be 16.
    val density = LocalDensity.current.density
    val handler = remember(engine, density) { DesktopShell.handler(engine, density) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value.roundToInt()
        val heightDp = maxHeight.value.roundToInt()
        val layout = remember(widthDp, heightDp) {
            DesktopChromeLayout.forWindow(widthDp, heightDp)
        }

        // The canvas is full-bleed and the chrome floats above it, the same
        // arrangement `:app`'s CanvasScreen uses: the paper stays centred in
        // the whole window instead of in a column beside a sidebar.
        // Pointer px are viewport px, one to one.
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    engine.setViewportSize(size.width, size.height)
                    handler.setViewport(canvasSize, size.width, size.height)
                }
                .background(DesktopTheme.viewportVoid),
        ) {
            val canvasInput = if (state.preferencesReady && activeTool != null) {
                Modifier.pointerInput(activeTool, colorArgb, state.mixer) {
                    awaitPointerEvents(handler, activeTool, colorArgb, state.mixer, state::pickSwatch)
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

        DesktopTopStrip(
            layout = layout,
            canUndo = engine.canUndo(),
            canRedo = engine.canRedo(),
            layerCount = state.stack.activeIndex + 1,
            layerPanelOpen = state.showLayerPanel,
            brushColor = colorArgb,
            colorPanelOpen = state.showColorPanel,
            savedMessage = state.savedMessage,
            onUndo = { engine.undo() },
            onRedo = { engine.redo() },
            onLayers = { state.showLayerPanel = !state.showLayerPanel },
            onColor = { state.showColorPanel = !state.showColorPanel },
            onSave = onSave,
            onAbout = onAbout,
            onHelp = { state.showHelp = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        DesktopToolRail(
            layout = layout,
            presets = state.presets,
            paintSlots = state.paintSlots,
            rail = state.rail,
            tool = activeTool,
            windowWidth = maxWidth,
            windowHeight = maxHeight,
            onPaintSlot = { index ->
                state.paintSlots = state.paintSlots.activate(index)
                state.selectPreset(state.paintSlots.activePresetId)
            },
            onAssignPaint = { preset ->
                state.paintSlots = state.paintSlots.assign(preset.id)
                state.selectPreset(preset.id)
            },
            onEraserTap = { state.eraserTap() },
            onSecondaryTool = state::selectSecondary,
            onSettings = { state.showBrushPanel = !state.showBrushPanel },
            onSizeChanged = state::tuneActiveSize,
            onSecondaryChanged = state::tuneActiveSecondary,
            modifier = Modifier.align(railAlignment(layout)),
        )

        if (activeTool != null) {
            DesktopSliderLedge(
                layout = layout,
                kind = activeTool,
                onSizeChanged = state::tuneActiveSize,
                onSecondaryChanged = state::tuneActiveSecondary,
                modifier = Modifier.ledgePlacement(this, layout),
            )
        }

        if (state.showColorPanel) {
            val insets = layout.panelInsets(widthDp, heightDp)
            DesktopColorPanel(
                selection = state.colorSelection,
                onSelection = state::pickColor,
                onSwatch = state::pickSwatch,
                onClose = { state.showColorPanel = false },
                modifier = Modifier
                    .align(
                        if (layout.panelSide == Hand.RIGHT) {
                            AbsoluteAlignment.CenterRight
                        } else {
                            AbsoluteAlignment.CenterLeft
                        },
                    )
                    .absolutePadding(
                        left = insets.leftDp.dp,
                        top = insets.topDp.dp,
                        right = insets.rightDp.dp,
                        bottom = insets.bottomDp.dp,
                    ),
            )
        }
    }

    if (state.showHelp) {
        AlertDialog(
            onDismissRequest = { state.showHelp = false },
            title = { Text("Canvas") },
            text = {
                // The body is longer than a dialog on a 480 dp window; it
                // also names the export directory, which is a path someone
                // will want to copy rather than retype.
                SelectionContainer {
                    Text(
                        DesktopHelp.canvasBody(),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                Button(onClick = { state.showHelp = false }) { Text("Close") }
            },
        )
    }
}

/** The rail hugs the hand's bottom corner, or the window's foot when docked. */
private fun railAlignment(layout: LayoutSpec): Alignment = when {
    layout.railMode == RailMode.DOCK -> Alignment.BottomCenter
    layout.railSide == Hand.RIGHT -> Alignment.BottomEnd
    else -> Alignment.BottomStart
}

/**
 * The ledge fills the strip of window the rail leaves free: beside a
 * full-height SHORT rail, above a DOCK. [DesktopSliderLedge] draws nothing
 * in the modes whose sliders live in the rail, so this placement is inert
 * there.
 */
private fun Modifier.ledgePlacement(scope: BoxScope, layout: LayoutSpec): Modifier = with(scope) {
    val docked = layout.railMode == RailMode.DOCK
    val railWidth = if (docked) 0.dp else layout.railWidthDp.dp
    return this@ledgePlacement
        .align(
            when {
                docked -> Alignment.BottomCenter
                layout.railSide == Hand.RIGHT -> AbsoluteAlignment.BottomLeft
                else -> AbsoluteAlignment.BottomRight
            },
        )
        .absolutePadding(
            left = if (!docked && layout.railSide == Hand.LEFT) railWidth else 0.dp,
            right = if (!docked && layout.railSide == Hand.RIGHT) railWidth else 0.dp,
            // The dock's own height, from LayoutSpec — a second copy of it
            // here could drift and float the ledge off the dock.
            bottom = if (docked) LayoutSpec.DOCK_HEIGHT_DP.dp + LEDGE_GAP else LEDGE_GAP,
        )
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
    kind: ToolKind,
    colorArgb: Int,
    mixer: ch.lkmc.bangnidraw.engine.core.ColorMixer,
    onColorPicked: (Int) -> Unit,
) {
    val sample = PointerSample()
    // The kind already carries the rail sliders' tuning; the gesture must not
    // re-derive it from a second copy of the size.
    DesktopShell.tool = DesktopShell.Tool(kind, colorArgb, mixer, onColorPicked)

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
                        // `onScroll` takes wheel NOTCHES — `ScrollZoom` raises
                        // STEP_PER_NOTCH to that power and clamps the burst
                        // itself. Compose Desktop's scrollDelta is already in
                        // those units; the divisor this replaces made one
                        // notch a 0.35% zoom instead of 15%.
                        handler.onScroll(
                            position.x,
                            position.y,
                            -change.scrollDelta.y,
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
        val kind: ToolKind,
        val colorArgb: Int,
        val mixer: ch.lkmc.bangnidraw.engine.core.ColorMixer,
        /** Where an eyedropper read lands; called on the UI thread. */
        val onColorPicked: (Int) -> Unit,
    )

    var tool: Tool? = null
    var mouseMode = DesktopMouseMode.Draw

    /**
     * What the right button draws with. It is the stylus eraser end, so it
     * erases whatever brush the rail holds — and while a secondary tool is
     * selected there is no brush to erase with, so the gesture is refused
     * rather than silently smudging.
     */
    fun eraserKind(kind: ToolKind): ToolKind? = kind as? ToolKind.Brush

    fun handler(engine: DesktopEngine, density: Float): CanvasTouchHandler {
        lateinit var handler: CanvasTouchHandler
        handler = CanvasTouchHandler(density = density, host = DesktopInputHost(engine) { handler })
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
    private var pick: EyedropperParams? = null
    private var pickGate = EyedropperSampleGate()
    private var fillPending = false

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
        pick = null
        fillPending = false

        val resolvedSource = DesktopStrokePolicy.source(source, DesktopShell.mouseMode)
        this.source = resolvedSource
        // The right button is the eraser end, whatever tool the rail holds:
        // the pen you flipped over erases, it does not smudge.
        val kind = if (resolvedSource == StrokeSource.ERASER_END) {
            DesktopShell.eraserKind(tool.kind) ?: return
        } else {
            tool.kind
        }

        // Neither of these opens a stroke. The eyedropper reads pixels as the
        // pointer moves; the fill runs once, from the first sample's point.
        if (kind is ToolKind.Eyedropper) {
            pick = kind.params
            pickGate.reset()
            return
        }
        if (kind is ToolKind.Fill) {
            // Lock refuses pixels before anything is scanned; the engine
            // checks again on its own thread, this only saves the work.
            if (StrokeLayerPolicy.decide(
                    visible = active.props.visible,
                    locked = active.props.locked,
                ) == StrokeLayerDecision.REFUSE_LOCKED
            ) {
                return
            }
            fillPending = true
            return
        }

        val preset = when (kind) {
            is ToolKind.Brush -> kind.preset
            is ToolKind.Smudge -> RmwDabPreset.smudge(kind.params)
            is ToolKind.Water -> RmwDabPreset.water(kind.params)
            is ToolKind.Blur -> RmwDabPreset.blur(kind.params)
            is ToolKind.Fill, is ToolKind.Eyedropper -> null
        } ?: return

        val mode = if (kind is ToolKind.Brush) {
            DesktopStrokePolicy.mode(resolvedSource, preset, tool.mixer)
        } else {
            // An RMW tool never paints the brush colour; the merge reads what
            // is already there.
            StrokeMode.PAINT
        }
        val rmw = if (resolvedSource == StrokeSource.ERASER_END) {
            null
        } else {
            RmwStrokePolicy.spec(kind, tool.mixer)
        }
        val spec = StrokeSpec(
            layerId = active.id,
            mode = mode,
            opacity = preset.opacity,
            alphaLock = active.props.alphaLock,
            dilution = if (mode == StrokeMode.MIX) preset.dilution else 0f,
            grainMode = preset.grainMode,
            brushModel = preset.model,
            rmw = rmw,
        )
        driver = StrokeDriver(
            preset,
            seed = System.nanoTime(),
            zoom = handler().canvasToScreenScale,
            spacingPolicy = if (rmw == null) {
                DabSpacingPolicy.Brush
            } else {
                DabSpacingPolicy.ReadModifyWrite
            },
        )

        val rgb = DesktopPalette.toStrokeRgb(tool.colorArgb)
        engine.beginStroke(spec, preset.bufferMode, rgb[0], rgb[1], rgb[2])
    }

    override fun onStrokeSample(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
    ) {
        val picking = pick
        if (picking != null) {
            // Every read is a synchronous glReadPixels — a full pipeline sync
            // — so the gate drops intermediate samples to one read per frame.
            if (!pickGate.shouldRead(System.nanoTime() / NANOS_PER_MS)) return

            val onPicked = DesktopShell.tool?.onColorPicked ?: return
            engine.sampleColor(x, y, picking) { color ->
                color?.let { argb -> java.awt.EventQueue.invokeLater { onPicked(argb) } }
            }
            return
        }
        if (fillPending) {
            // One fill per gesture, from where it started.
            fillPending = false
            val tool = DesktopShell.tool ?: return
            val params = (tool.kind as? ToolKind.Fill)?.params ?: return
            engine.startFill(x, y, params, tool.colorArgb) {}
            return
        }

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
        pick = null
        fillPending = false
        val driver = driver ?: return
        this.driver = null

        while (driver.isActive) {
            val batch = engine.acquireDabBatch()
            if (batch == null) {
                // Out of ring slots at pen-up: stop generating, but still
                // COMMIT what was already stamped. `CanvasScreen` breaks here
                // for the same reason — cancelling instead would throw away
                // the whole stroke the user just drew, and the engine-side
                // stroke still has to be closed either way (§4's pairing).
                driver.cancel()
                break
            }
            val emitted = driver.end(batch)
            if (emitted == 0) engine.releaseDabBatch(batch) else engine.stampDabs(batch)
        }
        engine.endStroke(driver.opacityCeiling) {}
    }

    override fun onStrokeCancel() {
        pick = null
        if (fillPending) {
            fillPending = false
        } else {
            engine.cancelFill()
        }
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

private const val RGBA_BYTES = 4
private const val MOUSE_POINTER_ID = 0
private const val SYNTHETIC_PRESSURE = 1f
private const val NANOS_PER_MS = 1_000_000L
private const val CANVAS_EDGE = 2048
private const val VERIFY_RUNTIME_FLAG = "--verify-runtime"
private const val GL_REPORT_FLAG = "--gl-report"
private const val SMOKE_STARTUP_FAILURE_FLAG = "--smoke-startup-failure"
private const val SMOKE_WINDOW_FLAG = "--smoke-window"
private const val INITIAL_GL_WIDTH = 1
private const val INITIAL_GL_HEIGHT = 1
// The gap LayoutSpec's docked chrome already reserves above the dock;
// a second copy here could drift and leave the two disagreeing.
private val LEDGE_GAP = LayoutSpec.LEDGE_GAP_DP.dp
private const val SAVED_MESSAGE_MS = 6_000L
// The old floor existed for a fixed 230 dp sidebar beside a toolbar row.
// The chrome is adaptive now — LayoutSpec shortens the rail, then docks it —
// so the window may be as small as one a person could still draw in.
private const val WINDOW_MIN_W = 640
private const val WINDOW_MIN_H = 480
private const val UNTITLED_NAME = "Untitled"

internal object DesktopGlDiagnostics {
    private const val HEADLINE = "OpenGL ES 3.0 is unavailable."

    private const val REPORT_HINT =
        "Run this app from a terminal with --gl-report for the full report."

    /**
     * The same hint, naming this app's own launcher when it has one. A user who
     * double-clicked a .app has no terminal and no reason to know its
     * executable lives under Contents/MacOS.
     */
    private fun reportHint(): String {
        val command = runCatching { ProcessHandle.current().info().command().orElse(null) }
            .getOrNull()
            ?.takeUnless { it.endsWith("/java") || it.endsWith("java.exe") }
            ?: return REPORT_HINT

        return "Run this for the full report:\n  \"$command\" --gl-report"
    }

    private val platformGuidance = """
        Linux: install Mesa libEGL and libGLESv2, or your GPU vendor driver.
        macOS: provide libEGL.dylib and libGLESv2.dylib with
        -Dbangnidraw.angle.dir=/path/to/angle, or place both in the app resources.
        Windows: this desktop target supports macOS and Linux only.
    """.trimIndent()

    /** The headline and guidance, with the pointer to the report flag. */
    fun unavailable(): String = "$HEADLINE\n\n${reportHint()}\n\n$platformGuidance"

    /**
     * The failure window's text. It carries the evidence — which host refused,
     * with which error, against which libraries — because the one thing a
     * remote report of "OpenGL ES 3.0 is unavailable" cannot say is why.
     */
    fun failure(report: DesktopGlReport): String = listOf(
        HEADLINE,
        report.failures(),
        "Details:\n${report.details()}",
        reportHint(),
        platformGuidance,
    ).filter { it.isNotBlank() }.joinToString("\n\n")

    val contextFailure = "Could not create a GLES 3.0 EGL context.\n\n$platformGuidance"

    val rendererRequirements =
        "OpenGL ES 3.0 with texture arrays is required.\n\n$platformGuidance"
}
