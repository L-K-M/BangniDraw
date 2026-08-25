# Input and stylus

This document covers everything between a `MotionEvent` (or key event)
arriving at the canvas `SurfaceView` and either a `StrokeInput` sample
reaching the stroke pipeline or a gesture reaching the ViewModel: the
`CanvasTouchHandler`, the `StrokeInput` sample format, the pure-JVM
`GestureArbiter`, palm rejection, S Pen specifics (eraser end, side
button, hover), two-finger navigation over `ViewTransform`, motion
prediction, system-gesture conflicts, keyboard/mouse, and the accessibility
rule that every gesture has a button. It expands PLAN.md §3.3 and the
`input/` package of §3; the stroke pipeline downstream of `StrokeInput`
(stabilizer, dab generation, front buffer) is `docs/plan/03-canvas-engine.md`,
the per-tool use of pressure/tilt is `docs/plan/04-tools.md`, and the chrome
the gestures share the screen with is `docs/plan/08-ui-and-layout.md`.

## 1. Where input is handled, and why it is not Compose

The canvas is a `SurfaceView` hosted through `AndroidView` (PLAN.md §3). All
pointer input for the canvas is handled by **`CanvasTouchHandler`**, a
`View.OnTouchListener` + `OnGenericMotionListener` + `OnHoverListener`
installed on that `SurfaceView` — *not* by a Compose `pointerInput`
modifier on a Box above it. Four reasons, each individually sufficient:

| Need | Compose `pointerInput` | View `onTouchEvent` |
| --- | --- | --- |
| Unbuffered dispatch (events at sensor rate, not vsync-batched) | no API | `View.requestUnbufferedDispatch(MotionEvent)` |
| Historical samples inside one batched event | dropped (`PointerInputChange` has `historical` only since recent versions and only for x/y/time; no pressure/tilt history) | `getHistoricalX/Y/Pressure/AxisValue` |
| Hover, tool type, buttons, `AXIS_DISTANCE`, tilt, orientation | hover events, `PointerType.Eraser`/`Stylus`, `PointerEvent.buttons` and `pressure` exist; `AXIS_DISTANCE`, tilt and orientation do not, and `HistoricalChange` carries neither pressure nor tilt | all of it, first-class |
| Zero allocation per event | `PointerEvent`/`PointerInputChange` are allocated per event and per pointer | the `MotionEvent` is a pooled native-backed object; we read floats out of it |

Compose still owns everything drawn *over* the surface (rail, strip,
panels, hover cursor). Those composables consume their own touches; what
they do not consume falls through to the `SurfaceView` because it is below
them in the view hierarchy. Consequence: the handler never has to ask "was
this tap on a button?" — if it received the event, it was on the canvas.

### Contract with the ViewModel and the engine

`CanvasTouchHandler` talks to two things and nothing else:

```kotlin
/** Implemented by CanvasViewModel. Called on the main thread. */
interface CanvasInputSink {
    fun onStrokeBegin(sample: StrokeInput, source: StrokeSource)   // source: STYLUS, ERASER_END, FINGER, MOUSE
    fun onStrokeSamples(samples: StrokeInputBatch)                 // preallocated, reused; copy before returning
    fun onStrokePredicted(samples: StrokeInputBatch)               // the removable tail, front layer only
    fun onStrokeEnd(sample: StrokeInput)
    fun onStrokeCancel()                                           // FLAG_CANCELED / ACTION_CANCEL / arbiter rollback
    fun onNavigate(step: NavigationStep)                           // centroid, pan, zoom, rotationDelta (view px / radians)
    fun onNavigateEnd()
    fun onGesture(g: QuickGesture)                                 // TapUndo, TapRedo, LongPressPick(x, y)
    fun onHover(state: HoverState)                                 // for HoverCursor; NONE hides it
    fun onStylusButton(pressed: Boolean, button: StylusButton)
    fun onToolTypeChanged(type: StrokeSource)                      // eraser end enters/leaves
}
```

The handler owns *no drawing state*: it does not know the active tool, the
brush size, or the layer. It needs exactly three inputs from the ViewModel,
read from the `UiState` on each event (cheap field reads, no allocation):
the current `ViewTransform` (to invert coordinates), `InputPrefs`
(stylus-only, finger-pressure, button action, pressure curve), and the
brush's current on-screen radius (for the hover cursor only). Everything
that *decides* — draw vs navigate, palm rejection, tap classification — is
in `engine/core/GestureArbiter`, which the handler feeds with plain
numbers so it is unit-testable without Android.

