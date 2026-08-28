# 帮你Draw review — 2026-08-28

Review target: `main` at `8d638d3` (`v1.1.0`). This is a source, test,
resource, architecture, and release-build review. I read `PLAN.md`, the
detailed plans, `AGENTS.md`, `ISSUES.md`, `REVIEW.md`, and `ANALYSIS.md`, then
traced the canvas, input, GLES, persistence, gallery, Studio, and Compose UI
paths. Three independent read-only audits covered engine/input, data/recovery,
and UI/product behavior.

The baseline is reproducible:

- `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleRelease` pass.
- The no-Mixbox build also passes tests, lint, and release assembly.
- The manifest requests no permissions. Backup and share boundaries are sane.
- No phone, tablet, S Pen, mouse/keyboard cover, or emulator was available.
  Frame pacing, palm rejection, TalkBack, 200% font scale, rotations, context
  loss, and native zh-Hans wording remain unvalidated on hardware.

## Verdict

The project is unusually mature for its age. Its architecture is coherent,
the canvas hot path mostly stays outside Compose, GPU ownership is explicit,
and the interface has a restrained visual identity. It is not ready to call
reliable, though. The review found paths that can lose or overwrite work,
produce wrong export colors, commit system-rejected input, leak background
workers, and make large paintings reopen with unbounded memory pressure.

The right near-term product work is reliability, touch fidelity, and honest
feedback. A broad visual redesign would be a mistake: the warm paper/slate
theme, saffron accent, physical-hand rail, and artwork-first Studio are good.
Polish should make controls clearer and more tactile without making the app
feel like a toy.

Disposition labels:

- **PR now** — high-confidence behavior and bounded implementation; give it an
  isolated branch and PR.
- **Backlog** — real issue, but the safe solution needs design, profiling, a
  device, or a larger slice.
- **Validate** — source evidence is insufficient; reproduce or measure first.
- **Idea** — optional product proposal, not a defect.

## 1. Correctness and data safety

### C1 — Exported and thumbnail colors can swap red and blue

**Severity:** critical. **Confidence:** high. **Disposition:** PR now.

`CpuFlatten` and tile files produce RGBA bytes. `ImageEncode.kt:28-33` and
`Thumbnails.kt:80-83` copy those bytes directly into Android `ARGB_8888`
bitmaps. On little-endian Android the native byte order is BGRA. Red artwork
therefore becomes blue in thumbnails, Gallery mirrors, PNG/JPEG exports, and
shares.

Convert RGBA to Android colors explicitly at the bitmap boundary. Pin opaque
red, blue, translucent, and edge-tile cases. Do not change the canonical tile
or GLES format.

### C2 — A failed tile flush is treated as a successful save

**Severity:** critical. **Confidence:** high. **Disposition:** PR now.

`TileFlusher.checkpointFlush()` reports failure, but
`CanvasViewModel.checkpoint` ignores the result, writes `project.json`, clears
dirty state, and lets Leave navigate. A full disk can therefore produce a
metadata commit that references stale or missing pixels while telling the
child the painting is saved. `retryPending()` is currently unused.

Abort the checkpoint before its commit point when tiles remain pending. Keep
dirty state, keep the Canvas open on Leave, retain the storage-full banner,
and retry after a later successful write. Test a writer that fails once and
then recovers.

### C3 — A checkpoint can replace an edit made while it writes

**Severity:** critical. **Confidence:** high. **Disposition:** PR now.

`CanvasViewModel.checkpoint` folds a document snapshot, suspends for disk IO,
then assigns the old folded value back and unconditionally clears all dirty
flags. Rename, reference, view, and other main-thread edits remain enabled
during that suspension. Such an edit can disappear from memory and never arm
another autosave.

Give model changes a monotonic generation. A completed checkpoint may clear
dirty state only for the generation it wrote; it must not replace newer model
fields. Test with a blocked store write and a mutation made before release.

### C4 — Reopen can overwrite fresh strokes and retain the whole painting raw

**Severity:** critical. **Confidence:** high. **Disposition:** backlog.

`CanvasViewModel.streamTiles` reads every tile sequentially, packages arrays
in batches of 16, and queues asynchronous `EngineSession.uploadTiles` calls.
There is no completion, backpressure, visible-first priority, progress gate,
or interaction gate. The UI is already Ready while uploads run.

Two failures follow:

