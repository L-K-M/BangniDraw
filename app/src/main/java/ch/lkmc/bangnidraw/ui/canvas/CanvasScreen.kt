package ch.lkmc.bangnidraw.ui.canvas

import android.animation.ValueAnimator
import android.content.Intent
import android.content.ContextWrapper
import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.widget.Toast
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.DisposableEffect
import ch.lkmc.bangnidraw.BuildConfig
import ch.lkmc.bangnidraw.CanvasShortcutSink
import ch.lkmc.bangnidraw.MainActivity
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.ImageEncode
import ch.lkmc.bangnidraw.data.GalleryExportOutcome
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.ButtonState
import ch.lkmc.bangnidraw.engine.core.CanvasDialog
import ch.lkmc.bangnidraw.engine.core.CanvasPanel
import ch.lkmc.bangnidraw.engine.core.ColorText
import ch.lkmc.bangnidraw.engine.core.CanvasShortcut
import ch.lkmc.bangnidraw.engine.core.DabSpacingPolicy
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.EyedropperSampleGate
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.FocusMode
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.HintVisibility
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import ch.lkmc.bangnidraw.engine.core.PressureCurve
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.RmwDabPreset
import ch.lkmc.bangnidraw.engine.core.ShortcutContext
import ch.lkmc.bangnidraw.engine.core.SizeAdjustment
import ch.lkmc.bangnidraw.engine.core.StrokeDriver
import ch.lkmc.bangnidraw.engine.core.StrokeInputBatch
import ch.lkmc.bangnidraw.engine.core.StrokeLayerDecision
import ch.lkmc.bangnidraw.engine.core.StrokeLayerPolicy
import ch.lkmc.bangnidraw.engine.core.StrokeMode
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.TemporaryReason
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset
import ch.lkmc.bangnidraw.engine.core.TouchDrawingMode
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.core.WidthClass
import ch.lkmc.bangnidraw.input.CanvasInputHost
import ch.lkmc.bangnidraw.input.CanvasTouchHandler
import ch.lkmc.bangnidraw.ui.theme.LocalThemeTone
import ch.lkmc.bangnidraw.ui.theme.canvasVoidColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Canvas: where one painting is painted (PLAN.md §5).
 *
 * Roadmap 3a: the painting persists. [CanvasViewModel] opens or creates the
 * routed project, the engine's readback funnels merged tiles to disk, and the
 * leave/`ON_STOP` checkpoints write `project.json`
 * (`docs/plan/06-document-and-persistence.md` §6.2's clock-free rows; the
 * quiet and ceiling clocks are 3b's).
 *
 * Both back paths — the arrow and the system gesture — go through
 * [CanvasViewModel.leave], which navigates only after the checkpoint has
 * landed, so the Studio never lists a shelf the write has not reached.
 */
@Composable
fun CanvasScreen(
    onBack: () -> Unit,
    onSettings: () -> Unit = onBack,
    viewModel: CanvasViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val leave = { viewModel.leave(onBack) }
    BackHandler { viewModel.handleBack(onBack) }

    // §6.2's ON_STOP row: the last callback before the process may be
    // reclaimed. Fire-and-forget — the write survives on the app scope.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.checkpointNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val current = state) {
        CanvasViewModel.UiState.Loading -> Box(Modifier.fillMaxSize())

        is CanvasViewModel.UiState.Failed -> Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            IconButton(onClick = leave, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.canvas_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(current.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        is CanvasViewModel.UiState.Ready -> CanvasContent(
            state = current,
            viewModel = viewModel,
            onLeave = leave,
            onSettings = { viewModel.leave(onSettings) },
        )
    }
}

@Composable
private fun CanvasContent(
    state: CanvasViewModel.UiState.Ready,
    viewModel: CanvasViewModel,
    onLeave: () -> Unit,
    onSettings: () -> Unit,
) {
    var view by rememberSaveable(stateSaver = VIEW_TRANSFORM_SAVER) {
        mutableStateOf(ViewTransform())
    }
    val stack = state.stack
    var session by remember { mutableStateOf<EngineSession?>(null) }
    var hoverRevision by remember { mutableIntStateOf(0) }
    var textInputFocus by remember { mutableStateOf(TextInputFocus.CLEAR) }
    var showRecentSwatches by remember { mutableStateOf(false) }
    val recentColors = state.color.palettes
        .firstOrNull { it.id == PaletteCatalog.RECENT_ID }
        ?.swatches
        .orEmpty()
    val recentScroll = rememberScrollState()
    var historyReadout by remember { mutableIntStateOf(0) }
    val layerThumbnails by viewModel.layerThumbnails.collectAsStateWithLifecycle()

    // The stroke in flight. Plain vars, not Compose state: they change several
    // hundred times a second on the input path and nothing draws from them, so
    // making them observable would recompose the whole screen per pen sample.
    val strokeState = remember { StrokeUiState() }
    var navigating by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val view0 = LocalView.current
    val context = LocalContext.current
    CanvasImmersiveEffect()
    var seenStrokeNotice by remember { mutableLongStateOf(state.strokeLayerNoticeRevision) }
    var seenLeaveNotice by remember { mutableLongStateOf(state.leaveNoticeRevision) }

    fun updateView(next: ViewTransform) {
        view = next
    }

    // 06 §4's one honest toast per open, when something could not be read.
    LaunchedEffect(state.warning) {
        state.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    // A failed leave keeps the canvas open; say so once per failure.
    LaunchedEffect(state.leaveNoticeRevision) {
        if (state.leaveNoticeRevision == seenLeaveNotice) return@LaunchedEffect
        seenLeaveNotice = state.leaveNoticeRevision
        Toast.makeText(context, R.string.err_leave_failed, Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(state.strokeLayerNoticeRevision) {
        if (state.strokeLayerNoticeRevision == seenStrokeNotice) return@LaunchedEffect
        seenStrokeNotice = state.strokeLayerNoticeRevision
        val notice = state.strokeLayerNotice ?: return@LaunchedEffect
        Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
        if (state.hapticsMode == HapticsMode.DISABLED) return@LaunchedEffect
        val haptic = when {
            notice == R.string.layer_locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                HapticFeedbackConstants.REJECT
            notice == R.string.layer_locked -> HapticFeedbackConstants.LONG_PRESS
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        view0.performHapticFeedback(haptic)
    }

    // Roadmap 2.4a: real two-finger navigation. The handler owns the view
    // transform while a gesture is running and reports it back here.
    // view0 is in the key because the host below captures it for the snap
    // haptic: a composition that moved to a different View would otherwise keep
    // ticking the old one. Safe now that CanvasSurface re-attaches on update.
    // `stack` is in the key for the same capture reason: the host reads the
    // active layer at pen-down, and 3a's stack is fixed per open, so a changed
    // stack means a different painting.
    val touch = remember(density, view0, stack, state.hapticsMode) {
        lateinit var handler: CanvasTouchHandler
        handler = CanvasTouchHandler(
            density = density.density,
            host = object : CanvasInputHost {
                override fun onViewChanged(view: ViewTransform) { updateView(view) }
                override fun onRotationSnapped() {
                    // A single tick as the canvas clicks to straight (§7).
                    if (state.hapticsMode == HapticsMode.ENABLED) {
                        view0.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                // Two- and three-finger taps (roadmap 3b): straight into the
                // journal; the ViewModel drops a request that lands mid-apply.
                override fun onUndoRequested() {
                    if (state.hapticsMode == HapticsMode.ENABLED) {
                        view0.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    viewModel.undo()
                }
                override fun onRedoRequested() {
                    if (state.hapticsMode == HapticsMode.ENABLED) {
                        view0.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    viewModel.redo()
                }
                override fun onHoverChanged() {
                    hoverRevision++
                    if (handler.stylus.isHovering) {
                        viewModel.beginHoverTool(handler.stylus.tool)
                    } else {
                        viewModel.endHoverTool()
                    }
                }
                override fun onColorPick(x: Float, y: Float) {
                    val engine = session ?: return
                    engine.sampleColor(x, y, viewModel.currentEyedropperParams()) { color ->
                        color?.let(viewModel::selectBrushColor)
                    }
                }

                override fun onNavigateActive(active: Boolean) { navigating = active }

                override fun onStrokeBegin(pointerId: Int, source: StrokeSource) {
                    val staleFillStarted = strokeState.fillStarted
                    viewModel.cancelFill()
                    // A begin while one is already open means the previous
                    // stroke's end was lost — a gesture transition, a torn-down
                    // session. Cancel it rather than orphaning the driver and
                    // opening a second stroke on the engine while the first is
                    // still live (§4: a cancelled stroke leaves no trace).
                    val staleReason = strokeState.temporaryReason
                    strokeState.temporaryReason = null
                    if (strokeState.pickParams != null) {
                        viewModel.cancelPickedColor()
                    }
                    strokeState.pickParams = null
                    strokeState.fillParams = null
                    strokeState.fillTouch = false
                    strokeState.fillStarted = false
                    val staleDriver = strokeState.driver
                    staleDriver?.cancel()
                    strokeState.driver = null
                    // The engine the stale stroke was opened on, which is not
                    // necessarily the current one.
                    if (staleDriver != null) {
                        strokeState.engine?.cancelStroke(viewModel::prepareStrokeCancel)
                    }
                    strokeState.engine = null
                    strokeState.readModifyWrite = false
                    strokeState.colorUsage = StrokeColorUsage.IGNORE
                    val staleDisposition = if (staleFillStarted) {
                        StrokeEndDisposition.AWAIT_COMMIT
                    } else {
                        StrokeEndDisposition.COMPLETE
                    }
                    viewModel.endStrokeTool(staleReason, staleDisposition)
                    val pickGeneration = strokeState.nextPickGeneration()

                    val engine = session ?: return
                    val active = stack.layers.getOrNull(stack.activeIndex) ?: return
                    val button = if (handler.stylus.buttonPressed) {
                        ButtonState.Pressed
                    } else {
                        ButtonState.Released
                    }
                    val selection = viewModel.beginStrokeTool(source, button) ?: return
                    strokeState.temporaryReason = selection.temporaryReason
                    val kind = selection.kind
                    if (kind is ToolKind.Eyedropper) {
                        viewModel.prepareColorPick()
                        strokeState.engine = engine
                        strokeState.pickParams = kind.params
                        strokeState.pickGeneration = pickGeneration
                        // A fresh gate per stroke: the first sample must read,
                        // wherever the previous drag's timing left it.
                        strokeState.pickGate.reset()
                        return
                    }
                    if (kind is ToolKind.Fill) {
                        val layerDecision = StrokeLayerPolicy.decide(
                            visible = active.props.visible,
                            locked = active.props.locked,
                        )
                        viewModel.noteStrokeLayerDecision(layerDecision)
                        if (layerDecision == StrokeLayerDecision.REFUSE_LOCKED) {
                            viewModel.endStrokeTool(
                                strokeState.temporaryReason,
                                StrokeEndDisposition.COMPLETE,
                            )
                            strokeState.temporaryReason = null
                            return
                        }

                        strokeState.engine = engine
                        strokeState.fillParams = kind.params
                        strokeState.fillTouch = true
                        strokeState.setColor(viewModel.currentBrushColor())
                        return
                    }
                    val preset = when (kind) {
                        is ToolKind.Brush -> kind.preset
                        is ToolKind.Smudge -> RmwDabPreset.smudge(kind.params)
                        is ToolKind.Blur -> RmwDabPreset.blur(kind.params)
                        is ToolKind.Fill, is ToolKind.Eyedropper -> null
                    }
                    if (preset == null) {
                        viewModel.endStrokeTool(
                            strokeState.temporaryReason,
                            StrokeEndDisposition.COMPLETE,
                        )
                        strokeState.temporaryReason = null
                        return
                    }

                    val layerDecision = StrokeLayerPolicy.decide(
                        visible = active.props.visible,
                        locked = active.props.locked,
                    )
                    viewModel.noteStrokeLayerDecision(layerDecision)
                    if (layerDecision == StrokeLayerDecision.REFUSE_LOCKED) {
                        viewModel.endStrokeTool(
                            strokeState.temporaryReason,
                            StrokeEndDisposition.COMPLETE,
                        )
                        strokeState.temporaryReason = null
                        return
                    }

                    val rmw = viewModel.rmwSpec(kind)
                    // A new seed keeps each stroke's jitter independent;
                    // procedural grain remains fixed to the canvas.
                    val driver = StrokeDriver(
                        preset,
                        seed = strokeState.nextSeed(),
                        zoom = handler.canvasToScreenScale,
                        spacingPolicy = if (rmw == null) {
                            DabSpacingPolicy.Brush
                        } else {
                            DabSpacingPolicy.ReadModifyWrite
                        },
                    )
                    strokeState.driver = driver
                    strokeState.engine = engine
                    strokeState.readModifyWrite = rmw != null
                    strokeState.colorUsage = if (kind is ToolKind.Brush && !preset.eraseMode) {
                        StrokeColorUsage.RECORD
                    } else {
                        StrokeColorUsage.IGNORE
                    }
                    // Carried for every later sample. onStrokeBegin inspects
                    // `source` to pick erase mode, so passing a hardcoded
                    // STYLUS to the driver afterwards would report an eraser or
                    // a finger as a pen for the whole rest of the stroke.
                    strokeState.source = source
                    strokeState.setColor(viewModel.currentBrushColor())
                    val strokeMode = if (kind is ToolKind.Brush) {
                        viewModel.strokeMode(preset)
                    } else {
                        StrokeMode.PAINT
                    }
                    val spec = StrokeSpec(
                        layerId = active.id,
                        mode = strokeMode,
                        // The ceiling is only known once the stroke has ended —
                        // it is the maximum pressure actually used — so the
                        // spec sent at pen-down carries the preset's own
                        // opacity and endStroke re-sends the measured one.
                        opacity = preset.opacity,
                        alphaLock = active.props.alphaLock,
                        dilution = if (strokeMode == StrokeMode.MIX) preset.dilution else 0f,
                        grainMode = preset.grainMode,
                        rmw = rmw,
                    )
                    engine.beginStroke(
                        spec, preset.bufferMode,
                        strokeState.colorR, strokeState.colorG, strokeState.colorB,
                    )
                }

                override fun onStrokeSample(
                    x: Float,
                    y: Float,
                    pressure: Float,
                    tilt: Float,
                    orientation: Float,
                    timeNs: Long,
                ) {
                    // The stroke's own engine, not `session`. Identity, not
                    // nullability: a replaced session is non-null and would
                    // accept these calls while having no stroke open.
                    val engine = strokeState.engine ?: return
                    val pick = strokeState.pickParams
                    if (pick != null) {
                        // Each read is a synchronous glReadPixels (a pipeline
                        // sync), and unbuffered dispatch delivers hundreds of
                        // samples a second — so intermediate samples are
                        // dropped to one read per frame. Pen-up commits the
                        // last color actually previewed, which is what the
                        // user was shown.
                        if (strokeState.pickGate.shouldRead(SystemClock.uptimeMillis())) {
                            val generation = strokeState.pickGeneration
                            engine.sampleColor(x, y, pick) { color ->
                                if (strokeState.pickGeneration == generation) {
                                    color?.let(viewModel::previewPickedColor)
                                }
                            }
                        }
                        return
                    }
                    val fill = strokeState.fillParams
                    if (fill != null) {
                        strokeState.fillParams = null
                        strokeState.fillStarted = viewModel.startFill(
                            engine,
                            x,
                            y,
                            fill,
                            strokeState.colorArgb,
                        ) == FillStartResult.STARTED
                        return
                    }

                    val driver = strokeState.driver ?: return
                    val batch = engine.acquireDabBatch() ?: return
                    val emitted = if (driver.isActive) {
                        driver.sample(x, y, pressure, tilt, orientation, timeNs, strokeState.source, batch)
                    } else {
                        driver.begin(x, y, pressure, tilt, orientation, timeNs, strokeState.source, batch)
                    }
                    if (emitted == 0) engine.releaseDabBatch(batch) else engine.stampDabs(batch)
                }

                override fun onStrokeEnd(pointerId: Int) {
                    val reason = strokeState.temporaryReason
                    strokeState.temporaryReason = null
                    if (strokeState.pickParams != null) {
                        strokeState.nextPickGeneration()
                        viewModel.commitPickedColor()
                        strokeState.pickParams = null
                        strokeState.engine = null
                        viewModel.endStrokeTool(reason, StrokeEndDisposition.COMPLETE)
                        if (state.hapticsMode == HapticsMode.ENABLED) {
                            view0.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        return
                    }

                    val fillStarted = strokeState.fillStarted
                    strokeState.fillParams = null
                    strokeState.fillTouch = false
                    strokeState.fillStarted = false

                    val driver = strokeState.driver
                    if (driver == null) {
                        strokeState.engine = null
                        val disposition = if (fillStarted) {
                            StrokeEndDisposition.AWAIT_COMMIT
                        } else {
                            StrokeEndDisposition.COMPLETE
                        }
                        viewModel.endStrokeTool(reason, disposition)
                        return
                    }
                    // Cleared before the session check, so no early return can
                    // leave a dead stroke's driver installed for the next
                    // sample to feed.
                    strokeState.driver = null
                    strokeState.readModifyWrite = false
                    val colorUsage = strokeState.colorUsage
                    strokeState.colorUsage = StrokeColorUsage.IGNORE
                    val strokeColor = strokeState.colorArgb
                    val engine = strokeState.engine
                    strokeState.engine = null
                    if (engine == null) {
                        driver.cancel()
                        viewModel.endStrokeTool(reason, StrokeEndDisposition.COMPLETE)
                        return
                    }
                    val batch = engine.acquireDabBatch()
                    if (batch != null) {
                        if (driver.end(batch) == 0) engine.releaseDabBatch(batch)
                        else engine.stampDabs(batch)
                    } else {
                        driver.cancel()
                    }
                    engine.endStroke(driver.opacityCeiling)
                    // The commit's pixels reach disk through the readback; the
                    // ViewModel only needs to know the document changed.
                    viewModel.onStrokeCommitted(colorUsage, strokeColor)
                    viewModel.endStrokeTool(reason, StrokeEndDisposition.AWAIT_COMMIT)
                }

                override fun onStrokeCancel() {
                    val reason = strokeState.temporaryReason
                    strokeState.temporaryReason = null
                    if (strokeState.pickParams != null) {
                        strokeState.nextPickGeneration()
                        viewModel.cancelPickedColor()
                        strokeState.pickParams = null
                        strokeState.engine = null
                        viewModel.endStrokeTool(reason, StrokeEndDisposition.COMPLETE)
                        return
                    }
                    val wasFill = strokeState.fillTouch
                    val fillStarted = strokeState.fillStarted
                    strokeState.fillParams = null
                    strokeState.fillTouch = false
                    strokeState.fillStarted = false
                    viewModel.cancelFill()
                    strokeState.driver?.cancel()
                    strokeState.driver = null
                    if (!wasFill) strokeState.engine?.cancelStroke(viewModel::prepareStrokeCancel)
                    strokeState.engine = null
                    strokeState.readModifyWrite = false
                    strokeState.colorUsage = StrokeColorUsage.IGNORE
                    val disposition = if (wasFill && fillStarted) {
                        StrokeEndDisposition.AWAIT_COMMIT
                    } else {
                        StrokeEndDisposition.COMPLETE
                    }
                    viewModel.endStrokeTool(reason, disposition)
                }

                // Roadmap 2.5b: §9's predicted tail. The same ring and the same
                // publish as a real batch — the dabs carry `predictedFrom`, and
                // the renderer routes them to the tail buffer from that.
                override fun onStrokePredicted(samples: StrokeInputBatch) {
                    if (strokeState.readModifyWrite) return
                    val driver = strokeState.driver ?: return
                    val engine = strokeState.engine ?: return
                    val batch = engine.acquireDabBatch() ?: return
                    // A tail is a guess, so a starved ring drops it without a
                    // second thought: the next frame brings another, and the
                    // real samples — which cannot be regenerated — keep the
                    // slots they need.
                    val emitted = driver.predict(samples, batch)
                    if (emitted == 0) engine.releaseDabBatch(batch) else engine.stampDabs(batch)
                }
            },
        )
        handler
    }
    val checkerA = MaterialTheme.colorScheme.surface.toArgb()
    val checkerB = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val canvasVoid = canvasVoidColor(LocalThemeTone.current).toArgb()

    // Keyed on the handler, not Unit: a recreated handler starts from an
    // identity transform, and without re-seeding its first gesture would
    // measure from the wrong baseline and jump.
    LaunchedEffect(touch, state.touchDrawingMode, state.pressurePreference) {
        touch.setView(view)
        touch.stylusOnly = state.touchDrawingMode == TouchDrawingMode.STYLUS_ONLY
        touch.pressureCurve = PressureCurve.of(preference = state.pressurePreference)
    }

    val shortcutContext = if (
        state.chrome.dialog is CanvasDialog.RenameLayer ||
        state.chrome.dialog == CanvasDialog.RenamePainting ||
        textInputFocus == TextInputFocus.FOCUSED
    ) {
        ShortcutContext.TEXT_INPUT
    } else {
        ShortcutContext.CANVAS
    }
    val shortcutContextState = rememberUpdatedState(shortcutContext)
    val shortcutHandlerState = rememberUpdatedState<(CanvasShortcut) -> Boolean> { shortcut ->
        when (shortcut) {
            CanvasShortcut.UNDO -> viewModel.undo()
            CanvasShortcut.REDO -> viewModel.redo()
            CanvasShortcut.SIZE_DOWN -> viewModel.adjustBrushSize(SizeAdjustment.DECREASE)
            CanvasShortcut.SIZE_UP -> viewModel.adjustBrushSize(SizeAdjustment.INCREASE)
            CanvasShortcut.BRUSH -> viewModel.selectPaintBrush()
            CanvasShortcut.ERASER -> viewModel.selectEraser()
            CanvasShortcut.SMUDGE -> viewModel.selectSmudge()
            CanvasShortcut.FILL -> viewModel.selectFill()
            CanvasShortcut.EYEDROPPER -> viewModel.selectEyedropper()
            CanvasShortcut.BEGIN_EYEDROPPER -> viewModel.beginKeyboardEyedropper()
            CanvasShortcut.END_EYEDROPPER -> viewModel.endKeyboardEyedropper()
            CanvasShortcut.RESET_VIEW -> {
                view = ViewTransform()
                touch.setView(view)
            }
            CanvasShortcut.TOGGLE_FOCUS -> viewModel.toggleFocus()
            CanvasShortcut.TOGGLE_LAYERS -> viewModel.togglePanel(CanvasPanel.LAYERS)
            CanvasShortcut.TOGGLE_COLOR -> viewModel.togglePanel(CanvasPanel.COLOR)
        }
        true
    }
    val shortcutSink = remember(viewModel, touch) {
        object : CanvasShortcutSink {
            override val shortcutContext: ShortcutContext
                get() = shortcutContextState.value

            override fun onShortcut(shortcut: CanvasShortcut): Boolean =
                shortcutHandlerState.value(shortcut)
        }
    }
    val activity = remember(context) { context.findActivity() as? MainActivity }
    DisposableEffect(activity, shortcutSink) {
        activity?.installShortcutSink(shortcutSink)
        onDispose {
            activity?.uninstallShortcutSink(shortcutSink)
            viewModel.endKeyboardEyedropper()
        }
    }

    val animationScope = rememberCoroutineScope()
    val resetJob = remember { arrayOfNulls<Job>(1) }
    val resetView = {
        resetJob[0]?.cancel()
        val start = view
        val reset = ViewTransform()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            updateView(reset)
            touch.setView(reset)
            if (state.hapticsMode == HapticsMode.ENABLED) {
                view0.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        } else {
            resetJob[0] = animationScope.launch {
                Animatable(0f).animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = RESET_DAMPING_RATIO,
                        stiffness = Spring.StiffnessHigh,
                    ),
                ) {
                    val next = start.lerp(reset, value)
                    updateView(next)
                    touch.setView(next)
                }
                updateView(reset)
                touch.setView(reset)
                if (state.hapticsMode == HapticsMode.ENABLED) {
                    view0.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthClass = WidthClass.forWidth(maxWidth.value.roundToInt())
        val windowWidth = maxWidth
        val windowHeight = maxHeight
        val safeInsets = WindowInsets.safeDrawing
        val verticalInsetDp = (
            safeInsets.getTop(density) + safeInsets.getBottom(density)
            ) / density.density
        val railHeight = (
            maxHeight.value - verticalInsetDp - LayoutSpec.TOP_STRIP_DP
            ).toInt().coerceAtLeast(0)
        val layout = LayoutSpec.forWindow(widthClass, railHeight, state.handedness)
        val undoAvailability =
            if (state.canUndo) ActionAvailability.ENABLED else ActionAvailability.DISABLED
        val redoAvailability =
            if (state.canRedo) ActionAvailability.ENABLED else ActionAvailability.DISABLED
        LaunchedEffect(state.chrome.openPanel) {
            if (state.chrome.openPanel != CanvasPanel.COLOR) {
                textInputFocus = TextInputFocus.CLEAR
            }
        }
        val paintingName = state.title.ifBlank { stringResource(R.string.studio_untitled) }
        val canvasDescription = stringResource(
            R.string.canvas_description,
            paintingName,
            state.canvas.width,
            state.canvas.height,
            toolName(state.toolSelection.kind),
            layerName(state.stack.active.props.name),
        )
        CanvasSurface(
            canvas = state.canvas,
            stack = stack,
            paperColor = state.paperColor,
            view = view,
            canvasDescription = canvasDescription,
            undoLabel = stringResource(R.string.canvas_undo),
            redoLabel = stringResource(R.string.canvas_redo),
            undoAvailability = undoAvailability,
            redoAvailability = redoAvailability,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            gestureExclusionSide = if (layout.railMode == RailMode.DOCK) null else layout.railSide,
            gestureExclusionWidthDp = layout.railWidthDp + EXCLUSION_GAP_DP,
            modifier = Modifier
                .fillMaxSize()
                .semantics { traversalIndex = CANVAS_TRAVERSAL },
            debugBuild = BuildConfig.DEBUG,
            onSession = { attached ->
                // A session arriving or departing takes any live stroke with
                // it, and the pin has to go with it — this is the seam that
                // makes `strokeState.engine` safe to read everywhere else.
                //
                // Pinning the stroke to its own engine removed the accidental
                // protection the old `session ?: return` reads had: `session`
                // goes null on dispose, so those returned early, while a pin
                // held on a *released* EngineSession would not. That is the
                // reachable half of the problem — a surface torn down mid-
                // gesture (back navigation, system teardown) still delivers
                // trailing move and cancel events.
                //
                // The driver is cancelled but `cancelStroke` is deliberately
                // NOT called on the old engine: it has been released, and
                // `stampDabs`/`endStroke`/`cancelStroke` queue through
                // `frontBuffered.execute` with no validity guard. A block that
                // never runs also never returns its `DabRing` slot, so a
                // handful of those would strand the pool and backpressure all
                // later input. §4 makes dropping it correct:
                // a cancelled stroke leaves no trace, so there is nothing the
                // dead engine still owes.
                //
                // `CanvasSurface` calls this exactly twice per session — once
                // from the `AndroidView` factory and once from its
                // `DisposableEffect`'s dispose — and never re-emits a session
                // it has already handed over, so there is no live stroke to
                // preserve here and no identity guard to add. A guard would be
                // dead code defending an emission this contract does not make.
                //
                // Pins dropped BEFORE the cancel, and the local is what makes
                // that possible. `StrokeDriver.cancel` only clears a boolean —
                // it takes no listener and cannot reach these handlers, so it
                // cannot re-enter today. The ordering is written this way so
                // the seam stays correct on its own terms rather than on a
                // fact about another class: whatever runs below, the pin is
                // already gone.
                val stale = strokeState.driver
                val fillStarted = strokeState.fillStarted
                if (strokeState.pickParams != null) {
                    strokeState.nextPickGeneration()
                    viewModel.cancelPickedColor()
                }
                strokeState.pickParams = null
                strokeState.fillParams = null
                strokeState.fillTouch = false
                strokeState.fillStarted = false
                viewModel.cancelFill()
                strokeState.driver = null
                strokeState.engine = null
                strokeState.readModifyWrite = false
                strokeState.colorUsage = StrokeColorUsage.IGNORE
                stale?.cancel()
                val disposition = if (fillStarted) {
                    StrokeEndDisposition.AWAIT_COMMIT
                } else {
                    StrokeEndDisposition.COMPLETE
                }
                viewModel.endStrokeTool(strokeState.temporaryReason, disposition)
                strokeState.temporaryReason = null
                session = attached
                // The ViewModel streams the painting's tiles into an arriving
                // engine (§5.7's reopen path) and stops waiting on a departed
                // one.
                viewModel.attachSession(attached)
            },
            touchHandler = touch,
            onTile = viewModel::onTileReadback,
            revisions = viewModel.revisions,
        )

        // Not in `onSession`: that fires once, from the AndroidView factory, so
        // a dark-mode toggle kept the old theme's checkerboard and canvas void
        // until the screen was torn down, while a density change kept the old
        // square size. These values recompose; the engine has to be told.
        LaunchedEffect(session, checkerA, checkerB, canvasVoid, density) {
            session?.setCanvasAppearance(
                checkerPx = with(density) { CHECKER_DP.dp.toPx() },
                colorA = checkerA,
                colorB = checkerB,
                canvasVoid = canvasVoid,
            )
        }

        val eraserPreset = state.brushPresets.firstOrNull {
            it.id == state.eraserEndPreset && it.eraseMode
        } ?: state.brushPresets.firstOrNull { it.eraseMode } ?: BrushPresets.DEFAULT
        HoverCursor(
            stylus = touch.stylus,
            active = state.toolSelection.kind,
            eraserPreset = eraserPreset,
            canvasToScreenScale = touch.canvasToScreenScale,
            revision = hoverRevision,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                // While a leave is flushing, the chrome is off-limits to
                // every input modality: pointer events hit the closing
                // scrim, and this drops the chrome from focus traversal and
                // the accessibility tree so keyboard/TalkBack cannot reach
                // it either.
                .focusProperties { canFocus = !state.closing }
                .semantics { if (state.closing) invisibleToUser() },
        ) {
            val chromeVisible = state.chrome.focusMode == FocusMode.CHROME
            val chromeAnimationMs = if (ValueAnimator.areAnimatorsEnabled()) {
                CHROME_ANIMATION_MS
            } else {
                0
            }
            val railDirection = if (layout.railSide == Hand.RIGHT) 1 else -1
            val railEnter = if (layout.railMode == RailMode.DOCK) {
                slideInVertically(tween(chromeAnimationMs)) { it } + fadeIn(tween(chromeAnimationMs))
            } else {
                slideInHorizontally(tween(chromeAnimationMs)) { railDirection * it } +
                    fadeIn(tween(chromeAnimationMs))
            }
            val railExit = if (layout.railMode == RailMode.DOCK) {
                slideOutVertically(tween(chromeAnimationMs)) { it } + fadeOut(tween(chromeAnimationMs))
            } else {
                slideOutHorizontally(tween(chromeAnimationMs)) { railDirection * it } +
                    fadeOut(tween(chromeAnimationMs))
            }

            // §5.3's overlay, debug builds only. Drawn first so the back
            // button and the reset pill stay on top of it and reachable — the
            // overlay fills the box and would otherwise swallow their taps.
            //
            // The preference is developer-facing, and release builds never
            // carry the overlay even if a restored DataStore says it is on.
            val engine = session
            if (BuildConfig.DEBUG && state.debugLatency && engine != null) {
                DebugOverlay(
                    perf = engine.perf,
                    prediction = touch.prediction,
                    latency = touch.latency,
                    describe = engine::describeEngine,
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = railEnter,
                exit = railExit,
                modifier = (if (layout.railMode == RailMode.DOCK) {
                    Modifier.align(Alignment.BottomCenter)
                } else if (layout.railSide == Hand.RIGHT) {
                    Modifier.align(Alignment.BottomEnd)
                } else {
                    Modifier.align(Alignment.BottomStart)
                })
                    .semantics { traversalIndex = RAIL_TRAVERSAL }
                    .zIndex(CHROME_Z),
            ) {
                ToolRail(
                layout = layout,
                presets = state.brushPresets,
                paintBrushId = state.paintBrushId,
                eraserBrushId = state.eraserBrushId,
                selection = state.toolSelection,
                hapticsMode = state.hapticsMode,
                onBrushSelected = {
                    viewModel.dismissPanel()
                    viewModel.selectBrush(it)
                },
                onSmudgeSelected = {
                    viewModel.dismissPanel()
                    viewModel.selectSmudge()
                },
                onBlurSelected = {
                    viewModel.dismissPanel()
                    viewModel.selectBlur()
                },
                onFillSelected = {
                    viewModel.dismissPanel()
                    viewModel.selectFill()
                },
                onEyedropperSelected = {
                    viewModel.dismissPanel()
                    viewModel.selectEyedropper()
                },
                onSettingsRequested = {
                    viewModel.togglePanel(CanvasPanel.BRUSH_SETTINGS)
                },
                onFillSettingsRequested = {
                    viewModel.togglePanel(CanvasPanel.FILL_SETTINGS)
                },
                onSizeChanged = viewModel::updateActiveToolSize,
                onOpacityChanged = viewModel::updateActiveToolOpacity,
                onTuningFinished = viewModel::persistBrushTuning,
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(tween(chromeAnimationMs)) { -it } +
                    fadeIn(tween(chromeAnimationMs)),
                exit = slideOutVertically(tween(chromeAnimationMs)) { -it } +
                    fadeOut(tween(chromeAnimationMs)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .semantics { traversalIndex = TOP_STRIP_TRAVERSAL }
                    .zIndex(CHROME_Z),
            ) {
                TopStrip(
                layout = layout,
                undoAvailability = undoAvailability,
                redoAvailability = redoAvailability,
                activeLayer = state.stack.activeIndex + 1,
                brushColor = state.color.current,
                openPanel = state.chrome.openPanel,
                hapticsMode = state.hapticsMode,
                onBack = { viewModel.handleBack(onLeave) },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onUndoLongPress = { historyReadout++ },
                onLayers = { viewModel.togglePanel(CanvasPanel.LAYERS) },
                onColor = { viewModel.togglePanel(CanvasPanel.COLOR) },
                onColorLongPress = {
                    // No colours painted yet: the panel is the honest answer.
                    if (recentColors.isEmpty()) {
                        viewModel.togglePanel(CanvasPanel.COLOR)
                    } else {
                        showRecentSwatches = true
                    }
                },
                onShare = {
                    sharePainting(context, viewModel, ImageEncode.Format.PNG)
                },
                onExportPng = {
                    exportPainting(context, viewModel, ImageEncode.Format.PNG)
                },
                onExportJpeg = {
                    exportPainting(context, viewModel, ImageEncode.Format.JPEG)
                },
                onFocus = viewModel::toggleFocus,
                onRename = viewModel::requestRename,
                onSettings = onSettings,
                )
            }

            // The quick palette: the last colours painted with, one tap away
            // from the strip's swatch. The scrim below it consumes the
            // dismissing tap so it never draws (the panel rule, §4.1).
            if (showRecentSwatches) {
                BackHandler { showRecentSwatches = false }
                // The hoisted scroll state outlives the popover; each open
                // starts at the newest swatches.
                LaunchedEffect(Unit) { recentScroll.scrollTo(0) }
                // The auto-dismiss pauses while the user is scrolling the
                // swatch list, and never runs while a screen reader is
                // active — TalkBack traversal of a row of hex-named swatches
                // cannot fit a fixed window (WCAG 2.2.1). Read fresh on
                // every restart and again after the delay, so enabling
                // TalkBack mid-session is honored.
                val accessibilityManager = context.getSystemService(
                    android.view.accessibility.AccessibilityManager::class.java,
                )
                LaunchedEffect(showRecentSwatches, recentScroll.isScrollInProgress) {
                    if (
                        !recentScroll.isScrollInProgress &&
                        !accessibilityManager.hasActiveScreenReader()
                    ) {
                        // Switch Access (FEEDBACK_GENERIC) is missed by
                        // hasActiveScreenReader but is far slower than a
                        // fixed window; the platform's per-user "time to take
                        // action" scales the wait for exactly that audience.
                        delay(
                            accessibilityManager?.getRecommendedTimeoutMillis(
                                RECENT_POPOVER_MS.toInt(),
                                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS,
                            )?.toLong() ?: RECENT_POPOVER_MS,
                        )
                        // Re-check: TalkBack may have been enabled while the
                        // wait ran (its volume-key shortcut), and yanking the
                        // popover mid-traversal is the exact failure the
                        // guard exists to prevent.
                        if (!accessibilityManager.hasActiveScreenReader()) {
                            showRecentSwatches = false
                        }
                    }
                }
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(RECENT_SCRIM_Z)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { showRecentSwatches = false },
                        )
                        .clearAndSetSemantics {},
                )
                RecentPopover(
                    colors = recentColors,
                    current = state.color.current,
                    scrollState = recentScroll,
                    onSelected = { color ->
                        viewModel.selectBrushColor(color)
                        showRecentSwatches = false
                    },
                )
            }

            // §3.1's long-press readout: the undo depth and the cap, shown
            // under the strip for a moment — the one place the history budget
            // is visible where undo is actually used. An incrementing token
            // rather than a boolean, so a repeat long-press restarts the timer.
            if (historyReadout > 0) {
                LaunchedEffect(historyReadout) {
                    delay(HISTORY_READOUT_MS)
                    historyReadout = 0
                }
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = HISTORY_READOUT_TOP.dp)
                        .zIndex(CHROME_Z),
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.canvas_history_readout,
                            state.historySteps,
                            state.historySteps,
                            Formatter.formatShortFileSize(context, state.historyBytes),
                            Formatter.formatShortFileSize(context, state.historyMaxBytes),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // A live readout while the fingers steer the view: the reset pill
            // only appears once they lift, so the gesture itself owes the
            // zoom/angle feedback. Delayed like the pill, so the navigation
            // blip inside a two-finger tap-undo never flashes it.
            var readoutVisible by remember { mutableStateOf(false) }
            LaunchedEffect(navigating) {
                readoutVisible = false
                if (!navigating) return@LaunchedEffect
                delay(READOUT_APPEAR_DELAY_MS)
                readoutVisible = true
            }
            AnimatedVisibility(
                visible = readoutVisible && navigating,
                enter = fadeIn(tween(chromeAnimationMs)),
                exit = fadeOut(tween(chromeAnimationMs)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = LayoutSpec.TOP_STRIP_DP.dp + READOUT_GAP)
                    .zIndex(CHROME_Z),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 3.dp,
                ) {
                    Text(
                        text = stringResource(
                            R.string.canvas_view_readout,
                            (view.scale * READOUT_PERCENT).roundToInt(),
                            Math.toDegrees(view.rotation.toDouble()).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(
                            horizontal = READOUT_PADDING_H,
                            vertical = READOUT_PADDING_V,
                        ),
                    )
                }
            }

            val resetBottomPadding = when (layout.railMode) {
                RailMode.DOCK -> DOCK_CHROME_HEIGHT.dp
                RailMode.SHORT -> LEDGE_CHROME_HEIGHT.dp
                RailMode.GROUPED, RailMode.FULL -> RESET_EDGE_PADDING.dp
            }
            ResetViewPill(
                view = view,
                density = density.density,
                strokeActivity = state.chrome.strokeActivity,
                onReset = resetView,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = resetBottomPadding),
            )

            // §6.3's storage-full banner: persistent while the state holds,
            // gone with the first successful write.
            val storageFull by viewModel.storageFull.collectAsStateWithLifecycle()
            if (storageFull) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.err_storage_full),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            val fillProgress = state.fillProgress
            if (fillProgress != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = FILL_PROGRESS_BOTTOM.dp)
                        .width(FILL_PROGRESS_WIDTH.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.fill_progress, (fillProgress * 100).toInt()),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(
                            progress = { fillProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = viewModel::cancelFill,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.fill_cancel))
                        }
                    }
                }
            }

            val panel = state.chrome.openPanel
            if (panel != null) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = viewModel::dismissCanvasOverlay,
                        )
                        .clearAndSetSemantics {},
                )
            }

            PanelHost(
                layout = layout,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                announcement = panelAnnouncement(panel, state.toolSelection.kind),
                visibility = if (panel == null) {
                    PanelVisibility.HIDDEN
                } else {
                    PanelVisibility.VISIBLE
                },
                modifier = Modifier.semantics { traversalIndex = PANEL_TRAVERSAL },
            ) {
                CanvasPanelContent(
                    panel = panel,
                    layout = layout,
                    state = state,
                    layerThumbnails = layerThumbnails,
                    viewModel = viewModel,
                    onTextInputFocus = { textInputFocus = it },
                )
            }

            val ledgePreset = ToolSliderPreset.forKind(state.toolSelection.kind)
            if (ledgePreset != null) {
                val ledgeModifier = when (layout.railMode) {
                    RailMode.DOCK -> Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = DOCK_HEIGHT.dp)
                        .fillMaxWidth()
                    RailMode.SHORT -> Modifier
                        .align(
                            if (layout.railSide == Hand.RIGHT) Alignment.BottomStart
                            else Alignment.BottomEnd,
                        )
                        .width(windowWidth - layout.railWidthDp.dp)
                    RailMode.GROUPED, RailMode.FULL -> Modifier
                }
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = slideInVertically(tween(chromeAnimationMs)) { it } +
                        fadeIn(tween(chromeAnimationMs)),
                    exit = slideOutVertically(tween(chromeAnimationMs)) { it } +
                        fadeOut(tween(chromeAnimationMs)),
                    modifier = ledgeModifier
                        .semantics { traversalIndex = SLIDER_TRAVERSAL }
                        .zIndex(CHROME_Z),
                ) {
                    SliderLedge(
                        layout = layout,
                        preset = ledgePreset,
                        hapticsMode = state.hapticsMode,
                        onSizeChanged = viewModel::updateActiveToolSize,
                        onOpacityChanged = viewModel::updateActiveToolOpacity,
                        onTuningFinished = viewModel::persistBrushTuning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            AnimatedVisibility(
                visible = state.chrome.focusMode == FocusMode.FOCUSED,
                enter = fadeIn(tween(chromeAnimationMs)),
                exit = fadeOut(tween(chromeAnimationMs)),
                modifier = Modifier
                    .align(
                        if (layout.railSide == Hand.RIGHT) Alignment.CenterEnd
                        else Alignment.CenterStart,
                    )
                    .zIndex(CHROME_Z),
            ) {
                FocusHandle(
                    side = layout.railSide,
                    onClick = viewModel::showChrome,
                )
            }

            if (state.chrome.hint == HintVisibility.VISIBLE) {
                FirstRunHint(
                    onDismiss = viewModel::dismissCanvasOverlay,
                    modifier = Modifier.zIndex(HINT_Z),
                )
            }
        }

        // 08 §4.8: "Closing…" appears only when the leave checkpoint
        // outlives the 300 ms grace — a fast leave stays a silent pop.
        // Composed OUTSIDE the gated chrome box on purpose: the chrome box
        // drops focus and a11y while closing, and the scrim itself must keep
        // both (it takes keyboard focus and announces the state).
        var closingScrim by remember { mutableStateOf(false) }
        val scrimFocus = remember { FocusRequester() }
        LaunchedEffect(closingScrim) {
            if (closingScrim) runCatching { scrimFocus.requestFocus() }
        }
        LaunchedEffect(state.closing) {
            if (!state.closing) {
                closingScrim = false
                return@LaunchedEffect
            }
            delay(CLOSING_SCRIM_DELAY_MS)
            closingScrim = true
        }
        if (closingScrim) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = CLOSING_SCRIM_ALPHA),
                modifier = Modifier
                    .fillMaxSize()
                    // The scrim is the front-most hit node while visible:
                    // consume every change of every gesture, so no tap,
                    // drag or stroke can reach the chrome or the canvas
                    // mid-flush. Focus and a11y gating for the chrome
                    // lives on the chrome box itself.
                    .focusRequester(scrimFocus)
                    .focusable()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    }
                    .zIndex(CLOSING_SCRIM_Z),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.canvas_closing),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }

    CanvasDialogHost(state, viewModel)
}

@Composable
private fun CanvasPanelContent(
    panel: CanvasPanel?,
    layout: LayoutSpec,
    state: CanvasViewModel.UiState.Ready,
    layerThumbnails: Map<ch.lkmc.bangnidraw.engine.core.LayerId, ch.lkmc.bangnidraw.engine.core.LayerThumbnail>,
    viewModel: CanvasViewModel,
    onTextInputFocus: (TextInputFocus) -> Unit,
) {
    when (panel) {
        CanvasPanel.LAYERS -> LayerPanel(
            canvas = state.canvas,
            stack = state.stack,
            paperColor = state.paperColor,
            layerCap = state.layerCap,
            panelMode = layout.panelMode,
            hapticsMode = state.hapticsMode,
            documentBusy = state.documentBusy,
            feedbackRevision = state.layerFeedbackRevision,
            refusal = state.layerRefusal,
            thumbnails = layerThumbnails,
            onDismiss = viewModel::dismissPanel,
            onSelect = viewModel::selectLayer,
            onAdd = viewModel::addLayer,
            onDelete = viewModel::deleteLayer,
            onDuplicate = viewModel::duplicateLayer,
            onMove = viewModel::moveLayer,
            onMergeDown = viewModel::mergeLayerDown,
            onClear = viewModel::clearLayer,
            onRequestDialog = viewModel::requestDialog,
            onOpacityPreview = viewModel::previewLayerOpacity,
            onOpacityFinished = viewModel::finishLayerOpacity,
            onToggleVisibility = viewModel::toggleLayerVisibility,
            onBlendMode = viewModel::setLayerBlendMode,
            onToggleAlphaLock = viewModel::toggleLayerAlphaLock,
            onToggleLock = viewModel::toggleLayerLock,
            onPaperColor = viewModel::setPaperColor,
        )
        CanvasPanel.COLOR -> ColorPanel(
            state = state.color,
            mixSteps = viewModel::mixingDish,
            mixColor = viewModel::mixingColor,
            onColorSelected = viewModel::selectBrushColor,
            onSwapColors = viewModel::swapBrushColors,
            onMixerChanged = viewModel::setMixerChoice,
            onPaletteSelected = viewModel::selectPalette,
            onCreatePalette = viewModel::createUserPalette,
            onAddToPalette = viewModel::addColorToPalette,
            onReplaceSwatch = viewModel::replacePaletteSwatch,
            onDeleteSwatch = viewModel::deletePaletteSwatch,
            onMoveSwatch = viewModel::movePaletteSwatch,
            onPickSwatch = {
                viewModel.dismissPanel()
                viewModel.selectPaletteSwatchEyedropper(it)
            },
            onDishWellChanged = viewModel::setDishWell,
            onPickDishWell = {
                viewModel.dismissPanel()
                viewModel.selectDishEyedropper(it)
            },
            onTextInputFocus = onTextInputFocus,
            hapticsMode = state.hapticsMode,
        )
        CanvasPanel.BRUSH_SETTINGS -> when (val kind = state.toolSelection.kind) {
            is ToolKind.Brush -> BrushSettingsSheet(
                active = kind.preset,
                presets = state.brushPresets,
                brushColor = state.color.current,
                paperColor = state.paperColor,
                hapticsMode = state.hapticsMode,
                mixerChoice = state.color.mixerChoice,
                onPresetSelected = viewModel::selectBrush,
                onPresetChanged = viewModel::updateActiveBrush,
                onPresetPersisted = viewModel::persistActiveBrush,
                onReset = viewModel::resetActiveBrush,
            )
            is ToolKind.Smudge -> SmudgeSettingsSheet(
                active = kind.params,
                onChanged = viewModel::updateSmudgeParams,
            )
            is ToolKind.Blur -> BlurSettingsSheet(
                active = kind.params,
                onChanged = viewModel::updateBlurParams,
            )
            is ToolKind.Eyedropper -> EyedropperSettingsSheet(
                active = kind.params,
                onChanged = viewModel::updateEyedropperParams,
            )
            is ToolKind.Fill -> FillSettingsSheet(
                active = state.fillParams,
                onChanged = viewModel::updateFillParams,
            )
        }
        CanvasPanel.FILL_SETTINGS -> FillSettingsSheet(
            active = state.fillParams,
            onChanged = viewModel::updateFillParams,
        )
        CanvasPanel.OVERFLOW, null -> Unit
    }
}

/**
 * The strip swatch's long-press palette: the last colours painted with, as
 * 48 dp targets in a wrapping row. Dismissed by its scrim, a selection, or
 * the timeout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.RecentPopover(
    colors: List<Int>,
    current: Int,
    scrollState: ScrollState,
    onSelected: (Int) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = RECENT_POPOVER_TOP.dp)
            .zIndex(CHROME_Z)
            .widthIn(max = RECENT_POPOVER_MAX.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                text = stringResource(R.string.palette_recent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .heightIn(max = RECENT_POPOVER_MAX_HEIGHT.dp)
                    .verticalScroll(scrollState),
            ) {
                for (color in colors) {
                    val selected = color == current
                    val border = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(RECENT_TARGET.dp)
                            .semantics {
                                contentDescription = ColorText.hex(color)
                                this.selected = selected
                            }
                            .clickable { onSelected(color) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(RECENT_VISUAL.dp)
                                .clip(CircleShape)
                                .border(
                                    if (selected) 2.dp else 1.dp,
                                    border,
                                    CircleShape,
                                ),
                        ) {
                            Canvas(Modifier.fillMaxSize()) { drawCircle(Color(color)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whether a screen reader is driving this session: touch exploration
 * (TalkBack's classic mode) or any enabled service that speaks. Deliberately
 * NOT every accessibility service — a password manager or screen dimmer
 * (FEEDBACK_GENERIC) must not freeze the popover's auto-dismiss. Slower
 * non-spoken navigation (Switch Access) is not exempted.
 */
private fun android.view.accessibility.AccessibilityManager?.hasActiveScreenReader(): Boolean =
    this?.let { am ->
        am.isTouchExplorationEnabled ||
            am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN,
            ).isNotEmpty()
    } == true

@Composable
private fun panelAnnouncement(panel: CanvasPanel?, kind: ToolKind? = null): String = when (panel) {
    CanvasPanel.LAYERS -> stringResource(R.string.panel_layers_opened)
    CanvasPanel.COLOR -> stringResource(R.string.panel_color_opened)
    CanvasPanel.BRUSH_SETTINGS -> when (kind) {
        is ToolKind.Smudge -> stringResource(R.string.panel_smudge_opened)
        is ToolKind.Blur -> stringResource(R.string.panel_blur_opened)
        is ToolKind.Eyedropper -> stringResource(R.string.panel_eyedropper_opened)
        is ToolKind.Fill -> stringResource(R.string.panel_fill_opened)
        else -> stringResource(R.string.panel_brush_opened)
    }
    CanvasPanel.FILL_SETTINGS -> stringResource(R.string.panel_fill_opened)
    CanvasPanel.OVERFLOW, null -> ""
}

@Composable
private fun CanvasDialogHost(
    state: CanvasViewModel.UiState.Ready,
    viewModel: CanvasViewModel,
) {
    when (val dialog = state.chrome.dialog) {
        CanvasDialog.RenamePainting -> RenameDialog(
            title = R.string.studio_rename,
            initialValue = state.title,
            onConfirm = viewModel::renamePainting,
            onDismiss = viewModel::dismissDialog,
        )
        is CanvasDialog.RenameLayer -> RenameDialog(
            title = R.string.layer_rename_title,
            initialValue = dialog.currentName,
            onConfirm = {
                viewModel.dismissDialog()
                viewModel.renameLayer(dialog.index, it)
            },
            onDismiss = viewModel::dismissDialog,
        )
        is CanvasDialog.MergeLayers -> ConfirmationDialog(
            title = stringResource(R.string.layer_merge_title),
            body = stringResource(R.string.layer_merge_body),
            onConfirm = {
                viewModel.dismissDialog()
                viewModel.mergeLayerDown(dialog.index)
            },
            onDismiss = viewModel::dismissDialog,
        )
        CanvasDialog.FlattenLayers -> ConfirmationDialog(
            title = pluralStringResource(
                R.plurals.layer_flatten_title,
                state.stack.size,
                state.stack.size,
            ),
            body = stringResource(R.string.layer_flatten_body),
            onConfirm = {
                viewModel.dismissDialog()
                viewModel.flattenLayers()
            },
            onDismiss = viewModel::dismissDialog,
        )
        null -> Unit
    }
}

@Composable
private fun RenameDialog(
    title: Int,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = { onConfirm(draft) },
            ) {
                Text(stringResource(R.string.studio_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.new_canvas_cancel))
            }
        },
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.layer_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.layer_cancel))
            }
        },
    )
}