Threading: everything in this document runs on the main thread. The
ViewModel forwards `StrokeInputBatch` contents into the dab ring buffer for
the GL thread (`docs/plan/02-architecture.md` §3); the handler is
done the moment `onStrokeSamples` returns.

## 2. `StrokeInput` — one sample

```kotlin
/** One input sample in CANVAS pixels. Mutable, pooled; never escapes the batch it lives in. */
class StrokeInput {
    var x = 0f            // canvas px, via ViewTransform.invert(viewX, viewY)
    var y = 0f
    var pressure = 1f     // 0..1 after PressureCurve; 1.0 constant for fingers unless fingerPressure is on
    var tilt = 0f         // radians, 0 = perpendicular, π/2 = flat (AXIS_TILT as delivered)
    var orientation = 0f  // radians, AXIS_ORIENTATION rotated by -view.rotation so it is canvas-relative
    var timeNs = 0L       // event time in ns (eventTime × 1e6 — ms precision; historical event time × 1e6)
    var source = StrokeSource.STYLUS
    var predicted = false // true only in onStrokePredicted batches
}

class StrokeInputBatch(capacity: Int = 64) {   // 64 > max historical size seen on any S Pen at 120 Hz vsync (to verify on device; grows never — overflow flushes early)
    val items = Array(capacity) { StrokeInput() }
    var size = 0
}
```

How each field is derived:

| Field | Source | Notes |
| --- | --- | --- |
| `x`, `y` | `event.getX(i)`/`getY(i)` or historical → `ViewTransform.invert` | `invert` returns a `Pair`; the handler uses an allocation-free variant `invertInto(x, y, out: FloatArray)` added in the port (same math). The Compose `AndroidView` sits at the window origin of the canvas area, so view coordinates are `SurfaceView` coordinates — no insets math. |
| `pressure` | `getPressure(i)` / `getHistoricalPressure` → `PressureCurve.apply` | Raw value may exceed 1.0 on some devices; clamp to `[0, 1]` *before* the curve. For `TOOL_TYPE_FINGER`: constant `1.0` unless `InputPrefs.fingerPressure` is on — finger "pressure" on capacitive screens is contact area, which tracks finger angle and sweat, not intent. Mouse: constant 1.0. |
| `tilt` | `getAxisValue(AXIS_TILT, i)` | Already radians. Devices without tilt report 0 (perpendicular) — tools treat 0 as "no tilt effect". |
| `orientation` | `getAxisValue(AXIS_ORIENTATION, i)` − `view.rotation`, wrapped to (−π, π] | The pen's azimuth is reported in *screen* space; a rotated view would rotate the marker's square tip the wrong way otherwise. |
| `timeNs` | `event.eventTime * 1_000_000` for the current sample and `getHistoricalEventTime(pos) * 1_000_000` for historical ones (ms precision on all API levels; `getEventTimeNanos()` is public only from API 34 — to verify — and may be used behind a `Build.VERSION.SDK_INT >= 34` check with the ms path as the fallback, the older `getEventTimeNano()` being hidden API) | Time drives velocity dynamics and the stabilizer; monotonic per stroke by construction of the history order. |
| `source` | `getToolType(i)` ∈ STYLUS / ERASER / FINGER / MOUSE | Stored per sample so a stroke's source is available at every stage; a stroke never changes source mid-way (a tool-type change is a stroke boundary, §6). |

### Pressure curve

`PressureCurve` is pure JVM: a monotone piecewise-linear map over 8 knots,
applied after clamping. Two sources compose into one curve:

1. **Per-device calibration** (`Prefs.pressureCalibration`, keyed by
   `InputDevice.descriptor`): a floor and ceiling per device; the curve
   maps `[floor, ceiling] → [0, 1]`. **v1 ships the identity calibration**
   — floor `0.02`, ceiling `1.0` (S Pens idle at a small nonzero pressure
   while touching; without a floor, the lightest touch already paints). The
   data model and the composition below are in v1 so the value can be set
   later; the guided Settings screen that captures it (draw a stroke "as
   light as you would ever draw" and "as hard as you would ever press",
   record the 5th/95th percentiles as floor and ceiling) is **post-v1**
   (`12-roadmap.md` §5) — no v1 roadmap step or acceptance test ships that
   UI.
2. **User preference** (`InputPrefs.pressureCurve` ∈ Softer / Linear /
   Harder): a gamma of 0.7 / 1.0 / 1.4 applied after calibration. This is
   the pressure control v1's Settings screen exposes (`12-roadmap.md`
   step 10).