- A child can paint into a not-yet-restored tile; the stale disk upload later
  overwrites those fresh GPU pixels.
- Queued closures can retain hundreds of MiB. A dense 4096² eight-layer
  document is about 512 MiB raw, despite the plan's 16 MiB staging promise.

The five-second engine-ready timeout also abandons restoration permanently,
leaving a blank or partial canvas without an honest error.

This needs a real reopen pipeline: fixed reusable staging buffers, GL upload
acknowledgements, cancellation by session generation, visible tiles first,
and a gate that permits drawing only after visible tiles are resident. Use
`PerfConstants.UPLOAD_BATCH_TILES` and `REOPEN_STAGING_TILES`; remove the local
magic batch size. Test queue bounds and an edit racing a delayed upload, then
measure 4096² reopen on class-A and class-B hardware.

### C5 — Journal publication is not atomic with changed pixels

**Severity:** critical. **Confidence:** high. **Disposition:** backlog.

`TileFlusher` publishes the stamped history entry before readback and tile
flush. Recovery considers contiguous entries applied, but it replays only
structural headers; it cannot restore the after-image. A process death after
the entry rename and before all tile writes can reopen as old or mixed pixels,
while the undo journal claims the edit exists.

This needs a recovery protocol, not a reordered line. Add a transaction marker
or after-payload sufficient to choose wholly-before or wholly-after. Exercise
entry-only, partially flushed, and fully flushed crash fixtures before choosing
the on-disk format. Record the resulting invariant in `AGENTS.md`.

### C6 — Canvas and Studio can insert two Gallery rows for one painting

**Severity:** high. **Confidence:** high. **Disposition:** backlog.

Leave starts a detached Canvas gallery sync and immediately navigates. Studio
then reads stale disk metadata and starts its own sync. Canvas records its
result only in a ViewModel field for a later checkpoint that may never happen.
With no recorded URI, both jobs can insert. Cancellation can also land after a
MediaStore mutation but before bookkeeping.

Use one application-scoped, per-project coordinator. Serialize export,
MediaStore mutation, and `ProjectStore.updateGalleryFields`; make ownership
across navigation explicit. Switching sync off must be rechecked after flatten
and before every mutation. Tests should hold a fake exporter across
Canvas-to-Studio handoff and cancellation and observe one durable URI.

### C7 — Every opened Canvas leaks a process-lifetime flusher worker

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

Each `CanvasViewModel` starts `TileFlusher` in the application scope and drops
the returned job. The worker waits forever on its channel; `onCleared` neither
closes nor cancels it. Repeated open/close cycles retain workers, queues,
buffer pools, and closures capturing painting state.

Give the flusher an explicit drain-and-close lifecycle. Canvas teardown must
finish the final checkpoint, close the queue, and join the worker without
accepting later work. Test termination and post-close rejection.

### C8 — An unreadable tracing reference is silently made disposable

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

`streamTracingReference` calls `applyTracingReference(null)` when decoding
fails. That makes the document dirty; the next checkpoint removes the
metadata-named committed asset. A transient read failure can therefore destroy
recoverable private bytes, contradicting the repository's explicit retention
rule.

Keep metadata and the panel intact, render no reference tiles, and show the
existing unreadable notice. Only explicit Replace or Remove may discard the
asset. Pin this with a source/behavior regression test.

### C9 — Gallery URI probe exceptions can crash sync

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

`GalleryExporter.sync` queries a recorded URI outside its exception boundary.
Lost ownership or a hostile/broken provider can throw `SecurityException`
before the documented reinsert decision. Canvas and Studio callers do not
contain it.

Treat an inaccessible recorded row as missing and follow the existing reinsert
path. Bound provider exceptions at the data abstraction, not in screens. Test
a resolver whose query throws.

### C10 — Failed project rename falls back to unsafe live deletion

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

`ProjectStore.delete` promises rename-first crash safety, but when the atomic
rename fails it recursively deletes the live UUID folder. Process death can
leave a normal-looking, partly destroyed project.

Return failure and preserve every source byte when staging fails. The UI
already has an honest delete-failure result. Test a forced rename failure.

### C11 — One failed journal sequence hides all later undo after reopen

**Severity:** medium-high. **Confidence:** high. **Disposition:** backlog.

Sequence numbers are consumed before enqueue/write. The live journal accepts
later increasing entries, while `HistoryStore` recovery stops at the first
gap. A transient failure at 41 can make valid 42 onward disappear on reopen.

