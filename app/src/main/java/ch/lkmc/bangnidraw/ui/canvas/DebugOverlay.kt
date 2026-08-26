package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.bangnidraw.engine.core.LatencyTrace
import ch.lkmc.bangnidraw.engine.core.PerfStats
import ch.lkmc.bangnidraw.engine.core.PredictionGate
import kotlinx.coroutines.delay

/**
 * `docs/plan/10-performance.md` §5.3's debug overlay, and
 * `07-input-and-stylus.md` §8's real-vs-predicted plot.
 *
 * Two jobs in one surface, because they answer the same question from
 * different ends. The **numbers** say whether the frame stayed inside
 * `03-canvas-engine.md` §11's budgets — dab stamping ≤ 1 ms, the dirty-rect
 * recomposite ≤ 2 ms. The **points** say whether the predicted tail is worth
 * having: §8 declines to decide that from first principles ("keep prediction on
 * for all refresh rates, including 120 Hz, **and measure**") and names this
 * overlay as the measurement.
 *
 * **Sampled at 4 Hz, not recomposed per frame.** §5.3 says so, and the reason
 * is that this thing exists to observe a hot path without perturbing it: a
 * Compose recomposition per front-buffered frame would put layout and text
 * measurement on the main thread at up to 240 Hz, next to the input handling
 * whose latency is what is being measured. Four times a second is faster than a
 * person reads and slow enough to be free.
 *
 * Debug builds only, gated by the caller. `07-input-and-stylus.md` §8 and the
 * roadmap both call the switch `Prefs.debugLatency`; no `Prefs` object exists
 * yet — it arrives with step 3's persistence — so the gate today is
 * `BuildConfig.DEBUG`, the same one `CanvasSurface` already threads through.
 * Named here so the rename has one place to happen.
 */
@Composable
fun DebugOverlay(
    perf: PerfStats,
    prediction: PredictionGate,
    latency: LatencyTrace,
    modifier: Modifier = Modifier,
    describe: () -> String,
) {
    var frame by remember { mutableStateOf(OverlayFrame()) }
    LaunchedEffect(perf, prediction, latency) {
        while (true) {
            frame = OverlayFrame(
                stampMs = perf.stampMs,
                stampMsMax = perf.stampMsMax,
                compositeMs = perf.compositeMs,
                compositeMsMax = perf.compositeMsMax,
                commitMs = perf.commitMs,
                dabsPerFrame = perf.dabsPerFrame,
                frames = perf.frames,
                tilesResident = perf.tilesResident,
                tilesBudget = perf.tilesBudget,
                predictionErrorPx = prediction.error,
                predictionEnabled = prediction.enabled,
                pool = describe(),
            )
            delay(SAMPLE_MS)
        }
    }

    // Read HERE, in this composable's own scope, and not only inside the `Text`
    // below. Compose restarts the smallest scope that read a state value, so a
    // read confined to the `Column`'s content lambda restarts the Column alone
    // — leaving the `Canvas` sibling holding its original draw lambda, and the
    // plot frozen on whatever the trace held at first composition. Numbers
    // ticking over four times a second beside a picture that never moves is the
    // most misleading thing this overlay could do. Reading it here recomposes
    // the whole function, which is what redraws both.
    val sample = frame

    Box(modifier = modifier.fillMaxSize()) {
        // The points first, so the text sits on top of them rather than under.
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read straight from the trace rather than snapshotting it into
            // `sample`: these are main-thread objects and this lambda is
            // rebuilt by the same 4 Hz recomposition, so copying 64 pairs into
            // the state object would buy nothing.
            //
            // What makes that rebuild happen is the `val sample = frame` above:
            // recomposing this function hands `Canvas` a new draw lambda, and a
            // new lambda invalidates the draw. Nothing inside here has to touch
            // the sample.
            for (i in 0 until latency.size) {
                // Newest at full strength, oldest nearly gone — which is what
                // makes a drifting predictor legible as drift rather than as a
                // cloud. `latency` is indexed newest-first for exactly this.
                val fade = 1f - i.toFloat() / latency.size
                val px = latency.predictedXAt(i)
                val py = latency.predictedYAt(i)
                val ax = latency.actualXAt(i)
                val ay = latency.actualYAt(i)
                // Window px, and this Canvas fills the same box the SurfaceView
                // does, so they are already in its coordinate space — §8's
                // threshold is in screen px for the same reason.
                drawLine(
                    color = Color(0xFFFF6D00).copy(alpha = 0.9f * fade),
                    start = Offset(ax, ay),
                    end = Offset(px, py),
                    strokeWidth = 2f,
                )
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.9f * fade),
                    radius = 3f,
                    center = Offset(px, py),
                )
            }
        }

        Column(
            modifier = Modifier
                .background(Color(0xCC000000))
                .padding(8.dp),
        ) {
            Text(
                text = sample.render(),
                color = Color(0xFFE0E0E0),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/**
 * One 4 Hz sample, as an immutable value.
 *
 * A snapshot rather than reading [PerfStats] during composition: its fields are
 * written by the GL thread and would otherwise be read at whatever moment
 * Compose happened to measure each `Text`, so two lines of the same overlay
 * could describe different frames. Copying nine primitives once per sample is
 * cheaper than explaining that inconsistency to whoever reads it next.
 */
private data class OverlayFrame(
    val stampMs: Float = 0f,
    val stampMsMax: Float = 0f,
    val compositeMs: Float = 0f,
    val compositeMsMax: Float = 0f,
    val commitMs: Float = 0f,
    val dabsPerFrame: Int = 0,
    val frames: Int = 0,
    val tilesResident: Int = 0,
    val tilesBudget: Int = 0,
    val predictionErrorPx: Float = 0f,
    val predictionEnabled: Boolean = true,
    val pool: String = "",
) {
    /**
     * The overlay's text, with §11's budgets shown beside the measurements —
     * a number without its target is a number nobody acts on.
     *
     * "cpu" is in the header because these are CPU spans around GL calls, which
     * return once the driver has queued the work. That is the right number for
     * §11 (it bounds what the render thread spends) and the wrong one to read
     * as GPU time; `CanvasRenderer.publishFrame` carries the long version.
     */
    fun render(): String = buildString {
        append("frame cpu — stamp ").append(fmt(stampMs)).append("/").append(fmt(stampMsMax))
        append(" ms (budget 1.0)\n")
        append("           composite ").append(fmt(compositeMs)).append("/").append(fmt(compositeMsMax))
        append(" ms (budget 2.0)\n")
        append("commit ").append(fmt(commitMs)).append(" ms | dabs/frame ").append(dabsPerFrame)
        append(" | frames ").append(frames).append("\n")
        append("tail err ").append(fmt(predictionErrorPx)).append(" px ")
        append(if (predictionEnabled) "(on)" else "(OFF, > 12 px)").append("\n")
        append("tiles ").append(tilesResident).append("/").append(tilesBudget).append("\n")
        append(pool)
    }

    private fun fmt(v: Float): String {
        // Two decimals without `String.format`, which allocates a Formatter and
        // is locale-sensitive — a comma decimal separator would misalign the
        // columns on a German device for no benefit.
        val hundredths = (v * 100f + 0.5f).toInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
}

/** §5.3's 4 Hz. */
private const val SAMPLE_MS = 250L