Per-brush pressure→size/opacity/flow curves are a third stage and belong to
the brush (`docs/plan/04-tools.md`); `StrokeInput.pressure` is the
device-normalized value, brush-independent.

### Consuming historical samples

A batched `ACTION_MOVE` carries `getHistorySize()` older samples *plus* the
current one. They are consumed in order, oldest first, then the current
sample — for every pointer the arbiter says is drawing (in practice one):

```kotlin
val n = e.historySize
for (h in 0 until n) fill(batch.next(), e, pointerIndex, historical = h)
fill(batch.next(), e, pointerIndex, historical = -1)   // the event itself
sink.onStrokeSamples(batch)
batch.size = 0
```

While unbuffered dispatch is active, `historySize` is usually 0 and events
arrive at the digitizer rate (S Pen: to verify, expected ≥ 240 Hz); the
loop is the same code either way. Nothing in the loop allocates: the
batch is preallocated, the `FloatArray(2)` for `invertInto` is a field.

### Unbuffered dispatch

`view.requestUnbufferedDispatch(e)` is called on `ACTION_DOWN`. The
`MotionEvent` overload only affects *touch* streams (the framework returns
early unless the event is a touch event with action DOWN or MOVE), so it
does nothing for hover. For smooth hover cursor motion the handler calls
the API 30+ overload `requestUnbufferedDispatch(InputDevice.SOURCE_STYLUS)`
on `ACTION_HOVER_ENTER` (to verify); on API 29 hover stays vsync-batched
and the handler consumes the hover event's historical samples instead.
The touch request lasts until the gesture ends, so it must be re-issued per
stroke. It is called for *all* tool types: the finger path benefits equally
on a phone. Cost: more main-thread wakeups while a pointer is down — that
is the whole point, and the handler does nothing else on that thread.

## 3. The `GestureArbiter`

`GestureArbiter` (`engine/core`, pure JVM) is fed a timeline of pointer
events — `down(id, toolType, x, y, tNs)`, `move(...)`, `up(...)`,
`cancel()` — in *dp-independent* units (the handler passes `density` once at
construction, so thresholds in dp are compared against px correctly) and
emits decisions. It has no reference to `MotionEvent`; the tests build
timelines by hand.

### Decisions

| Decision | Meaning | Who acts |
| --- | --- | --- |
| `Draw(pointerId, source)` | this pointer is a stroke from its down position; samples already buffered are replayed into `onStrokeBegin` + `onStrokeSamples` | stroke pipeline |
| `Navigate` | pointers form a two-finger navigation; a finger stroke that had started is cancelled first (`CancelStroke`) | `ViewTransform.gesture` |
| `TapUndo` / `TapRedo` | 2 / 3 fingers down and up within the tap limits with no navigation | `HistoryJournal` via ViewModel |
| `LongPressPick(x, y)` | one finger held still ≥ 500 ms without a stroke having committed | eyedropper, then previous tool restored on up |
| `CancelStroke` | roll back the live stroke, no history entry | front buffer `cancel()` |
| `Ignore(pointerId)` | palm / touch during stylus | nothing |

### Rules, with numbers

| Rule | Value | Why this value |
| --- | --- | --- |
| Stylus down → `Draw` immediately | 0 ms | Pen latency is the product; there is nothing to disambiguate — a stylus never navigates. |
| Finger down → *pending* for `PENDING_MS` | 120 ms | Long enough that a two-finger gesture's second finger (typically 20–80 ms after the first) is seen before the stroke is committed; short enough that a single finger drawing does not feel delayed — the pending samples are buffered and replayed, so nothing is lost, only the first pixels appear ≤120 ms late. Fingers already moving > `TAP_SLOP` before 120 ms resolve to `Draw` early (a deliberate line is not a chord). |
| Second finger within `PENDING_MS` | → `Navigate`, cancels the pending/just-started stroke | The two-finger chord is the universal navigation gesture; a stray mark from the first 100 ms would be worse than a lost 100 ms of line. |
| Second finger *after* `PENDING_MS` | ignored | The user is drawing with one finger and rested another; changing modes mid-stroke would be a surprise. |
| Stylus stroke + any finger | finger → `Ignore` | A stylus stroke is never interrupted by touch (palm, knuckle, the other hand resting). |
| Tap | up within `TAP_MS` = 200 ms and total movement < `TAP_SLOP` = 8 dp for *every* pointer | 200 ms is comfortably above a deliberate tap and below the shortest pan; 8 dp is Android's standard touch slop order of magnitude. |
| 2-finger tap → `TapUndo`; 3-finger → `TapRedo` | count = maximum pointers simultaneously down | Counting the *maximum* tolerates fingers landing a few ms apart. A 2-finger tap that turns into movement is `Navigate` from the moment slop is exceeded, so undo never fires by accident after a pan. |
| Long press | one finger, no movement > `TAP_SLOP`, held ≥ `LONG_PRESS_MS` = 500 ms, and stroke still pending or `stylusOnly` is on | 500 ms matches the platform long-press. If touch drawing is on, the finger resolves to `Draw` at 120 ms, so long-press-pick only exists in stylus-only mode or *before* the stroke commits — i.e. it is effectively a stylus-only-mode feature plus the eyedropper tool. |
| `stylusOnly` mode | one finger → pan (`Navigate` with one pointer: pan only, no zoom/rotation) | The most-requested S Pen feature: rest your hand, move the paper with a finger. |
| Rotation snap | see §7 | |