private fun sharePainting(
    context: android.content.Context,
    viewModel: CanvasViewModel,
    format: ImageEncode.Format,
) {
    viewModel.share(
        format = format,
        onReady = { uri, mime ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null))
        },
        onFailure = {
            Toast.makeText(context, R.string.studio_save_failed, Toast.LENGTH_SHORT).show()
        },
    )
}

private fun exportPainting(
    context: android.content.Context,
    viewModel: CanvasViewModel,
    format: ImageEncode.Format,
) {
    viewModel.export(format) { outcome ->
        Toast.makeText(
            context,
            if (outcome == GalleryExportOutcome.SUCCESS) {
                R.string.studio_saved_to_gallery
            } else {
                R.string.studio_save_failed
            },
            Toast.LENGTH_SHORT,
        ).show()
    }
}

@Composable
private fun CanvasImmersiveEffect() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose {}

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var current: android.content.Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable
private fun toolName(tool: ToolKind): String = when (tool) {
    is ToolKind.Brush -> if (tool.preset.eraseMode) {
        stringResource(R.string.tool_eraser)
    } else {
        brushPresetName(tool.preset)
    }
    is ToolKind.Smudge -> stringResource(R.string.tool_smudge)
    is ToolKind.Blur -> stringResource(R.string.tool_blur)
    is ToolKind.Fill -> stringResource(R.string.tool_fill)
    is ToolKind.Eyedropper -> stringResource(R.string.tool_eyedropper)
}

