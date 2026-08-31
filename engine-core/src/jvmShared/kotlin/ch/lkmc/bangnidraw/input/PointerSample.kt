package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.PointerTool

/**
 * One pointer sample — the platform-neutral axis set `CanvasTouchHandler`
 * reads (`07-input-and-stylus.md` §2, DESKTOP.md seam 3): position,
 * pressure, tilt, orientation, tool type, hover distance, event time, and
 * the pointer id.
 *
 * A **reused mutable record**, not a data class: the touch path is
 * zero-allocation (`10-performance.md` §2.4), so a host owns one instance,
 * overwrites it per sample, and hands it to the handler's `onPointer*`
 * entries — the handler copies what it keeps ([track], the stroke
 * emission) exactly as the primitive `handle*` path always has. Never hold
 * a sample past the call that received it.
 *
 * Axes are the platform's raw values, in **window** px and radians: the
 * handler converts to canvas space where a sample is accepted.
 */
class PointerSample {

    /** The stable pointer id, not the per-event index. */
    var pointerId: Int = 0

    var tool: PointerTool = PointerTool.FINGER

    /** Window px. */
    var x: Float = 0f

    /** Window px. */
    var y: Float = 0f

    /** Raw device pressure in 0..1; the handler applies the user's curve. */
    var pressure: Float = 1f

    /** Radians from vertical, 0 = perpendicular. */
    var tilt: Float = 0f

    /** Screen-space azimuth in radians, zero at screen-up. */
    var orientation: Float = 0f

    /** Hover distance from the glass if the tool reports one, else 0. */
    var distance: Float = 0f

    /** Monotonic event time in nanoseconds. */
    var timeNs: Long = 0L

    fun set(
        pointerId: Int,
        tool: PointerTool,
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
    ): PointerSample {
        this.pointerId = pointerId
        this.tool = tool
        this.x = x
        this.y = y
        this.pressure = pressure
        this.tilt = tilt
        this.orientation = orientation
        this.timeNs = timeNs
        return this
    }

    fun set(
        pointerId: Int,
        tool: PointerTool,
        x: Float,
        y: Float,
        distance: Float,
        timeNs: Long,
    ): PointerSample {
        this.pointerId = pointerId
        this.tool = tool
        this.x = x
        this.y = y
        this.distance = distance
        this.timeNs = timeNs
        return this
    }
}
