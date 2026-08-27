package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.math.pow

internal enum class MouseScrollMode {
    ZOOM,
    ROTATE,
}

internal enum class MouseButton {
    PRIMARY,
    MIDDLE,
    SECONDARY,
    NONE,
}

internal enum class MouseGesture {
    DRAW,
    PAN,
    IGNORE,
    NONE,
}

/** Maps desktop buttons before they can enter the finger arbiter. */
internal object MouseButtonPolicy {
    fun begin(button: MouseButton): MouseGesture = when (button) {
        MouseButton.PRIMARY -> MouseGesture.DRAW
        MouseButton.MIDDLE -> MouseGesture.PAN
        MouseButton.SECONDARY -> MouseGesture.IGNORE
        MouseButton.NONE -> MouseGesture.NONE
    }
}

/** Mouse navigation uses the same anchored transform as touch gestures. */
internal object MouseNavigationPolicy {

    fun scroll(
        view: ViewTransform,
        pivotX: Float,
        pivotY: Float,
        ticks: Float,
        mode: MouseScrollMode,
    ): ViewTransform {
        if (ticks == 0f) return view

        val zoom = if (mode == MouseScrollMode.ZOOM) {
            ZOOM_PER_TICK.pow(ticks)
        } else {
            1f
        }
        val rotation = if (mode == MouseScrollMode.ROTATE) {
            ticks * RADIANS_PER_ROTATION_TICK
        } else {
            0f
        }

        return view.gesture(
            pivotX = pivotX,
            pivotY = pivotY,
            panX = 0f,
            panY = 0f,
            zoom = zoom,
            rotationDelta = rotation,
        )
    }

    fun middleDrag(view: ViewTransform, deltaX: Float, deltaY: Float): ViewTransform =
        view.gesture(
            pivotX = 0f,
            pivotY = 0f,
            panX = deltaX,
            panY = deltaY,
            zoom = 1f,
            rotationDelta = 0f,
        )

    private const val ZOOM_PER_TICK = 1.1f
    private const val ROTATION_DEGREES_PER_TICK = 5f
    private val RADIANS_PER_ROTATION_TICK =
        (ROTATION_DEGREES_PER_TICK * PI / 180.0).toFloat()
}