Constants live in `GestureArbiter.Companion` (`PENDING_MS`, `TAP_MS`,
`TAP_SLOP_DP`, `LONG_PRESS_MS`) and the tests reference them by name, so
tuning on device is a one-line change with tests still meaningful.

### State machine

```
                     stylus down                          stylus up
  ┌──────┐ ───────────────────────────────► ┌──────────────┐ ──────────► IDLE
  │ IDLE │                                  │ STYLUS_DRAW  │  finger down: Ignore
  └──────┘ ◄──────────────────────┐         └──────────────┘  cancel: CancelStroke → IDLE
     │                            │
     │ finger down                │ up < TAP_MS & < slop      ┌───────────┐
     ▼                            │  (1 finger, drawing on):   │ TAP_WAIT  │ 2 up → TapUndo
  ┌───────────────┐ ─────────────►│  Draw + End (dot)          │ (all up)  │ 3 up → TapRedo
  │ FINGER_PENDING│               └────────────────────────────┴───────────┘
  │ (buffering)   │ 2nd finger < PENDING_MS ─────────────► ┌──────────────┐
  │               │ move > slop ─► Draw ─┐                 │  NAVIGATE    │ all up → IDLE
  │               │ 120 ms elapsed ─► Draw│                │ 2 pointers   │ 3rd finger: ignored
  │               │ held 500 ms, still ───┼─► LongPressPick│ (1 in stylus-│ stylus down: CancelNav,
  └───────────────┘                       │                │  only mode)  │   → STYLUS_DRAW
                                          ▼                └──────────────┘
                                   ┌──────────────┐
                                   │ FINGER_DRAW  │ up → End → IDLE
                                   │              │ extra finger → Ignore
                                   └──────────────┘ cancel → CancelStroke → IDLE

  Any state: ACTION_CANCEL / FLAG_CANCELED → CancelStroke (if a stroke is live) → IDLE
  Any state with stylus hovering or the hover-exit grace running: finger down → Ignore
```

`stylusOnly = true` removes the FINGER_PENDING → FINGER_DRAW transitions:
one finger goes straight to NAVIGATE (pan only) after slop, or to TAP_WAIT
/ LongPressPick.

### MotionEvent → action

| Event (masked action, tool type) | Handler does |
| --- | --- |
| `ACTION_DOWN`, stylus | `requestUnbufferedDispatch`; `predictor.record`; arbiter `down` → `Draw`; `onStrokeBegin`; read button state (§6) |
| `ACTION_DOWN`, eraser | as stylus, with `source = ERASER_END` → ViewModel swaps to eraser preset |
| `ACTION_DOWN`, finger | `requestUnbufferedDispatch`; arbiter `down` → pending (buffer sample) or `Ignore` (stylus active / palm) |
| `ACTION_POINTER_DOWN` | arbiter `down` for the new pointer → `Navigate` / `Ignore` |
| `ACTION_MOVE` | for each pointer: historical + current samples → arbiter `move`; drawing pointer → `onStrokeSamples` (+ `predictor.record`); navigating pointers → `NavigationStep` (§7) |
| `ACTION_POINTER_UP` | arbiter `up`; navigation continues with the remaining pointer as pan-only until it lifts (no zoom from one finger) |
| `ACTION_UP` | arbiter `up` → `onStrokeEnd` / `TapUndo` / `TapRedo` / `onNavigateEnd` |
| `ACTION_CANCEL`, or any event with `FLAG_CANCELED` (API 33+) | `onStrokeCancel` (front buffer `cancel()`, stroke buffer discarded, no `HistoryEntry`), arbiter reset, navigation ended without a step |
| `ACTION_HOVER_ENTER/MOVE` (generic motion) | `HoverState(x, y, distance?, source)` → `onHover`; palm-rejection "stylus near" flag set |
| `ACTION_HOVER_EXIT` | `onHover(NONE)`; start the `HOVER_GRACE_MS` timer (§5) |
| `ACTION_BUTTON_PRESS/RELEASE` (generic motion, API 23+) | `onStylusButton` (§6) |
| `ACTION_SCROLL` (mouse wheel) | zoom step about the pointer (§9) |
| `KeyEvent` | shortcuts (§9); only when the surface has focus |