/** 8 dp squares, per `03-canvas-engine.md` §3.2 step 1. */
private const val CHECKER_DP = 8
private const val FILL_PROGRESS_BOTTOM = 24
private const val FILL_PROGRESS_WIDTH = 240
private const val DOCK_HEIGHT = 56
private const val DOCK_CHROME_HEIGHT = 120
private const val LEDGE_CHROME_HEIGHT = 64
private const val RESET_EDGE_PADDING = 16
private const val EXCLUSION_GAP_DP = 16
private const val CHROME_Z = 2f
private const val HINT_Z = 3f
private const val CLOSING_SCRIM_Z = 5f
private const val CLOSING_SCRIM_ALPHA = 0.55f
// 08 §4.8 fixes the scrim threshold at 300 ms; the leave() grace period in
// CanvasViewModel (LEAVE_HANDOFF_GRACE_MS) is the stranded-scrim reset, not
// a scrim threshold — a cancelled back gesture may therefore flash the scrim
// briefly before the canvas pops back, which is honest feedback, not a bug.
private const val CLOSING_SCRIM_DELAY_MS = 300L
private const val RESET_DAMPING_RATIO = 0.8f
private const val CHROME_ANIMATION_MS = 180
private const val RECENT_POPOVER_MS = 4_000L
private const val RECENT_SCRIM_Z = CHROME_Z
private const val RECENT_POPOVER_TOP = 56
private const val RECENT_POPOVER_MAX = 336
private const val RECENT_POPOVER_MAX_HEIGHT = 240
private const val RECENT_TARGET = 48
private const val RECENT_VISUAL = 34
private const val HISTORY_READOUT_MS = 2_000L
private const val HISTORY_READOUT_TOP = 56
private val READOUT_GAP = 8.dp
private const val READOUT_PERCENT = 100f
private const val READOUT_APPEAR_DELAY_MS = 150L
private val READOUT_PADDING_H = 12.dp
private val READOUT_PADDING_V = 6.dp
private const val TOP_STRIP_TRAVERSAL = 0f
private const val RAIL_TRAVERSAL = 1f
private const val SLIDER_TRAVERSAL = 2f
private const val PANEL_TRAVERSAL = 3f
private const val CANVAS_TRAVERSAL = 4f

