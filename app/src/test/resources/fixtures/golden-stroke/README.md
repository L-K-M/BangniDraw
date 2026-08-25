# The golden stroke

`ink-pen-loop.json` is a stroke; `ink-pen-loop.dabs.txt` is what
`Stabilizer -> DabGenerator` makes of it with `BrushPresets.INK_PEN` and
stroke seed 20260825. `DabGeneratorGoldenTest` compares them field by field.

## Where the input came from

**Synthesized, not captured.** `docs/plan/11-testing.md` §6 describes this
fixture as recorded from a Tab S with an S Pen; no such device has been
available to this work, so the path is generated instead — a closed loop with
a third-harmonic wobble so curvature varies, a pressure ramp that presses in
and releases so both tapers are exercised, tilt sweeping with the loop, and an
8 ms cadence matching a 120 Hz digitizer.

What it therefore does *not* pin is anything specific to a real digitizer:
duplicate timestamps, jitter at the pixel level, the near-zero pressure of a
real `ACTION_DOWN`. `DabGeneratorTest` covers those as separate cases. When a
device recording exists, replacing this file is the better fixture and the
golden regenerates against it.

`the golden stroke actually exercises the dynamics it is meant to` is the
guard on this file rather than on the code: it fails if the stroke stops
tapering, stops curving, or gets short enough to pin nothing.

## The two columns files

`ink-pen-loop.json` is a list of `StrokeInput` samples in **canvas** px:

    x, y            canvas px, sub-pixel
    pressure        0..1, already through PressureCurve
    tilt            radians; 0 is perpendicular, pi/2 flat
    orientation     radians, canvas-relative
    timeMs          milliseconds from the start of the stroke

`ink-pen-loop.dabs.txt` is one dab per line, six decimals, in the order
`DabPass` reads them — the eight `DAB_STRIDE` fields:

    x y radius flow hardness angle aspect seed

Lines beginning `#` are comments. One dab per line rather than a JSON array
because the diff is meant to be read: a change to the dynamics *should* change
this file, and the review is how anyone sees by how much.

## Regenerating

    ./gradlew testDebugUnitTest -Dbangni.updateGolden=true

Then read the diff. A stroke that moved by a hair everywhere is a rounding
change; a stroke that gained or lost dabs is a spacing change and needs a
reason in the commit message.