## 4. Cancel semantics

A cancelled stroke leaves **no trace**: `GLFrontBufferedRenderer.cancel()`
drops the front-buffered content, the `StrokeBuffer` is discarded without
merging, and no `HistoryEntry` is written. This is the same path for all
three causes:

- the platform's `ACTION_CANCEL` (the window lost the gesture, e.g. a
  system back-swipe took over — see §10);
- `FLAG_CANCELED` on API 33+ (platform palm rejection retroactively
  rejects the pointer — "handle by rolling back the stroke since
  ACTION_DOWN", which for us is exactly `cancel()`);
- the arbiter's own `CancelStroke` (a second finger arriving inside the
  pending window; a stylus landing while a finger stroke is pending).

Because `Draw` for fingers is only issued after the pending window or after
slop, and the stroke buffer is separate from the layer until pen-up
(`docs/plan/03-canvas-engine.md` §Stroke buffer), cancellation costs nothing
and never touches the document. That is the reason the stroke buffer exists
as a separate object rather than "just paint into the layer".

## 5. Palm rejection

Three layers, cheapest first:

1. **Platform.** Samsung's firmware already suppresses most palm touches
   while the S Pen is in range; on API 33+ the platform may also cancel
   already-delivered pointers with `FLAG_CANCELED` (§4). We take what we
   get.
2. **Stylus-near rule.** `StylusState` (in `input/`) tracks
   `nearSinceNs`/`exitedAtNs` from hover and contact events. While a stylus
   is *down* or *hovering*, and for `HOVER_GRACE_MS` = 500 ms after
   `ACTION_HOVER_EXIT`, every `TOOL_TYPE_FINGER` pointer is `Ignore`d by
   the arbiter — including ones that would otherwise have been a two-finger
   tap. The grace window covers the common sequence "lift the pen a little
   too high between two strokes, palm still resting". 500 ms is a guess to
   tune on device: too long and the first finger gesture after putting the
   pen down is swallowed; too short and a hover flicker lets the palm mark.
3. **Size heuristic — deliberately not used.** `AXIS_TOUCH_MAJOR` varies
   too much across devices to threshold reliably, and the stylus-near rule
   already covers every case where a palm can reach the screen while
   drawing with a pen. Finger drawing on a phone has no palm to reject.

A finger already in `FINGER_DRAW` when the stylus comes near is *not*
cancelled by hover alone (the user may be drawing with a finger with the
pen in the other hand); stylus *contact* does cancel it, because at that
point the pen's stroke is the intent.

## 6. S Pen specifics

**Eraser end.** `TOOL_TYPE_ERASER` on `ACTION_DOWN` starts a stroke with
`source = ERASER_END`; the ViewModel swaps the active preset to the eraser
preset in `Prefs.eraserEndPreset` (default: hard eraser) for the duration
of the stroke — `ToolSwitcher.pushTemporary` / `popTemporary`,
`docs/plan/04-tools.md` §9 — and restores the previous preset on
`ACTION_UP`. Nothing in the rail changes except the active-tool highlight
(a dashed "temporary" ring, `docs/plan/08-ui-and-layout.md` §3.2). Tool
type is read per pointer per `ACTION_DOWN`, never mid-stroke.

**Side button.** `getButtonState()` is read on `ACTION_DOWN` and on
`ACTION_BUTTON_PRESS/RELEASE` generic-motion events. Pressed =
`BUTTON_STYLUS_PRIMARY`, **or** `BUTTON_SECONDARY` (older Samsung firmware
reported the side button as a mouse-style secondary button; both bits are
tested, it costs nothing). `BUTTON_STYLUS_SECONDARY` (a second button, rare)
is ignored in v1. The action is held-while-pressed:

| `Prefs.penButtonAction` (in `InputPrefs`) | While held | On release |
| --- | --- | --- |
| `ERASER` (default) | active preset → eraser (same swap as the eraser end) | previous preset restored |
| `EYEDROPPER` | stroke samples pick color instead of painting; the hover cursor shows the sampled color | previous tool restored, picked color kept |
| `NONE` | nothing | nothing |

