# ANALYSIS.md — the forward backlog

Findings from the full-project review of 2026-08-27 (originally written down
in `glm.md`): every entry below was verified in the source, not inferred from
the docs. Everything the review marked **[fix-now]** has landed (see the
cleared list); what remains is shovel-ready work for a future LLM session,
ordered small-to-large within each section.

House rules for picking items up: read PLAN.md's relevant `docs/plan/` file
first, write the failing test before the fix, keep decision logic in
`engine/core`, and record what you learn in AGENTS.md.

## Cleared (do not re-open)

- Tool-rail icon collisions + divider indices — PR #22.
- Zero per-frame allocation in the sandwich viewport query — post-v1 audit.
- Eyedropper read throttling (`EyedropperSampleGate`) — PR #30.
- Live zoom/angle readout during navigation (`onNavigateActive`) — PR #35.
- Studio empty state on every width — PR #33 (parallel work).
- RGB fields stack under font scaling — PR #39 (parallel work). The
  *cursor-reset* problem below is separate and still open.

## Shovel-ready (small, well-scoped)

1. **Long-press the eraser slot toggles hard ⇄ soft.** The soft eraser preset
   ships but is reachable only through the brush settings sheet (the rail
   renders exactly one eraser). A `combinedClickable` long-press on the
   eraser slot calling a ViewModel toggle (`eraserBrushId` swap between
   `HARD_ERASER_ID`/`SOFT_ERASER_ID`) plus a haptic tick makes both erasers
   one gesture away. Keep the settings sheet path; this adds a shortcut, not
   a replacement. (`ui/canvas/ToolRail.kt`, `CanvasViewModel`.)

2. **Eyedropper/hex-field editing state.** `ColorPanel.ColorFields` keys
   `remember(color)` on the committed color, so the moment a complete hex
   parses and commits the field recomposes from state and the cursor jumps
   to the start while the user may still be editing. Track "editing" locally
   (e.g. keep the draft until focus leaves, or re-key only on *external*
   color changes — compare against the last value this field emitted). Same
   pattern applies to the three channel fields. (`ui/canvas/ColorPanel.kt`.)

3. **Studio delete toast lies on failure.** `StudioScreen`'s delete confirm
   toasts "Deleted" unconditionally while `viewModel.delete` is
   fire-and-forget IO. Make `delete` report completion like
   `saveAsNewGalleryItem` already does and toast on the outcome.
   (`ui/home/StudioScreen.kt`, `StudioViewModel`.)

4. **Canvas share chooser title.** `CanvasScreen.sharePainting` passes `null`
   to `Intent.createChooser` while the Studio passes the painting's title.
   Use the same "Untitled"-falling-back name. One line.
   (`ui/canvas/CanvasScreen.kt`.)

5. **Top-strip layer badge overlaps the layers icon.** The count badge pins
   to the icon's bottom-end corner with no inset; "12" grows leftward over
   the glyph. Inset it a couple of dp and give it a surface-colored ring so
   it reads as a badge at any count. (`ui/canvas/TopStrip.kt`, `ToolCluster`.)

6. **Draw the pressure curve above its four sliders.** The brush settings'
   curve editor is four anonymous "Knot 1–4" sliders. `Curve` is evaluable
   and `BrushPreview` shows the rendering machinery exists; a small
   Compose-Canvas graph (pressure on x, mapped value on y, four draggable
   knots optional — a read-only plot is already a win) above the sliders
   makes the control legible. (`ui/canvas/BrushSettingsSheet.kt`,
   `engine/core/Curve.kt`.)

7. **Remember the last custom canvas size.** The New Canvas dialog's custom
   fields always reset to 2048². One DataStore preference
   (`lastCustomWidth/Height`), written on create, pre-fills the fields.
   (`ui/home/NewCanvasDialog.kt`, `Prefs`.)

8. **Custom paper color at canvas creation.** `NewCanvasDialog`'s own
   comment says the "+" custom-paper swatch waited for the color panel
   (roadmap step 7) — which has landed. Add a sixth "+" swatch that adopts
   the current brush color (or opens the existing HSV pick) and passes it to
   `onCreate`. (`ui/home/NewCanvasDialog.kt`.)

9. **Fill progress card can overlap the compact dock.** Bottom-center card,
   24 dp padding; on a phone in DOCK mode the dock+ledge are ~104 dp. Not
   user-blocking (the busy gate disables the dock during a fill) but the
   card should clear the dock's top edge — reuse the `resetBottomPadding`
   pattern from `ResetViewPill`. (`ui/canvas/CanvasScreen.kt`.)

10. **Eyedropper hover cursor is off-center.** The pipette glyph (diagonal
    line + tip circle) draws its "tip" offset from the actual sample point
    at the ring center. A centered circle-plus-crosshair is the
    conventional, legible form. (`ui/canvas/HoverCursor.kt`,
    `HoverCursorPolicy`.)

## Larger ideas (need product judgment or a proposal doc)

- **GL band flatten** (`03-canvas-engine.md` §10.4): supersedes both CPU
  flatten call sites (gallery sync ~every 30 s, thumbnail at checkpoints,
  share/export) and removes the ~128 MiB peak ISSUES.md records. This is the
  single biggest performance item left in v1.
- **Brush "save as new preset."** Users can mutate the seven built-ins but
  cannot fork one into a new rail slot; `BrushPresetStore` already supports
  arbitrary ids and `railOrder` sorts unknown ids after built-ins. Needs
  rail-overflow UX (the FULL rail budget is exactly 10 slots) and a name
  dialog.
- **History list UI.** The journal is capped and persisted but reachable
  only as linear undo/redo; PR #31 (open at the time of writing) adds an
  undo-depth readout on long-press. A sheet listing recent entries (kind,
  layer, step count against the cap — `readyState` already computes the
  numbers) is the natural next step.
- **Pressure "scratchpad" in Settings.** The pressure preference is three
  radio buttons; a small scratch canvas drawing a test stroke under the
  current curve (pure Compose Canvas replaying a few saved samples) would
  make the choice feel real.
- **Mixing-dish drag-to-mix.** Let a drag on the dish strip smear the mix
  continuously (`MixingDish.gradient` already exists) — a smear gesture
  instead of a form-control slider.
- **Canvas edge resistance (rubber-band overshoot on pan).** Pure
  `ViewTransform` policy; makes the canvas feel physical. Keep Meltorama
  math in sync if either side changes (AGENTS.md rule).
- **Hue-milestone haptic on eyedropper drag.** The `HueMilestone` helper
  exists for the picker ring; tick when a dragged pick crosses a hue band.
- **Ambient Studio shelf.** The newest painting's `thumb.png` rendered
  large and dim behind the app name. One image, big polish.
- **Session playback from the undo journal.** The journal already holds
  every tile delta; replaying entries at intervals is a cheap precursor to
  the planned time-lapse feature.

## Verified clean (do not re-litigate)

Checked during the same review and found correct: the front-buffered drain
and tail-rect folding (§8.1); nav-slot compaction on `ACTION_POINTER_UP`;
per-pointer axis reads (no `actionIndex` bug); the stabilizer's bounded
catch-up; palm rejection's `stylusNear` feed and cancel-path stylus
clearing; the `MotionEventPredictor` ownership decision (AGENTS.md); zoom
readout liveness (`view` is snapshot state — see REVIEW.md R-097); and the
whole ISSUES.md review-2 list.
