# Changelog

## 1.0.4 - 2026-08-27

- Reissue the canvas rendering fixes with a new package version.

## 1.0.3 - 2026-08-27

- Restore the canvas after startup and surface recreation.
- Ignore eyedropper reads that finish after pen-up.
- Give every tool a distinct rail icon (the pencil and smudge, and the
  airbrush and blur, shared glyphs).
- Show a live zoom/angle readout while a pinch or pan gesture runs.
- Throttle eyedropper reads to one per frame (a drag stalled the GL pipeline
  once per input sample).
- Allocate nothing in the sandwich's per-frame viewport query.
- Add settings sheets for smudge, blur and eyedropper.
- Hide pigment controls when the pigment mixer is unavailable, and canvas
  actions that cannot run.
- Show the Studio empty state on every width; stack RGB fields under font
  scaling; use plural resources for counts.
- Verify release builds without Mixbox.

## 1.0.2 - 2026-08-27

- Remove moving live-stroke cutoffs and tile-edge seams.

## 1.0.1 - 2026-08-27

- Keep the complete stroke visible while drawing.
- Raise dark tool-rail contrast and separate its sliders.
- Show the source artwork on adaptive launcher surfaces.

## 1.0.0 - 2026-08-27

- Add the tiled GLES canvas, seven brush presets, smudge, blur, fill, layers,
  persistent undo, autosave, gallery mirroring, sharing, and adaptive UI.
- Add Settings, About/licenses, accessibility semantics, and Simplified
  Chinese.