A press *during* a stroke does not change that stroke (the stroke was begun
with a tool; changing tools mid-stroke would produce half-erased lines);
the swap applies from the next `ACTION_DOWN`. A press while hovering with no
contact is a **quick action** (post-v1: e.g. toggle focus mode, open the
color panel); in v1 it is recorded and only affects the next contact.

**Hover.** `ACTION_HOVER_MOVE` drives `HoverCursor` (a Compose overlay,
`ui/canvas/`): a ring whose radius is the brush's current size in *screen*
pixels (`radius_canvas × view.scale`); its exact looks (crosshair under
6 px, dashed ring for the eraser end, pipette for the eyedropper) are
`docs/plan/08-ui-and-layout.md` §3.8. It is hidden for `TOOL_TYPE_FINGER`
(fingers do not hover) and for the mouse unless the pointer is over the
canvas. `AXIS_DISTANCE` is optional: when the device reports it, the ring's
alpha fades from 1.0 at contact to 0.4 at max reported distance — a small
"you are about to touch" cue; when it does not (most devices report 0 or a
constant), the ring is opaque. Hover coordinates are not inverted into
canvas space — the cursor is a screen-space overlay.

## 7. Two-finger navigation

`NavigationStep` is computed per `ACTION_MOVE` from the two navigating
pointers' previous and current positions — the Meltorama formulation,
ported with `ViewTransform` (to verify: `FitTransform` too, for `rebase` on
window-size change):

```
prev: p0, p1      cur: q0, q1     (view px)
centroid  = (q0 + q1) / 2
pan       = centroid − (p0 + p1) / 2
zoom      = |q1 − q0| / |p1 − p0|          (1.0 if |p1 − p0| < 1 px — degenerate)
rotation  = atan2(q1 − q0) − atan2(p1 − p0), wrapped to (−π, π]
view'     = view.gesture(centroid.x, centroid.y, pan.x, pan.y, zoom, rotationΔ)
```

The point under the fingers stays under the fingers by construction of
`gesture`, and similarities compose exactly, so a long session of pinches
does not drift. In stylus-only mode with one finger, `zoom = 1`,
`rotation = 0`.

**Rotation snap.** The ViewModel keeps `rawRotation` (the un-snapped
angle the gesture math accumulates into) separately from the displayed
`view.rotation`. The pure decision lives in `engine/core/RotationSnap`
(tested alongside `ViewTransform`): unsnapped → snapped when
`|rawRotation| ≤ SNAP_IN` (3° = 0.0524 rad); snapped → unsnapped when
`|rawRotation| > SNAP_OUT` (5°) — the wider exit band is the hysteresis
that stops flicker at the boundary. While snapped the displayed rotation is
exactly `0f` and `rawRotation` keeps accumulating so small deltas can leave
the snap; a haptic tick (`HapticFeedbackConstants.CLOCK_TICK`, gated by
`Prefs.haptics`) fires once on the unsnapped → snapped transition. Snap to 90° multiples is an option (`Prefs.snapRightAngles`,
off by default): a painter turning the canvas for a stroke does not want
it snapping at 90°, while someone working on a rotated portrait does.
Exact `0f` matters: `ViewTransform.isIdentity` decides whether the
reset-view pill is shown.

**Scale clamps.** Meltorama's `MIN_SCALE = 0.5f`/`MAX_SCALE = 8f` are
relative to the fit-to-window transform. For a drawing app the top end is
too low: fit for a 4096² canvas is ≈ 0.39 *screen px per canvas px* on a
1600 px window, so `MAX_SCALE = 8` tops out at ≈ 3.1 screen px per canvas
px — too little for pixel-level work, which wants one canvas px to cover
several screen px. **Decision (landed with the scaffold, step 1):** the
port keeps the math and sets the constants to
`MIN_SCALE = 0.25f` (a thumbnail-in-the-middle overview) and
`MAX_SCALE = 64f` relative to fit, with the effective zoom-in cap
`min(MAX_SCALE · fit, 32 screen px per canvas px)` computed by the
ViewModel from the fit so that phones and tablets reach the same
pixel-level zoom. Worked numbers for 4096²: 64 × 0.39 ≈ 25 screen px per
canvas px on a 1600 px window, 64 × 0.26 ≈ 17 on a 1080 px phone — the
32 cap is never reached for a canvas that large and only bites for small
canvases (e.g. 512², where 64 × fit would exceed it). Making MIN/MAX
constructor or companion parameters in the port keeps Meltorama's test file
portable unchanged. This deviation from "ported verbatim" is recorded in
AGENTS.md.