Choose either contiguous allocation at successful publication or a persisted
skip record. Test success/failure/success/checkpoint/reload. Coordinate this
with C5 so two recovery formats are not invented independently.

### C12 — Duplicate Canvas destinations can write the same painting

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

Studio navigation does not use `launchSingleTop`. A fast double tap can push
two Canvas destinations and create two ViewModels for one project. Back then
reveals stale state, and independent checkpoints can overwrite newer work.

Make same-route navigation single-top and pin the navigation options in a
contract test.

### C13 — Newer or damaged paintings disappear from Studio

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

`ProjectStore.list` skips newer-format and unreadable `project.json` folders.
The bytes remain, but the child sees the artwork vanish. The persistence plan
requires a disabled, grey shelf cell with an explanation.

Return unavailable summaries without inventing document metadata. Show a
stable fallback title, reason, and disabled open action; retain management
actions that are safe, especially delete/export of raw project backup if one
is later added. Test newer format and malformed metadata independently.

### C14 — Persisted view and tool fields are dead

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

`ProjectFile` defines `view` and `lastTool`, but save omits them, load ignores
them, and Canvas always starts with the default brush and black. Reopen loses
zoom, rotation, selected tool, color, and size despite the format suggesting
otherwise.

Settle exactly which state is per-painting versus global first. Round-trip the
chosen fields and fall back to Fit when the saved viewport is incompatible.

### C15 — Non-Normal blend thumbnails do not match the painting

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

Thumbnail generation logs a warning and uses source-over for every blend mode.
Multiply, Screen, Difference, and Add cards can look materially wrong. Reuse
the CPU `Composite` oracle or the future band flatten. Fix C1 first.

### C16 — Delete removes the Gallery copy before proving project deletion

**Severity:** medium. **Confidence:** medium-high. **Disposition:** backlog.

Studio deletes the optional Gallery row, then attempts project deletion. A
project-delete failure leaves the painting but has already removed the user's
requested external copy. Reverse or transactionally coordinate the order;
confirm the intended failure semantics in the persistence plan.

## 2. Drawing, input, and frame pacing

### D1 — System-canceled pointer lifts can commit rejected ink

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

On Android 13+, palm rejection can mark an `UP`/`POINTER_UP` with
`FLAG_CANCELED`. `CanvasTouchHandler` checks only `ACTION_CANCEL`, so a flagged
lift emits a final sample and commits the stroke. The input plan explicitly
requires all flagged events to cancel and leave no trace.

Classify cancellation before the action switch. Test flagged Up and PointerUp
against ordinary lifts.

### D2 — Long segments silently lose their suffix when a dab batch fills

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

`DabGenerator.advance` stops when `DabBatch.add` returns false, but advances
its state to the sample endpoint. `CanvasScreen` supplies only one batch per
sample. At minimum spacing, a plausible fast segment exceeds 1,024 dabs; the
unwritten suffix becomes a visible gap and cannot be recovered.

Make segment generation resumable across ring batches without consuming
dynamics/seed state twice. A tiny-batch test must concatenate to the exact
large-batch reference, including pressure, spacing, and Chinese Ink state.

### D3 — Stationary finger gestures have no production clock

**Severity:** high. **Confidence:** high. **Disposition:** PR now.

`GestureArbiter.tick` implements the 120 ms draw deadline and 500 ms long
press, but production calls it only after Move. A still finger never begins
drawing and a stylus-only long-press eyedropper never fires. A quick finger
Down/Up also resets the pending state without emitting the required dot.

Install one cancellable deadline scheduler owned by `CanvasTouchHandler`.
Resolve a single-finger quick tap to Draw+End only when touch drawing is on;
do not affect multi-finger chords or stylus-only navigation. Test both
deadlines without Move and callback cancellation on Up/Cancel/reset.

### D4 — Prediction can take the final batch needed by real input

**Severity:** medium-high. **Confidence:** high. **Disposition:** PR now.

Prediction and digitizer input acquire from the same eight-slot ring. With
seven slots held, prediction can take the eighth; the next real sample or
pen-up then drops irreplaceable input, despite comments promising prediction
yields first.

Add an acquisition class, not a Boolean: `REAL` may use the reserve;
`PREDICTION` may not. Test seven loans, denied prediction, accepted real input.

