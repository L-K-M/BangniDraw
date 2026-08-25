package ch.lkmc.bangnidraw.ui.canvas

import android.app.ActivityManager
import android.content.Context
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DeviceMemory
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.ViewTransform

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
fun CanvasSurface(
    canvas: CanvasSize,
    stack: LayerStack,
    paperColor: Int,
    view: ViewTransform,
    modifier: Modifier = Modifier,
    debugBuild: Boolean,
    onSession: (EngineSession?) -> Unit,
) {
    val context = LocalContext.current
    val budget = remember(canvas) { MemoryBudget.compute(readDeviceMemory(context), canvas) }
    val sessionHolder = remember { arrayOfNulls<EngineSession>(1) }

    // `key(canvas)` because the session takes its CanvasSize and its budget at
    // construction and has no resize path: without this, the New Canvas dialog
    // (roadmap step 3) changing the size would leave the engine rendering the
    // old dimensions against a pool sized from a freshly computed, mismatched
    // budget. The `remember(canvas)` above already anticipates the change; this
    // is what makes the SurfaceView, the session and the budget move together.
    key(canvas) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also { surface ->
                val session = EngineSession(surface, canvas, budget, debugBuild)
                sessionHolder[0] = session
                session.setStack(stack)
                session.setPaperColor(paperColor)
                session.setView(view)
                onSession(session)
            }
        },
        update = {
            // Compose recomposes on every state change; the engine only needs
            // to hear about the ones that change what it draws. Each setter
            // hops to the GL thread and requests one redraw, and the renderer
            // ignores a value equal to what it holds.
            sessionHolder[0]?.let { session ->
                session.setStack(stack)
                session.setPaperColor(paperColor)
                session.setView(view)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            onSession(null)
            sessionHolder[0]?.release()
            sessionHolder[0] = null
        }
    }
    }
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
private fun readDeviceMemory(context: Context): DeviceMemory {
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