**No fling.** A pan that keeps moving after the fingers lift is right for
a map and wrong for paper: the canvas is the thing you are drawing on, and
the next pen-down comes tenths of a second after the pan; a moving canvas
under a landing pen means a mark in the wrong place. The view stops when
the fingers stop. (Same reason there is no zoom animation except the reset
spring.)

**Reset view.** A pill appears at the bottom centre when `!view.isIdentity`
(`docs/plan/08-ui-and-layout.md` §3.7); tapping it springs the view back
through `ViewTransform.lerp` (a ~300 ms spring, 08 §3.7). A double-two-finger-tap alias was
considered and dropped: it collides with two-finger-tap undo (the first tap
would undo). Gestures stay few.

### Final gesture table

| Gesture | Action | Button equivalent |
| --- | --- | --- |
| Stylus contact | draw with the active tool | — |
| Eraser end contact | erase | eraser tool button |
| Side button + contact | configured action (eraser / eyedropper / none) | tool buttons |
| One finger drag | draw (touch drawing on) / pan (stylus-only) | — / two-finger pan |
| Two-finger drag | pan + zoom + rotate | reset-view pill; zoom slider in the menu (post-v1) |
| Two-finger tap | undo | top-strip undo |
| Three-finger tap | redo | top-strip redo |
| Finger long-press (stylus-only mode) | sample color under the finger | eyedropper tool |
| Tap on the canvas while a panel is open | dismiss the panel (Compose scrim, never reaches the handler) | panel's close affordance |

Nothing else. Four-finger gestures, pinch-to-open-menus, edge swipes:
no — every one of those has been a palm-triggered accident in some app.

## 8. Motion prediction

`androidx.input:input-motionprediction`'s `MotionEventPredictor` wraps the
system prediction API on Android 14+ and a library implementation below.
Usage:

- `predictor = MotionEventPredictor.newInstance(surfaceView)` once per
  surface (recreated with the surface).
- `predictor.record(e)` on **every** `ACTION_DOWN`/`MOVE`/`UP` for a stylus
  pointer (including `ACTION_HOVER_MOVE` — hover history improves the first
  predicted samples after contact; to verify that the predictor accepts
  hover events — if not, record contact only).
- `predictor.predict()` once per frame, *not* per input event —
  prediction is a per-frame estimate of where the pen will be at the next
  display, and calling it per event only wastes CPU. Cadence decision,
  shared with `docs/plan/03-canvas-engine.md`: real input renders per
  batch (the handler's `onStrokeSamples` leads to one
  `renderFrontBufferedLayer(param)` per batch, PLAN.md §3.1); `predict()`
  is called from a `Choreographer` frame callback the handler registers
  while a stylus stroke is live, and its tail is submitted as its own
  render.
- The returned `MotionEvent?` is converted like any other event into a
  `StrokeInputBatch` with `predicted = true` and sent to
  `onStrokePredicted`. The engine draws it as the removable tail in the
  front layer only (`docs/plan/03-canvas-engine.md` §Predicted tail); the
  next real batch replaces it. Predicted events are **never** recorded
  back into the predictor and never reach the stroke buffer.

Tail length: the predictor targets the next frame; the handler additionally
truncates the tail to `PREDICT_MAX_NS` = 16 ms of lookahead (roughly one
frame at 60 Hz, two at 120 Hz) by dropping predicted historical samples
beyond it. Longer tails visibly overshoot at stroke ends.

Automatic disable: the handler keeps an exponentially-weighted error
`err = 0.9·err + 0.1·|predicted − actual|` (screen px, compared when the
real sample for the predicted time arrives, interpolated). When `err >
PREDICT_ERR_DISABLE_PX` = 12 px it stops sending tails for the rest of the
stroke and re-enables at the next `ACTION_DOWN`. A wildly wrong tail is
worse than a few ms of latency.

Recommendation: **keep prediction on for all refresh rates, including 120
Hz, and measure.** The argument for disabling at ≥120 Hz is that a frame is
already short; the counter-argument is that the pipeline latency
(digitizer → app → front buffer → display) is 2–3 frames regardless of the
rate and the tail hides one of them at either rate. Step 2 of the roadmap
ships an on-device overlay (`Prefs.debugLatency`) that draws the last N
real vs predicted points so the error and the benefit are visible;
`docs/plan/10-performance.md` owns the targets. Fingers are not predicted
in v1 (the library supports it; finger latency is not the product).

## 9. Keyboard and mouse

Nicety for DeX, Chromebooks and Bluetooth keyboards; no UI is built around
them, and every shortcut is a duplicate of a button.