private val VIEW_TRANSFORM_SAVER = Saver<ViewTransform, List<Float>>(
    save = { listOf(it.scale, it.rotation, it.tx, it.ty) },
    restore = { values ->
        if (values.size != VIEW_TRANSFORM_FIELD_COUNT) return@Saver null
        ViewTransform(values[0], values[1], values[2], values[3])
    },
)

private const val VIEW_TRANSFORM_FIELD_COUNT = 4

/**
 * The stroke in flight, plus the colour it paints with.
 *
 * Plain fields rather than Compose state: these change several hundred times a
 * second on the input path and nothing composes from them, so making them
 * observable would recompose the whole screen once per pen sample. The colour
 * is frozen into its StrokeSpec at pen-down.
 */
internal class StrokeUiState {
    var driver: StrokeDriver? = null
    var readModifyWrite = false
    var colorUsage = StrokeColorUsage.IGNORE

    var pickParams: EyedropperParams? = null
    var fillParams: FillParams? = null
    var fillTouch = false
    var fillStarted = false
    var temporaryReason: TemporaryReason? = null
    var pickGeneration: Long = 0

    /** Caps the eyedropper's per-sample GL reads; reset at every pen-down. */
    val pickGate = EyedropperSampleGate(DEFAULT_PICK_INTERVAL_MS)

