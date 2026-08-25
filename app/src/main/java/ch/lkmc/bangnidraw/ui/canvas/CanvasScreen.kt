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
    val density = LocalDensity.current
    val view0 = LocalView.current

    // Roadmap 2.4a: real two-finger navigation. The handler owns the view
    // transform while a gesture is running and reports it back here.
    val touch = remember(density) {
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
            },
        )
    }
    val checkerA = MaterialTheme.colorScheme.surface.toArgb()
    val checkerB = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    LaunchedEffect(Unit) { touch.setView(view) }

    Box(modifier = Modifier.fillMaxSize()) {
        CanvasSurface(
            canvas = canvas,
            stack = stack,
            paperColor = PAPER_WHITE,
            view = view,
            modifier = Modifier.fillMaxSize(),
            debugBuild = BuildConfig.DEBUG,
            onSession = { attached -> session = attached },
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