| Key | Action |
| --- | --- |
| Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y | undo / redo / redo |
| `[` / `]` | brush size −/+ one slider step |
| `B` / `E` / `S` / `G` / `I` | brush (last brush preset) / eraser / smudge / fill / eyedropper |
| `Alt` held | eyedropper while held (mirrors the side-button behavior) |
| `0` | reset view |
| `Tab` | toggle focus mode |
| `L` / `C` | layer panel / color panel |

Keys are handled in `MainActivity.onKeyDown` → ViewModel (not on the
`SurfaceView`, which may not have focus while a Compose panel does) and are
suppressed while a text field (layer rename) is focused.

Mouse: left button draws (`source = MOUSE`, pressure 1.0, no tilt), hover
shows the cursor ring, wheel (`ACTION_SCROLL`, `AXIS_VSCROLL`) zooms by
`1.1^ticks` about the pointer via `ViewTransform.gesture` with
`pan = 0, rotation = 0`, Ctrl+wheel rotates by 5° per tick, middle-button
drag pans. Right button: nothing in v1 (no context menus — principle 1).

## 10. System gesture conflicts

The canvas runs immersive (`docs/plan/08-ui-and-layout.md` §Focus mode
and the default canvas chrome both hide system bars). Two settings keep the
system from eating strokes:

- `WindowInsetsControllerCompat.systemBarsBehavior =
  BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`: edge swipes reveal the bars
  transiently instead of both revealing them *and* being delivered as a
  stroke start. Strokes that begin inside the system gesture insets still
  arrive as `ACTION_DOWN` followed by `ACTION_CANCEL` when the system claims
  the gesture — §4 handles that with no trace.
- `View.setSystemGestureExclusionRects` on the canvas view for the strip of
  screen along the tool rail's edge (rail width + 16 dp), so a stroke
  starting next to the rail is not treated as a back gesture. The platform
  caps honored exclusion at **200 dp per edge** (platform limit, verify
  against the current docs) — the rail is narrower than that, and the
  exclusion is confined to the rail's extent, not the whole edge, so the
  user keeps the system back gesture elsewhere. The exclusion rects are
  updated on every layout pass that moves the rail (handedness switch,
  window size class change).

The top edge (notification shade) is not excluded: the top strip lives
there and a stroke rarely starts at pixel 0.

## 11. Accessibility

Every gesture in §7's table has a button equivalent and every tool is
reachable from the rail with a content description; the only thing that
has no non-gesture path is drawing itself. Touch-drawing users who cannot
chord two fingers get: undo/redo in the top strip, the reset-view pill,
and (post-v1) a zoom slider in the menu. `TalkBack` explores the Compose
chrome normally; the `SurfaceView` itself cannot be described pixel by
pixel, so it is wrapped in the single semantics node of
`docs/plan/08-ui-and-layout.md` §6 / `01-product.md` A6 ("Canvas, {w} × {h}",
active tool and layer, custom actions Undo and Redo) — never
`importantForAccessibility = no`, which would hide undo from a TalkBack
user without a keyboard. Haptic ticks (snap, tap-undo) are all gated by
`Prefs.haptics`.

## 12. Tests (`docs/plan/11-testing.md` owns the list)

`GestureArbiterTest` builds pointer timelines with explicit nanosecond
timestamps and asserts the decision sequence:

- stylus down → `Draw` at t=0; finger down at t=50 ms → `Ignore`;
- finger down; second finger at 100 ms → `CancelStroke` (if issued) then
  `Navigate`; at 150 ms → ignored;
- finger down/up at 150 ms, 3 dp movement → `Draw` + end (a dot), not a
  tap; two fingers down/up at 150 ms → `TapUndo`; same with 12 dp drift →
  `Navigate`, no undo;
- stylus hover exit at t, finger down at t+300 ms → `Ignore`; at t+700 ms
  → pending;
- `stylusOnly`: one finger → `Navigate` after slop, long-press → `LongPressPick`;
- `cancel()` in every state → `CancelStroke` iff a stroke was live, then IDLE.

`PressureCurveTest`: monotone, clamps >1.0, floor maps to 0, calibration
composes with gamma. `ViewTransformTest` is ported with the class (gesture
composition, centroid anchoring at the clamp boundary, `normalizeAngle`,
rebase) plus the snap hysteresis. `StrokeInput` derivation is exercised by
a `FakeMotionEventReader` interface (the handler reads through a tiny
interface over `MotionEvent`, faked in tests) — the one place `input/` has
a JVM test despite living outside `engine/core`.
