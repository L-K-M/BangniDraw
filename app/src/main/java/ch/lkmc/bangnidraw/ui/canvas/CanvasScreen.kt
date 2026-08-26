package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import ch.lkmc.bangnidraw.BuildConfig
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.input.CanvasInputHost
import ch.lkmc.bangnidraw.input.CanvasTouchHandler
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.StrokeDriver
import ch.lkmc.bangnidraw.engine.core.StrokeMode
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.ViewTransform

/**
 * The Canvas: where one painting is painted (PLAN.md §5).
 *
 * Roadmap 2.3b: the engine now draws. The paper fills the fitted canvas rect
 * through the real `view ∘ fit`, and the reset-view pill springs the view back
 * to identity.
 *
 * **No touch navigation yet.** Every gesture component — `CanvasTouchHandler`,
 * `GestureArbiter`, `PalmRejection`, `StylusState` — is roadmap 2.4, and
 * `07-input-and-stylus.md` §2 makes `CanvasTouchHandler` the single owner of
 * `MotionEvent`, so pulling half of it forward would split one coherent area
 * across two PRs. The scaffold's `detectTransformGestures` is gone rather than
 * left in place: it drove a Compose drawing, and leaving it wired to the
 * engine would be a second `MotionEvent` owner — exactly what 2.4 must not
 * inherit. The view is driven programmatically here, which is what the pill
 * and the debug nudge below are for.
 *
 * The document is a single empty layer on white, held in composable state:
 * `ProjectStore` and the real document model arrive in roadmap step 3.
 */