    /**
     * The session [driver] was opened against, so every later call reaches
     * *that* engine rather than whichever one happens to be current.
     *
     * `session` is Compose state fed by `CanvasSurface`'s `AndroidView`, which
     * is wrapped in `key(canvas)`: a canvas-size change builds a new
     * `SurfaceView`, a new `EngineSession`, and releases the old one. Reading
     * `session` in a sample handler therefore answers "which engine exists
     * now", not "which engine has this stroke open" — and the difference is
     * invisible until the two diverge.
     *
     * It cannot diverge yet: only the New Canvas dialog of step 3c changes the
     * canvas size, and nothing else re-runs the factory. Paired now anyway,
     * because the day that dialog lands there is nothing to notice the gap —
     * the engine's own guards make the mismatch silent rather than loud
     * (`stampDabs` and `endStroke` both no-op when no stroke is open), so it
     * would surface as dabs quietly going nowhere.
     */
    var engine: EngineSession? = null

    /** The stroke's source, fixed at pen-down and carried to every sample. */
    var source: StrokeSource = StrokeSource.STYLUS

    /** Straight sRGB, 0..1 — `dab.vert`'s `u_color`. */
    var colorArgb: Int = OPAQUE_BLACK
        private set
    var colorR = 0f
    var colorG = 0f
    var colorB = 0f