### D5 — Smudge and Blur allocate inside dab/tile loops

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

`SmudgePass` constructs plans and rectangles per dab, unions into new objects,
and builds a scissor object per output tile. A dense 200-dab/four-tile frame
can create roughly 1,400 short-lived objects. Watercolor already demonstrates
retained primitive bounds.

Port that pattern with parity tests, then confirm allocations and frame time on
a device. Do not optimize only from a source count.

### D6 — Unsupported GLES/budget devices get a blank canvas

**Severity:** high. **Confidence:** high. **Disposition:** backlog.

`EngineSession.isSupported` is documented as feeding an unsupported-device
screen, but no UI reads it. A failed probe drains input and draws nothing.

Publish a one-shot capability result to Canvas state and show an explanatory,
recoverable screen with Back and device diagnostics. Test callback lifetime;
verify on an ES2/low-array-layer emulator before shipping.

### D7 — Reopen and fill need real performance measurements

**Severity:** high on large documents. **Confidence:** high. **Disposition:**
validate after C4.

Fill eagerly touches the full canvas and CPU flatten keeps a full output
buffer. Current accepted worst-case flatten is around 128 MiB; gallery sync,
share, and export can overlap. Measure p50/p95/p99 input-to-present, GL queue
depth, allocation rate, reopen-to-interactive, fill time, and memory on:

- a 120 Hz S Pen tablet;
- a mid-range phone;
- a low-memory class-B device;
- 4096², eight dense layers, Watercolor, Smudge, rapid zoom, and rotation.

The future GL band flatten remains the largest known memory win.

## 3. UI, accessibility, and convenience

### U1 — Choice rows expose duplicate or undersized actions

**Severity:** medium. **Confidence:** high. **Disposition:** PR now.

This is one repeated accessibility defect:

- New Canvas radio groups lack `selectableGroup`; both row and nested radio
  own clicks.
- The delete-Gallery label does not toggle its checkbox.
- Brush/RMW switch labels do not toggle their switches.

Make each row the single action owner, let the nested control delegate with a
null callback, and expose group semantics. Follow the already-correct Settings
and Fill patterns. Test semantics contracts.

### U2 — Canvas overlays and reference controls do not adapt to short windows

**Severity:** medium. **Confidence:** high. **Disposition:** PR now.

The storage-full banner uses generic bottom padding and can sit under the dock
or slider ledge. The Fill card already computes the correct clearance. The
Tracing Reference side panel is an unscrollable Column, so Done/Remove can be
clipped in a short window or at 200% font scale.

Share the overlay-clearance policy and make tracing controls vertically
scrollable while keeping the header stable. Test the pure padding policy and
verify a 300 dp-high, 200%-font configuration on device/emulator.

### U3 — Layer accessibility and font scaling are incomplete

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

Layer rows have a fixed 64 dp height despite two lines of text, transient
refusal messages have no assertive live region, and lock/alpha-lock/blend
states are visual-only in the menu. Use minimum rather than fixed height,
announce blocking feedback, and expose checked/selected semantics. This is a
good isolated accessibility PR after a maximum-font screenshot can be taken.

### U4 — Sliders announce fractions rather than artist-facing values

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

Brush size, Fill, rail size, mixing, and tracing opacity sliders omit useful
names/state descriptions. TalkBack can announce normalized 0–1 values while
the UI displays pixels or percent. Centralize slider semantics and speak the
same formatted value visible on screen.

### U5 — Palette selection and actions are partly visual-only

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

Palette selection is only a border, and built-in swatches advertise a long
press whose callback does nothing. Expose selected state; offer the labelled
edit/remove action only for user palettes.

### U6 — First-run hint likely hides its instructions from TalkBack

**Severity:** medium. **Confidence:** medium-high. **Disposition:** validate.

The full-screen parent is one clickable Button with content description
“Got it,” surrounding instructional text and a nested TextButton. Descendant
merging may reduce the experience to a duplicate dismiss action. Inspect the
semantics tree/TalkBack. Likely fix: pointer-only backdrop plus a semantic card
whose text and one explicit button remain reachable.

### U7 — Native Android Toasts bypass the planned in-canvas feedback system

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

Canvas feedback uses native Toasts although the UI plan specifies themed,
replacing, non-overlapping, stroke-deferred transients. This is why busy-work
feedback, rail clearance, and TalkBack timing are inconsistent. Build one
Canvas transient host before adding more ad-hoc notices.

