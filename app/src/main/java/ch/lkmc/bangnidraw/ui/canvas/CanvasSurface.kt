package ch.lkmc.bangnidraw.ui.canvas

import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DeviceMemory
import ch.lkmc.bangnidraw.engine.core.EngineUpdate
import ch.lkmc.bangnidraw.engine.core.EngineUpdatePolicy
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.input.CanvasTouchHandler
import kotlin.math.roundToInt

/**
 * The `SurfaceView` the engine draws into, hosted in Compose
 * (`docs/plan/02-architecture.md` §2.6).
 *
 * An `AndroidView` rather than a Compose `Canvas` because the engine owns an
 * EGL context and a GL thread: `GLFrontBufferedRenderer` takes a
 * `SurfaceView`, and the low-latency front-buffered path of roadmap 2.5 has no
 * Compose equivalent at all.
 *
 * The [EngineSession] is created in the factory — it needs the `SurfaceView` —
 * and handed out through [onSession] so the screen (and later the ViewModel)
 * can talk to it. It is released on dispose, which is the composable's
 * lifetime, not the ViewModel's.
 */
@Composable
internal fun CanvasSurface(
    canvas: CanvasSize,
    stack: LayerStack,
    paperColor: Int,
    view: ViewTransform,
    canvasDescription: String,
    undoLabel: String,
    redoLabel: String,
    undoAvailability: ActionAvailability,
    redoAvailability: ActionAvailability,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    gestureExclusionSide: Hand?,
    gestureExclusionWidthDp: Int,
    modifier: Modifier = Modifier,
    debugBuild: Boolean,
    onSession: (EngineSession?) -> Unit,
    /** Attached to the SurfaceView as its touch and hover listener (roadmap 2.4a). */
    touchHandler: CanvasTouchHandler? = null,
    /**
     * §10.1's readback sink, handed to the session at construction — it wires
     * the `Readback` machinery, so it cannot arrive later (roadmap 3a).
     */
    onTile: ((ch.lkmc.bangnidraw.engine.core.LayerId, ch.lkmc.bangnidraw.engine.core.TileKey, Int, java.nio.ByteBuffer) -> Unit)? = null,
    /** See EngineSession: shared so a recreated session cannot reset it. */
    revisions: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0),
) {
    val context = LocalContext.current
    val budget = remember(canvas) { MemoryBudget.compute(readDeviceMemory(context), canvas) }
    val sessionHolder = remember { arrayOfNulls<EngineSession>(1) }
    val surfaceHolder = remember { arrayOfNulls<SurfaceView>(1) }
    val appliedStack = remember { arrayOfNulls<LayerStack>(1) }
    val appliedPaperColor = remember { arrayOfNulls<Int>(1) }
    val appliedView = remember { arrayOfNulls<ViewTransform>(1) }
    val appliedTouchHandler = remember { arrayOfNulls<CanvasTouchHandler>(1) }
    val density = context.resources.displayMetrics.density
    val historyActions = availableCanvasHistoryActions(undoAvailability, redoAvailability)
    val accessibility = Modifier.semantics {
        contentDescription = canvasDescription
        customActions = historyActions.map { action ->
            when (action) {
                CanvasHistoryAction.UNDO -> CustomAccessibilityAction(undoLabel) {
                    onUndo()
                    true
                }

                CanvasHistoryAction.REDO -> CustomAccessibilityAction(redoLabel) {
                    onRedo()
                    true
                }
            }
        }
    }

    // `key(canvas)` because the session takes its CanvasSize and its budget at
    // construction and has no resize path: without this, the New Canvas dialog
    // (roadmap step 3) changing the size would leave the engine rendering the
    // old dimensions against a pool sized from a freshly computed, mismatched
    // budget. The `remember(canvas)` above already anticipates the change; this
    // is what makes the SurfaceView, the session and the budget move together.
    key(canvas) {
    AndroidView(
        modifier = modifier
            .then(accessibility)
            .onSizeChanged { size ->
                touchHandler?.setViewport(canvas, size.width, size.height)
                surfaceHolder[0]?.let { surface ->
                    updateGestureExclusion(
                        surface,
                        size.width,
                        size.height,
                        density,
                        gestureExclusionSide,
                        gestureExclusionWidthDp,
                    )
                }
            },
        factory = { ctx ->
            SurfaceView(ctx).also { surface ->
                surfaceHolder[0] = surface
                val session = EngineSession(
                    surface = surface,
                    canvas = canvas,
                    budget = budget,
                    assets = ctx.assets,
                    debugBuild = debugBuild,
                    onTile = onTile,
                    revisions = revisions,
                )
                sessionHolder[0] = session
                session.configure(stack, paperColor, view)
                appliedStack[0] = stack
                appliedPaperColor[0] = paperColor
                appliedView[0] = view
                onSession(session)
            }
        },
        update = { surface ->
            val previousTouchHandler = appliedTouchHandler[0]
            if (previousTouchHandler !== touchHandler) {
                previousTouchHandler?.detach()
                appliedTouchHandler[0] = touchHandler
            }

            // Size callbacks do not replay when only the handler changes.
            touchHandler?.setViewport(canvas, surface.width, surface.height)

            // Attached here, not in `factory`: `factory` runs once per view
            // instance, but CanvasScreen builds the handler with
            // `remember(density, view0)`, so a density or window change makes a
            // NEW handler that the SurfaceView would never hear about — it
            // would keep dispatching to the stale one while reset-view drove
            // the new one. These setters replace rather than accumulate, so
            // calling them on every recomposition is idempotent.
            surface.setOnTouchListener(touchHandler)
            surface.setOnHoverListener(touchHandler)
            surface.setOnGenericMotionListener(touchHandler)
            updateGestureExclusion(
                surface,
                surface.width,
                surface.height,
                density,
                gestureExclusionSide,
                gestureExclusionWidthDp,
            )

            // A multi-buffer redraw hides live front-buffer ink. Only changed
            // document values may cross this recomposition boundary.
            sessionHolder[0]?.let { session ->
                if (EngineUpdatePolicy.decide(appliedStack[0], stack) == EngineUpdate.APPLY) {
                    appliedStack[0] = stack
                    session.setStack(stack)
                }
                if (
                    EngineUpdatePolicy.decide(appliedPaperColor[0], paperColor) ==
                    EngineUpdate.APPLY
                ) {
                    appliedPaperColor[0] = paperColor
                    session.setPaperColor(paperColor)
                }
                if (EngineUpdatePolicy.decide(appliedView[0], view) == EngineUpdate.APPLY) {
                    appliedView[0] = view
                    session.setView(view)
                }
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            appliedTouchHandler[0]?.detach()
            appliedTouchHandler[0] = null
            onSession(null)
            sessionHolder[0]?.release()
            sessionHolder[0] = null
            surfaceHolder[0] = null
        }
    }
    }
}

/** Android honors at most 200 dp of vertical exclusion per edge. */
private fun updateGestureExclusion(
    surface: SurfaceView,
    width: Int,
    height: Int,
    density: Float,
    side: Hand?,
    exclusionWidthDp: Int,
) {
    if (side == null || width <= 0 || height <= 0) {
        ViewCompat.setSystemGestureExclusionRects(surface, emptyList())
        return
    }

    val exclusionWidth = (exclusionWidthDp * density).roundToInt().coerceIn(0, width)
    val exclusionHeight = (MAX_EXCLUSION_HEIGHT_DP * density).roundToInt().coerceAtMost(height)
    val top = (height - exclusionHeight) / 2
    val left = if (side == Hand.LEFT) 0 else width - exclusionWidth
    ViewCompat.setSystemGestureExclusionRects(
        surface,
        listOf(Rect(left, top, left + exclusionWidth, top + exclusionHeight)),
    )
}

/**
 * The platform numbers [MemoryBudget] needs, read once.
 *
 * `glMaxArrayLayers` and `glMaxTextureSize` are left at 0 here: they need a GL
 * context, which does not exist until the session's first frame, and
 * `MemoryBudget` documents 0 as "no context yet" and falls back to the page
 * size. The budget is therefore the conservative one — which is the right way
 * round, since it is used to size a pool that is about to be created.
 */
internal fun readDeviceMemory(context: Context): DeviceMemory {
    val am = context.getSystemService<ActivityManager>()
    val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
    return DeviceMemory(
        // A device that reports nothing is treated as the smallest one the
        // budget has a band for, rather than as a device with no memory —
        // `DeviceMemory` refuses a non-positive total outright.
        totalMemBytes = if (info.totalMem > 0) info.totalMem else MIN_ASSUMED_TOTAL_MEM,
        isLowRamDevice = am?.isLowRamDevice ?: true,
        largeMemoryClassMb = am?.largeMemoryClass ?: 0,
        glMaxArrayLayers = 0,
        glMaxTextureSize = 0,
    )
}

/** 1 GiB — the smallest device this app targets, used only if the platform reports nothing. */
private const val MIN_ASSUMED_TOTAL_MEM = 1L shl 30
private const val MAX_EXCLUSION_HEIGHT_DP = 200