    fun setColor(argb: Int) {
        colorArgb = argb
        colorR = ((argb ushr RED_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX
        colorG = ((argb ushr GREEN_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX
        colorB = (argb and CHANNEL_MASK) / CHANNEL_MAX
    }

    // Seeded from the clock rather than from 1: a fixed start makes the first
    // stroke of every session wobble identically to the first stroke of the
    // last one. Nothing in the suite depends on the sequence — StrokeDriverTest
    // passes its own seeds — so determinism here buys nothing and costs the
    // jitter its independence across launches.
    private var seed = System.nanoTime()

    /**
     * A fresh seed per stroke keeps a jittering brush from tracing the same
     * wobble every time. Procedural grain remains fixed to the canvas.
     */
    fun nextSeed(): Long = seed++

    /** Invalidates eyedropper callbacks already queued on the GL thread. */
    fun nextPickGeneration(): Long = ++pickGeneration

    private companion object {
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val CHANNEL_MASK = 0xFF
        const val CHANNEL_MAX = 255f
        const val OPAQUE_BLACK = 0xFF000000.toInt()

        /** One eyedropper read per frame; see [EyedropperSampleGate]. */
        const val DEFAULT_PICK_INTERVAL_MS = EyedropperSampleGate.DEFAULT_INTERVAL_MS
    }
}