@Composable
fun CanvasScreen(onBack: () -> Unit) {
    var view by remember { mutableStateOf(ViewTransform()) }
    val canvas = remember { CanvasSize(DEFAULT_EDGE, DEFAULT_EDGE) }
    val stack = remember {
        LayerStack(
            layers = listOf(Layer(LayerProps(id = LayerId("layer-1"), name = "Layer 1"))),
            activeIndex = 0,
            nextName = 2,
        )
    }
    var session by remember { mutableStateOf<EngineSession?>(null) }

    // The stroke in flight. Plain vars, not Compose state: they change several
    // hundred times a second on the input path and nothing draws from them, so
    // making them observable would recompose the whole screen per pen sample.
    val strokeState = remember { StrokeUiState() }
    val density = LocalDensity.current
    val view0 = LocalView.current

    // Roadmap 2.4a: real two-finger navigation. The handler owns the view
    // transform while a gesture is running and reports it back here.
    // view0 is in the key because the host below captures it for the snap
    // haptic: a composition that moved to a different View would otherwise keep
    // ticking the old one. Safe now that CanvasSurface re-attaches on update.
    val touch = remember(density, view0) {
        CanvasTouchHandler(
            density = density.density,
            host = object : CanvasInputHost {
                override fun onViewChanged(next: ViewTransform) { view = next }
                override fun onRotationSnapped() {
                    // A single tick as the canvas clicks to straight (§7).
                    view0.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                // Undo and redo arrive with the history journal in step 3; the
                // gestures are recognised now so the arbiter's behaviour is not
                // written twice, and doing nothing is honest until then.
                override fun onUndoRequested() = Unit
                override fun onRedoRequested() = Unit
                override fun onColorPick(x: Float, y: Float) = Unit

                // Roadmap 2.4b: the stroke reaches pixels. Dabs land in a
                // stroke buffer and merge into the layer on pen-up (§7.1,
                // §7.4), so the mark appears when the pen lifts. The live
                // front-buffered preview of §7.5 is 2.5's.
                override fun onStrokeBegin(pointerId: Int, source: StrokeSource) {
                    // A begin while one is already open means the previous
                    // stroke's end was lost — a gesture transition, a torn-down
                    // session. Cancel it rather than orphaning the driver and
                    // opening a second stroke on the engine while the first is
                    // still live (§4: a cancelled stroke leaves no trace).
                    strokeState.driver?.let { stale ->
                        strokeState.driver = null
                        stale.cancel()
                        // The engine the stale stroke was opened on, which is
                        // not necessarily the current one.
                        strokeState.engine?.cancelStroke()
                        strokeState.engine = null
                    }
                    val engine = session ?: return
                    val active = stack.layers.getOrNull(stack.activeIndex) ?: return
                    val preset = BrushPresets.DEFAULT
                    val erasing = preset.eraseMode || source == StrokeSource.ERASER_END
                    // A new seed per stroke: DabGenerator derives every dab's
                    // jitter and grain phase from it, so one shared seed would
                    // make every stroke of a jittering brush identical.
                    val driver = StrokeDriver(preset, seed = strokeState.nextSeed(), zoom = view.scale)
                    strokeState.driver = driver
                    strokeState.engine = engine
                    // Carried for every later sample. onStrokeBegin inspects
                    // `source` to pick erase mode, so passing a hardcoded
                    // STYLUS to the driver afterwards would report an eraser or
                    // a finger as a pen for the whole rest of the stroke.
                    strokeState.source = source
                    val spec = StrokeSpec(
                        layerId = active.id,
                        mode = if (erasing) StrokeMode.ERASE else StrokeMode.PAINT,
                        // The ceiling is only known once the stroke has ended —
                        // it is the maximum pressure actually used — so the
                        // spec sent at pen-down carries the preset's own
                        // opacity and endStroke re-sends the measured one.
                        opacity = preset.opacity,
                        alphaLock = active.props.alphaLock,
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
                    val driver = strokeState.driver ?: return
                    // The stroke's own engine, not `session`. Identity, not
                    // nullability: a replaced session is non-null and would
                    // accept these calls while having no stroke open.
                    val engine = strokeState.engine ?: return
                    val batch = engine.acquireDabBatch() ?: return
                    val emitted = if (driver.isActive) {
                        driver.sample(x, y, pressure, tilt, orientation, timeNs, strokeState.source, batch)
                    } else {
                        driver.begin(x, y, pressure, tilt, orientation, timeNs, strokeState.source, batch)
                    }
                    if (emitted == 0) engine.releaseDabBatch(batch) else engine.stampDabs(batch)
                }

                override fun onStrokeEnd(pointerId: Int) {
                    val driver = strokeState.driver ?: return
                    // Cleared before the session check, so no early return can
                    // leave a dead stroke's driver installed for the next
                    // sample to feed.
                    strokeState.driver = null
                    val engine = strokeState.engine
                    strokeState.engine = null
                    if (engine == null) {
                        driver.cancel()
                        return
                    }
                    val batch = engine.acquireDabBatch()
                    if (batch != null) {
                        if (driver.end(batch) == 0) engine.releaseDabBatch(batch)
                        else engine.stampDabs(batch)
                    } else {
                        driver.cancel()
                    }
                    engine.endStroke()
                }

                override fun onStrokeCancel() {
                    strokeState.driver?.cancel()
                    strokeState.driver = null
                    strokeState.engine?.cancelStroke()
                    strokeState.engine = null
                }
            },
        )
    }
    val checkerA = MaterialTheme.colorScheme.surface.toArgb()
    val checkerB = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    // Keyed on the handler, not Unit: a recreated handler starts from an
    // identity transform, and without re-seeding its first gesture would
    // measure from the wrong baseline and jump.
    LaunchedEffect(touch) { touch.setView(view) }

    Box(modifier = Modifier.fillMaxSize()) {
        CanvasSurface(
            canvas = canvas,
            stack = stack,
            paperColor = PAPER_WHITE,
            view = view,
            modifier = Modifier.fillMaxSize(),
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
                // trailing move and cancel events — where the replacement half
                // waits on step 3.
                //
                // The driver is cancelled but `cancelStroke` is deliberately
                // NOT called on the old engine: it has been released, and
                // `stampDabs`/`endStroke`/`cancelStroke` queue through
                // `frontBuffered.execute` with no validity guard. A block that
                // never runs also never returns its `DabRing` slot, so a
                // handful of those would strand the ring and every later
                // `acquireDabBatch` would fail. §4 makes dropping it correct:
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
                strokeState.driver = null
                strokeState.engine = null
                stale?.cancel()
                session = attached
            },
            touchHandler = touch,
        )

        // Not in `onSession`: that fires once, from the AndroidView factory, so
        // a dark-mode toggle kept the old theme's checkerboard until the screen
        // was torn down and a density change kept the old square size. These
        // three recompose; the engine has to be told.
        LaunchedEffect(session, checkerA, checkerB, density) {
            session?.setCheckerboard(
                checkerPx = with(density) { CHECKER_DP.dp.toPx() },
                colorA = checkerA,
                colorB = checkerB,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.canvas_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (!view.isIdentity) {
                FilledTonalButton(
                    onClick = {
                        view = ViewTransform()
                        // The handler keeps the un-snapped angle §7 accumulates
                        // separately; without this the next gesture would start
                        // measuring from the old one and the canvas would jump.
                        touch.setView(view)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Text(stringResource(R.string.canvas_reset_view))
                }
            }
        }
    }
}

/** Opaque white, the paper of a new sketch until the New Canvas dialog lands. */
private const val PAPER_WHITE = 0xFFFFFFFF.toInt()

/** 8 dp squares, per `03-canvas-engine.md` §3.2 step 1. */
private const val CHECKER_DP = 8

/** A square canvas until the New Canvas dialog can choose one (roadmap step 3). */
private const val DEFAULT_EDGE = 2048


/**
 * The stroke in flight, plus the colour it paints with.
 *
 * Plain fields rather than Compose state: these change several hundred times a
 * second on the input path and nothing composes from them, so making them
 * observable would recompose the whole screen once per pen sample. The colour
 * becomes a real palette selection with the tool UI of roadmap step 3; until
 * then a stroke is black, which is enough for 2.4b's device check and honest
 * about what exists.
 */
internal class StrokeUiState {
    var driver: StrokeDriver? = null

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
     * It cannot diverge yet: only the New Canvas dialog of step 3 changes the
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
    var colorR = 0f
    var colorG = 0f
    var colorB = 0f

    // Seeded from the clock rather than from 1: a fixed start makes the first
    // stroke of every session wobble identically to the first stroke of the
    // last one. Nothing in the suite depends on the sequence — StrokeDriverTest
    // passes its own seeds — so determinism here buys nothing and costs the
    // grain its independence across launches.
    private var seed = System.nanoTime()

    /**
     * A fresh seed per stroke. `DabGenerator` derives every dab's jitter and
     * grain phase from it, so one shared seed would make every stroke of a
     * jittering brush trace the same wobble.
     */
    fun nextSeed(): Long = seed++
}
