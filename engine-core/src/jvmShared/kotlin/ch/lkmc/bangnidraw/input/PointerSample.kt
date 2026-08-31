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

    /**
     * The tool that produced this sample, or `null` where it was not read
     * from the platform — see [setWithoutTool].
     *
     * Nullable rather than a stale or defaulted value, because there is no
     * honest one to leave behind. One record is reused for every pointer, so
     * the last tool written may belong to a *different* finger: with a palm
     * down as pointer 0 and the pen drawing as pointer 1, pointer 0's moves
     * would carry `STYLUS`. `FINGER` would be as confidently wrong the other
     * way. `null` makes the two entries that need the tool say so, and the
     * compiler makes any future reader handle its absence.
     */
    var tool: PointerTool? = null

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
        setWithoutTool(pointerId, x, y, pressure, tilt, orientation, timeNs)
        // After the delegate, which clears the field.
        this.tool = tool
        return this
    }

    /**
     * The contact fill for the paths whose consumer never reads [tool].
     *
     * Reading the tool costs a JNI call per sample on Android, and only two
     * of the six sample paths spend it: a down decides palm rejection and the
     * eraser end, a hover-enter decides the cursor. A move, a lift and a
     * predicted sample continue a gesture whose tool was settled at *a* down,
     * so computing it again per historical sample — several per frame from a
     * 240 Hz digitizer — buys nothing.
     *
     * **[tool] is cleared to `null` here**, not left as the last fill wrote
     * it. One record serves every pointer, so a leftover value is not even
     * "this gesture's tool": with a palm down as pointer 0 and the pen as
     * pointer 1, pointer 0's moves would read `STYLUS`. A future consumer
     * that needs the tool has to move its call site back to [set];
     * `PointerSampleToolContractTest` says the same thing one round earlier,
     * at the four entries that must not read it.
     */
    fun setWithoutTool(
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        timeNs: Long,
    ): PointerSample {
        this.tool = null
        this.pointerId = pointerId
        this.x = x
        this.y = y
        this.pressure = pressure
        this.tilt = tilt
        this.orientation = orientation
        this.timeNs = timeNs
        // The hover axis does not survive contact: a reused record must not
        // carry the last hover sample's distance into a contact path.
        this.distance = 0f
        return this
    }

    /** The hover fill: position, hover distance, time — named apart from [set] so the fifth-float collision (`pressure` vs `distance`) cannot be written by accident. */
    fun setHover(
        pointerId: Int,
        tool: PointerTool,
        x: Float,
        y: Float,
        distance: Float,
        timeNs: Long,
    ): PointerSample {
        setHoverWithoutTool(pointerId, x, y, distance, timeNs)
        // After the delegate, which clears the field.
        this.tool = tool
        return this
    }

    /**
     * The hover fill for hover *moves*, whose consumer never reads [tool] —
     * the cursor was decided at hover-enter. Clears [tool] exactly as
     * [setWithoutTool] does, for the same reason and under the same pin.
     */
    fun setHoverWithoutTool(
        pointerId: Int,
        x: Float,
        y: Float,
        distance: Float,
        timeNs: Long,
    ): PointerSample {
        this.tool = null
        this.pointerId = pointerId
        this.x = x
        this.y = y
        this.distance = distance
        this.timeNs = timeNs
        // The contact-only axes do not survive a hover fill either — the
        // positional collision between `pressure` and `distance` makes
        // cross-population easy to write by accident.
        this.pressure = 1f
        this.tilt = 0f
        this.orientation = 0f
        return this
    }
}