### U8 — A busy document silently refuses a stroke

**Severity:** medium. **Confidence:** high. **Disposition:** backlog with U7.

`beginStrokeTool` returns null while an action is finishing. The pen moves,
nothing lands, and no reason is shown. Reuse the locked-layer notice path or
the future transient host with concise wording such as “Finishing the previous
change…”. Do not vibrate on every rejected Move.

### U9 — Repeated Studio render actions can multiply full-canvas work

**Severity:** medium-high. **Confidence:** high. **Disposition:** backlog.

Repeated share/export requests launch independent flatten/encode jobs and show
no busy state. Combine this with the gallery coordinator and future band
flatten: single-flight by project/action, cancel before the external mutation,
and disable or replace the initiating action with progress.

### U10 — Shelf refreshes can publish out of order

**Severity:** medium. **Confidence:** high. **Disposition:** PR now.

Every `StudioViewModel.refresh` launches an untracked job. Rename, duplicate,
delete, lifecycle resume, and gallery sync can overlap; an older result can
briefly resurrect deleted cards or old metadata.

Cancel the previous refresh or gate publication with a monotonically
increasing generation. A controlled delayed store must prove latest-wins.

### U11 — Studio startup walks every project file before showing the shelf

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

`ProjectStore.list` calls `folderBytes`, which walks all tiles, journal
payloads, and references before returning each Summary. Dense paintings can
contain thousands of files, contradicting the plan's metadata-only fast list.

Publish shelf metadata first, then calculate sizes asynchronously with an
explicit “calculating” state. Do not flash a false `0 B`.

### U12 — Canvas Settings closes the painting

**Severity:** medium. **Confidence:** high. **Disposition:** product decision.

Opening Settings from Canvas checkpoints, navigates to Studio, then opens the
sheet there. Closing Settings does not return to the drawing context. That is
especially awkward while tuning pressure or handedness. Prefer a route-level
sheet over the current destination, but settle whether live settings changes
may recreate the Surface before implementing.

### U13 — Clear Layer has no confirmation

**Severity:** medium. **Confidence:** high. **Disposition:** backlog.

Undo makes this recoverable, but it is one menu tap while Merge and Flatten
confirm. For a child-facing app, use a concise confirmation naming the layer,
or deliberately document why Undo is sufficient.

### U14 — Mixing position resets when the color panel is recreated

**Severity:** low-medium. **Confidence:** high. **Disposition:** backlog.

Preferences keep the two wells but not `DishState.t`; reopening the panel
recenters at 0.5. Persist `t`, clamped, with the wells or explicitly recenter
only when a well changes.

### U15 — Keyboard shortcuts are invisible

**Severity:** low-medium. **Confidence:** high. **Disposition:** backlog.

The app supports undo/redo, size, tools, reset, focus, Layers, Color, and Alt
eyedropper shortcuts but offers no help. Add a compact Shortcuts section in
Settings/About for Chromebook, DeX, and keyboard-cover users.

### U16 — Several small polish defects remain

**Severity:** low. **Confidence:** high. **Disposition:** backlog.

- Give the HSV marker a light and dark halo so it survives black/white corners.
- Hoist the eraser hover dash/path arrays out of the draw loop.
- Guard the About license intent when no activity can handle it.
- Age relative Studio timestamps while the shelf remains open.
- Decode 512 px thumbnails near their display size to extend the 16 MiB cache.
- Reuse the brush-preview bitmap to reduce settings-only GC churn.
- Confirm with an edge-to-edge screenshot whether Studio applies system insets
  twice before changing them.

## 4. Missing artist features

These are ordered by value to a young artist, not by novelty.

1. **Save a custom brush and favorite/reorder the rail.** The editor changes
   built-ins but cannot fork a brush into a named preset. Start with a simple
   mode (size, opacity, flow, hardness, stabilizer, mixing); put technical
   dynamics behind Advanced.
2. **Symmetry and kaleidoscope.** Highest delight-per-engine-seam in the
   roadmap. Offer horizontal, vertical, radial 4/6/8, and a visible axis.
   Journal one logical stroke, not N unrelated strokes.
3. **QuickShape and rulers.** Hold a stroke to snap a line/circle/rectangle;
   preserve the original until the snap visibly settles. Pair with the planned
   straightedge/ellipse guides.
