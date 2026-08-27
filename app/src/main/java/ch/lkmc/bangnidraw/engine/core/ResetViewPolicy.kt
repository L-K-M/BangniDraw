package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
import kotlin.math.hypot

/** Decides when navigation is far enough from fit to offer Reset view. */
internal object ResetViewPolicy {

    fun isDisplaced(view: ViewTransform, density: Float): Boolean {
        require(density > 0f && density.isFinite()) { "density must be finite and positive" }

        if (abs(view.scale - 1f) > SCALE_TOLERANCE) return true
        if (abs(view.rotation) > ROTATION_TOLERANCE_RADIANS) return true

        return hypot(view.tx, view.ty) > PAN_TOLERANCE_DP * density
    }

    private const val SCALE_TOLERANCE = 0.01f
    private const val ROTATION_TOLERANCE_RADIANS = 0.008726646f
    private const val PAN_TOLERANCE_DP = 4f
}