4. **Pressure scratchpad.** Let pressure presets be felt in Settings before
   returning to the painting.
5. **Selection, transform, and image-as-layer.** Tracing references exist, but
   a real paint layer import and local transform are still absent. Keep Photo
   Picker as the permission-free boundary.
6. **Actual-size zoom, horizontal flip, grayscale/value preview, and layer
   solo.** These are fast, nondestructive inspection tools used constantly by
   artists.
7. **Export current layer and ORA project backup.** PNG layer export helps
   asset-making; ORA gives users a portable layered backup without accounts.
8. **Gradient fill and crop/resize.** Both are already planned and fit the
   existing document-action/journal model.
9. **Undo history sheet and per-painting history cleanup.** Show kind, layer,
   depth, and disk use; clearing history must never change pixels.
10. **Time-lapse/session playback.** The journal is a useful preview source,
    but true time-lapse needs stroke/event data rather than only tile deltas.

## 5. Aesthetic, delightful, and quirky ideas

These should remain optional and offline. No accounts, ads, streaks, cloud
dependency, public feeds, or manipulative gamification.

- **Idea Spark.** One quiet Studio card with offline prompts: “paint only with
  three colors,” “draw your room as a tiny planet,” or “invent a shy monster.”
  It disappears with one tap and never tracks completion.
- **Surprise-me brush dice.** Seeded, reversible mutations from a saved brush;
  preview before accepting so “surprise” never means lost settings.
- **Pigment recipes.** Describe the dish in friendly language — “mostly blue,
  a little yellow” — and let a drag physically smear the mix.
- **Tool-name coach.** On the first use of an ambiguous glyph, show a brief
  label by the rail. This teaches in context without another onboarding tour.
- **Paper personality.** Subtle procedural tooth and a restrained wet shimmer
  for Watercolor. Keep it stable in canvas coordinates and decide explicitly
  whether it exports. Any texture asset must be CC0 with provenance.
- **Edge resistance.** A small rubber-band overshoot makes panning feel
  physical, with reduced-motion respected.
- **Ambient shelf.** Let the newest painting cast a very faint, blurred color
  wash behind the Studio title. The art supplies the decoration.
- **Visible card menu.** Keep long-press, but add a small overflow affordance;
  children should not need to discover management by accident.
- **Favorites and little collections.** A star and optional folders become
  useful once the shelf grows. Avoid badges, counts, or productivity pressure.
- **Continue shortcut.** A launcher shortcut opens the latest painting
  directly. It reinforces the app's one-tap promise.

## 6. Visual direction

Keep:

- the canvas-first layout and physical handedness;
- warm paper/slate surfaces, saffron accent, and indigo brand field;
- sparse chrome, focus mode, generous touch targets, and artwork-led shelf;
- system light/dark theme without dynamic color.

Improve incrementally:

- make the new-painting cell slightly more inviting with a restrained
  `primaryContainer` tint;
- give shelf art a 1 dp physical lift and keep shadows out of the canvas;
- round only the dock's top corners so dock and side rail read as one object;
- size the HSV picker to the available panel width instead of stopping at its
  current 220 dp cap;
- add visible close affordances to tablet side sheets;
- consider one OFL display face for the Studio name and panel headers only,
  with provenance recorded. Do not use it for controls or canvas labels.

## 7. Acceptance gate before calling the app child-ready

Run the complete phone/tablet matrix from the plans, then add these focused
sessions:

- draw for 20 minutes with S Pen at 120 Hz: fast flicks, pressure dots,
  canceled palms, barrel button, eraser end, hover, and rotation;
- reopen a dense 4096²/eight-layer painting while trying to draw immediately;
- fill, Smudge, Blur, Watercolor, undo/redo, background/foreground, rotate, and
  force a GL context loss;
- fill storage, deny/revoke Gallery access, corrupt one tile/reference/JSON,
  kill the process at each checkpoint phase, and verify no dishonest save;
- TalkBack plus 200% font on the shortest supported window; keyboard-only and
  mouse-wheel navigation; left-hand mode; RTL locale without mirroring the
  physical rail;
- native zh-Hans wording review by a human;
- screenshot comparison across phone portrait, phone landscape, 7-inch
  tablet, 14-inch tablet, light, dark, transparent paper, and every panel;
- release-build upgrade from the published signing lineage.

Until that gate is run, source-level confidence is not a substitute for the
feel and safety of the actual drawing surface.
