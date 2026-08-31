# REVIEW.md — living review backlog

Findings from AI review rounds (GLM on PRs, periodic deep reviews) and
their dispositions. Stable IDs; nothing is deleted, only resolved or
declined with reasons. Point-in-time review snapshots archive under
`docs/reviews/`.

Legend: 🐞 bug · 🔧 improvement · ✨ idea · ⬜ open · 🟢 done · ⏸️ declined/deferred

## Open

_Nothing open._

## Declined

- **R-030 ⏸️ `explicitApi()` on `:engine-core` (PR #171, round 1).** The
  concern (API grows by omission) is real, but enabling it rewrites every
  public block-bodied declaration across the 15.7k moved lines — exactly
  the rewrite M1 forbids ("moves not rewrites"), and the module's surface
  is still moving (M2 extracts engine-gl; M4 consumes it). Revisit after
  the desktop port lands and the boundary settles.
- **R-031 ⏸️ Revert `internal` on "unused" widened declarations (PR #171,
  round 1).** Refuted: every widened name came from the compiler's
  "Cannot access … it is internal" set — :app code or tests reference all
  of them (RenderAttachmentGate, RedrawCompletionTracker,
  PendingBatchDrainWindow included), so reverting any breaks compilation.
  The mechanical flip is the minimum widening.

## Resolved

- **R-020 🟢 The NaN/Infinity-token decode case.** Deferred from PR #7 until
  the `data/` loader existed; landed with 3a. `ProjectStore`'s `Json` sets
  `allowSpecialFloatingPointValues`, because kotlinx's default decoder throws
  on the token itself, *before* `LayerRecord.toProps`'s degrading code is
  ever reached — without the flag, 06 §4's "one bad field must never fail an
  open" could not hold. `LayerProps.sanitizeOpacity` then degrades the value
  (NaN and −∞ to fully visible, +∞ clamps to 1). On the write side the flag
  is inert: `LayerProps` refuses a non-finite opacity at construction, so
  nothing here ever encodes one. `ProjectStoreTest`'s NaN/−Infinity case
  pins the decode path.

- **R-029 🟢 (the collision half — the velocity findings recorded under this
  id below stay refuted) `ProjectStore.load` degrades on a case-insensitive
  layer-id collision rather than throwing.** Landed with 3a. `LayerStack`
  still refuses the pair at construction — the right answer for code
  *building* a stack — while the loader folds ids locale-independently,
  keeps the first claimant, drops later ones into the unreadable-layers
  count, and opens the document. That count travels beside 06 §4's
  unreadable-tiles count, never folded into it (a lost layer shown as N lost
  tiles misleads); R-001's layer-id→path policy shares the count and the
  same never-fail-the-open shape, and `ProjectStoreTest` pins all of it.

- **R-046 ⏸️ `presentToWindow` should bind its texture through `GlState`.**
  (PR #11, GLM round 1, info: "`onContextLost()` calling
  `state.forgetAllTextures()` strongly suggests `GlState` caches texture
  bindings".) **Refuted:** it does not. `GlState`'s only per-texture state is
  the sampler **filter** (`GL_NEAREST` vs `GL_LINEAR`, §3.4), and
  `forgetAllTextures` exists because a deleted texture's id can be recycled
  while the filter map still claims that id was configured — the hazard
  `TilePool.release` takes a `GlState` for. There is no binding cache to go
  stale, so routing the present bind through `GlState` would add an indirection
  that tracks nothing. A comment now says so at the call site, since the
  inference is a reasonable one to make twice.

- **R-045 ⏸️ Programs sharing a VAO must pin attribute locations with
  `glBindAttribLocation`.** (PR #11, GLM round 1, info, outside the diff.)
  **Refuted:** they are already pinned, in the shader source rather than at link
  time. `Shaders.COMPOSITE_VERT` declares
  `layout(location = $ATTR_POS) in vec2 a_canvas` and
  `layout(location = $ATTR_UV) in vec3 a_uvw`, interpolated from the same Kotlin
  constants the VAOs bind, and `GlShaderContractTest` asserts both lines
  literally plus `ATTR_POS != ATTR_UV`, and `GlslDeclarationOrderTest` fails any
  vertex `in` that has no `layout(location = …)` at all and any location claimed
  twice. `glBindAttribLocation` would be a second, weaker statement of a
  contract three tests already hold.


- **R-043 ⏸️ `LayerTextures.upload` should require a *direct* `ByteBuffer`.**
  (PR #10, GLM round 1, minor.) **Refuted.** The premise — "`glTexSubImage3D`
  requires a direct `ByteBuffer`" — is not true of the `android.opengl`
  bindings. Their generated JNI resolves a buffer through `getPointer()`, which
  handles a *direct* buffer and an array-backed heap buffer alike (the latter
  via `GetPrimitiveArrayCritical`); only a buffer with neither — a read-only or
  non-array-backed wrapper — is rejected, and the binding raises its own
  `IllegalArgumentException` naming that. Applying the suggested `isDirect`
  guard would therefore *reject valid input*: `ByteBuffer.allocate(262144)` is
  a perfectly good tile buffer, and refusing it would force a copy per tile on
  exactly the §12 reopen path this PR just removed a redundant clear from. The
  finding's stated goal — fail fast, near the mistake — is already met for the
  case that can actually fail.

- **R-042 ⏸️ `LayerTextures.swap` must guard against freeing `SliceHandle.NONE`.**
  (PR #10, GLM round 1, minor: "only safe if `TilePool.free` treats `NONE` as a
  no-op — nothing in this file guarantees that".) **Refuted:** it is guaranteed,
  in this very PR. `SliceAllocator.free` opens with `if (handle.isNone) return`,
  its KDoc states the contract ("`SliceHandle.NONE` is accepted and ignored:
  'free whatever this index held' is the shape of every caller in
  `LayerTextures`, and a dense index is full of `NONE`"), `TilePool.free`
  delegates straight to it, and `SliceAllocatorTest`'s
  `freeing NONE is a no-op, freeing a foreign handle is not` pins it — with a
  `freeCount` assertion added this round so the pin cannot pass while the free
  stack is corrupted. The finding reads the guarantee as absent because it
  looked only within one file; adding a second, redundant check at the call site
  would suggest the contract is unreliable and invite the next caller to guard
  too.

- **R-041 ⏸️ `GlErrors.reset()` should also re-state `strict`.** (PR #10, GLM
  round 1, info.) Declined. `strict` is not session state: it is set once from
  `BuildConfig.DEBUG`, a build-time constant that cannot differ between two
  sessions in one process, so there is nothing for a new session to re-state.
  The suggested signature — `fun reset(strict: Boolean = this.strict)` — assigns
  the field to itself at every existing call site, which reads like it does
  something and does not. `reset()` is about the once-per-session log
  suppression, and keeping it about only that is what makes its one line
  obvious.

- **R-044 ⏸️ Split 2.3a further, at GL wrappers vs the `engine/core` twins.**
  (PR #10, GLM round 1, major — raised as part of the roadmap size finding.)
  The *measurement* half of that finding was **applied**: the paragraph in
  `12-roadmap.md` claimed compliance with the ~1,500-line criterion while
  reporting ~2,570 for one half, which is a contradiction, and it now states
  the real figures (~1,730 code, ~950 tests) and records the overrun against
  §1's M band rather than glossing it. The *further split* is declined, with
  the reasons written into that document: rule 1's remedy is a **named** seam,
  and both candidates produce a half nobody can review as a unit — the twins
  (397 lines) would land with nothing calling them, and `Shaders`-plus-tests
  versus the pool plumbing cuts at a seam the roadmap never named and leaves
  two halves that both fail to draw.


- **R-038 🟢 `DabGenerator`'s radius clamp threw on a sub-pixel preset.** (PR
  #9, GLM round 4, raised as a BLOCKER.) Real, and a crash on the drawing
  path: `minRadius` was floored at `Dab.MIN_RADIUS` and `maxRadius` ceiled at
  `Dab.MAX_RADIUS` with nothing keeping the two in order, so
  `coerceIn(minRadius, maxRadius)` threw `Cannot coerce value to an empty
  range` on the *first dab of every stroke* for such a preset.
  `BrushPreset.MIN_SIZE` is half a pixel of **diameter** while `Dab.MIN_RADIUS`
  is half a pixel of **radius**, so a perfectly legal brush of 0.5..0.6 px gave
  a min of 0.5 against a max of 0.3. Reproduced before fixing.
  The finding's own stated path — `sizeMin / 2 > Dab.MAX_RADIUS` — is
  unreachable, since `BrushPreset.MAX_SIZE / 2` is exactly `Dab.MAX_RADIUS`;
  the small end is where it bites, which is the half the finding did name.
  Fixed by flooring `maxRadius` at `minRadius`: a brush smaller than the shader
  can draw means "always the smallest dab", not an error. `DabGeneratorTest`
  pins it.

- **R-001 🟢 Layer ids reach the filesystem unvalidated.** Raised in rounds 2,
  6, 7 and 8; declined three times, **applied in round 8** — recording the
  change of position rather than making it silently. My decline rested on one
  claim: that a `require` at the deserialization boundary contradicts
  `06-document-and-persistence.md` §4's "one bad tile must never fail an open".
  Round 8 rebutted exactly that claim, and it is right: §4's degrade rule is
  about values with a sane fallback — a tile degrades to transparent, an
  opacity to 1 — whereas a malformed layer *id* has no meaningful degraded
  value at all, because it is the key every history reference and tile lookup
  is resolved through. A document with one is corrupt, not partially readable.
  That is new reasoning, not repetition, so it is not a flip-flop.
  `LayerId` now enforces the single-path-segment contract its own KDoc already
  promised. Deliberately a path-segment floor (no `/`, `\`, `:`, NUL, not
  empty, not `.`/`..`) rather than the suggested UUID regex: the security
  property is "cannot escape the project folder", and a UUID check would break
  every fixture id for no added safety. The architectural half of the original
  decline still stands and stays in the roadmap: step 3 must not build a path
  by concatenating untrusted text, and `ProjectStore.load` must catch this per
  layer and drop it into §4's unreadable tally rather than failing the open.
  **That policy half landed with 3a**: `toPropsOrNull` is the per-layer catch,
  the dropped layer goes into the unreadable-layers count (kept beside, never
  inside, the tile count), and `ProjectStoreTest` pins that a traversal-shaped
  id never reaches a path join and never fails the open on its own.

- **R-004 🟢 Gate PR 2.5 on `PredictorTest` and `TailBufferTest`.** (PR #7, GLM
  round 4.) The *observation* was right and was acted on: 2.5 was the only row
  in the breakdown with no automated gate, while "no hook on pen-up" is one of
  the step's named risks. The *suggested tests* are refuted, with evidence:
  `docs/plan/11-testing.md` does not mention prediction anywhere, `Predictor`
  is a wrapper whose stated purpose is that "core and tests never see the
  androidx type" (`02-architecture.md` §2.6), and `TailBuffer` is an
  `engine/gl` GPU object (§2.3) — neither holds logic a JVM test could pin, so
  naming those files would invent tests for a shim and a texture. What is
  genuinely pure is the *stabilizer copy* the tail runs through (`04-tools.md`
  §4): the tail must continue the stabilized line and must never advance the
  real stabilizer state. 2.5 now gates on those cases in `StabilizerTest`, and
  says why the rest is device-gated. 🟢

- **R-003 🟢 `Composite.tile` skips a tile the model lists.** (PR #7, GLM round 3.)
  Raised as inconsistent leniency — `tile()` raises on a wrong-sized tile but
  silently treats a `null` read as transparent. Resolved by *documenting* the
  contract rather than by raising: a `null` is legal and expected (an
  unreadable tile file per `06-document-and-persistence.md` §4, a readback not
  yet landed), and `tile()` is the flatten and export path, so a painting with
  one bad tile must still open. Raising would be R-001's mistake again. The
  `TileReader` and `Composite.tile` KDocs now say which is which. 🟢

- **R-006 🟢 `maxLayers` could exceed what `TilePool` can allocate.** (PR #7,
  GLM round 6.) `maxLayersFor` divided the raw byte budget, but the pool hands
  out whole 64 MiB texture arrays, so up to one array of the budget is not
  actually available. A device reporting 2800 MiB (350 MiB budget) on a 2304²
  canvas advertised 16 layers = 1296 tiles against a 5-array pool holding
  1280 — and the KDoc promises the dialog and the layer panel never disagree.
  Both the cap and `maxCanvasEdge` now derive from `arrays × slices ×
  TILE_BYTES`. The pinned worked table is unchanged, because every budget in
  it is already an exact multiple of an array; `MemoryBudgetTest` now covers
  odd budgets and 128-slice drivers so the two can never drift apart again.

- **R-005 🟢 Type `HistoryEntry`'s id fields as `LayerId` rather than `String`.**
  Raised in rounds 5, 7 and 9; declined each time, **applied in round 10** —
  recording the change of position rather than making it silently, as with
  R-001.

  My decline rested on reading
  `docs/plan/06-document-and-persistence.md` §5.2 ("**This section is normative
  for `HistoryEntry`** — names, fields and the on-disk encoding") as binding on
  the Kotlin field types. It is not, and R-001's resolution is what showed why:
  the sentence binds the *encoding*, and a `@JvmInline value class` over
  `String` encodes as the same string. `history/<seq>.entry` is byte-identical
  either way, so nothing normative moved.

  What decided it is the trust boundary R-001 established. An entry is read
  back from a file a user can hand-edit, and its ids are joined into
  `layers/<id>/` paths on redo. Round 8 accepted that a `LayerId` from
  `project.json` must be validated at construction; an id from
  `history/<seq>.entry` arrives through the same door and had no such guard.
  Typing the fields closes it at the type level instead of asking every future
  call site to remember.

  The name collision raised in the same round (`HistoryEntry.LayerProps` vs the
  model's `LayerProps`) stays declined for the original reason — §5.2 *is*
  normative for the kinds' names — and is recorded in AGENTS.md as a
  convention rather than left as a surprise.

## Declined, with reasons

- **R-093 ⏸️ Tick the settings-open tap on the rail's smudge/blur/eyedropper
  slots.** (PR #24 round 3.) The new "tap an already-active RMW tool to open
  its settings" path skips the haptic tick that `switch()` gives tool
  *changes*. Declined: the established settings-open gesture — the brush
  slot's `if (active) onSettingsRequested()` — deliberately does not tick
  either, and `08-ui-and-layout.md` §5.1's haptic list (tool switch, drag
  reorder, rotation snap, reset arrival, slider detents, hue detents,
  long-press menus) does not include settings-open. Ticking only the three
  new slots would make them inconsistent with the brush slot; ticking all
  four is a change to a shipped interaction the plan does not call for.

- **R-010 ⏸️ (third raising, first on PR #9) "`assertIs` does not smart-cast,
  so `kind.preset` no longer resolves and the test source set fails to
  compile."** Refuted, and this raising is the easiest of the three to settle
  because it predicts something checkable about the very commit it is
  reviewing. `019a22e`'s `android` check is **green**, and
  `TEST-…BrushPresetTest.xml` records `an erase preset is a preset, not a
  kind` running in 0.014 s — the file the finding says cannot compile compiled,
  and the test it says never runs ran. The mechanism is the same one refuted on
  PR #7 in rounds 7 and 11: `kotlin.test.assertIs` is declared
  `contract { returns() implies (value is T) }`, which *is* a smart cast, and
  discarding the return value does not discard the contract.
  The suggested shape — `val brush = assertIs<ToolKind.Brush>(kind)` — is
  perfectly good Kotlin and would compile too. It is declined anyway, because
  the reason offered for it is false, and rewriting working code to satisfy a
  claim that CI disproves on the same SHA is how a false finding becomes
  precedent.

- **R-040 ⏸️ Round 5: "`notePressure`'s KDoc contradicts the implementation
  quoted in REVIEW.md".** Refuted on the code, and the finding's own fallback
  applied. It reads `if (x.isNaN()) 0f else …` as a literal return, and it is
  not: the `0f` is assigned to `t`, the x coordinate the lookup falls back to,
  and `lut[t]` — `lut[0]` — is returned. So NaN really does map to the curve
  *at* x = 0, not to zero: on `Curve.floor(0.3f)`, `lookup(lut, NaN)` is
  `0.3`. Verified by running it before answering. The KDoc stands as written;
  the finding says "if it instead returns the curve's value at x=0, leave the
  KDoc unchanged and fix the quoted snippet in R-039" — which is exactly the
  case, and R-039's quote is now the full expression rather than its first
  half.

- **R-039 ⏸️ Round 4's major: "NaN pressure is sanitized in `notePressure` but
  fed raw into the spacing math, permanently poisoning `carry`".** Refuted, by
  running it. The finding is explicit about its premise — "*If* `Curve.lookup`
  propagates NaN (as a plain interpolation would)" — and it does not.
  `lookup` opens with `val t = if (x.isNaN()) 0f else x.coerceIn(0f, 1f)`, and
  that `0f` is the *x coordinate* it falls back to, not the value it returns:
  it then returns `lut[t]`, the curve **at** x = 0. On `Curve.floor(0.3f)`,
  `lookup(lut, NaN)` is `0.3`, not zero. (Round 5 read the shorter quote that
  stood here as a literal return and filed the mismatch as R-040; the quote
  was the misleading half, so it is written out in full now.) Fed a NaN-pressure
  sample mid-stroke, the generator emitted 11 → 111 → 211 dabs across the
  segments before, during and after it, and no dab had a non-finite radius.
  The reasoning about what a NaN `carry` *would* do is correct and is exactly
  why the guard sits where it does. `notePressure` guards separately because
  it feeds a `max` rather than a curve — now said in its KDoc, since the
  asymmetry is what made this look like an omission.

- **R-029 ⏸️ (second raising) `velocity / fastPxPerMs` is 0/0 when
  `fastPxPerMs` is 0.** Refuted again on the same ground, now checked rather
  than argued: `VelocityEffect(sizeAtFast = 0.5f, fastPxPerMs = 0f)` throws
  `velocity fastPxPerMs must be positive, was 0.0` from the type's own `init`.
  The zero cannot reach the division. The general point that `coerceIn` does
  not filter NaN remains true and remains the reason the surrounding code
  tests `isNaN` explicitly.

- **R-037 ⏸️ Guard the golden harness's deferred read with
  `check(dabs.size == total)`.** The concern is fair; the suggested check
  cannot fail. `total` is `batches.sumOf { it.count }` and `dabs` is
  `batches.flatMap { b -> List(b.count) { … } }`, so `dabs.size` *is* that
  same sum, by construction — the two sides are one expression written twice.
  It would not even catch the recycling it is for: a batch cleared after
  publication would report its new count on both sides and the equality would
  still hold. Applied in the form that does work: each batch's count is
  recorded when it is published and compared when it is read, so a count that
  moved in between fails with both numbers. Worth noting that this is the same
  defect class the round-1 review caught in the batch-split test and that
  three of this PR's own tests had — an assertion whose two sides come from
  one computation.

- **R-036 ⏸️ (deferred) `BrushPresetStore`'s `Json` must set
  `ignoreUnknownKeys = true`, and decide what a preset failing `init` does on
  load.** Correct, and not declinable — the store does not exist yet, which is
  why `BrushPreset`'s KDoc names the debt rather than paying it. The finding
  observes that the load path got *stricter* this round (the `Curve` knot range
  and the `eraseMode && mixing` check), which makes the store's throw-handling
  matter more: a hand-edited preset now has more ways to fail construction, and
  whether that drops one brush or fails the whole load is the store's call.
  Both are now in `docs/plan/12-roadmap.md` as gates on that PR, alongside the
  RMW spacing floor. Deferred, like R-020 and R-029 before it, rather than
  declined.

- **R-035 ⏸️ Round 2's two BLOCKERs: "Use of eval() detected" at REVIEW.md:105
  and :108.** Refuted, and self-demonstrating: those two lines are R-034's
  entry *explaining why the eval finding is a false positive*, and they match
  because they contain the string `Curve.eval`. The scanner has now flagged the
  prose refuting it. Nothing to apply, in a Markdown file the JVM never loads.

- **R-034 ⏸️ PR #9 round 1's 26 BLOCKERs: "Use of eval() detected. This is
  unsafe and should be avoided."** Refuted, all twenty-six. They are a
  substring match on the identifier `eval`, which here is
  `Curve.eval(x: Float): Float` — a Catmull-Rom evaluation of four float
  knots, on a `@Serializable data class` in a module that has no scripting
  engine, no `ScriptEngineManager`, no reflection and no dynamic loading. The
  JVM has no `eval` to call. Fourteen of the twenty-six are in a *test file*
  calling that same method. Nothing was changed, and nothing could be: the
  only "fix" is renaming a well-named method to evade a regex.

- **R-033 ⏸️ Raise `SmudgeParams.spacing`'s default to the RMW floor of
  0.25·r.** Declined: the plan writes the default as `0.16f` *and* the floor
  as 0.25·r, and both on purpose. `03-canvas-engine.md` §7.6 says
  "`DabGenerator` therefore enforces a minimum spacing of 0.25·r for RMW tools
  **regardless of preset**" — the floor is the generator's, applied to
  whatever the preset stores, exactly as the dab step is already floored at
  half a pixel for brushes. Raising the stored default would deviate from a
  normative table to duplicate a rule that belongs one layer down. Carried
  forward instead: the RMW branch of `DabGenerator` owes that floor when
  smudge and blur land, and it is now in the roadmap's step-4 notes.

- **R-032 ⏸️ Make `DabRing.release` ignore a batch it never handed out.** The
  hazard is real and the fix is wrong for it. The finding's case is the
  allocating fallback batch from `acquire`'s backpressure path being routed
  back into `release`; returning silently would make that a no-op. But a batch
  arriving at `release` that the ring never owned means the caller has lost
  track of which batches are pooled, and the next thing it loses track of is a
  real slot — which leaks silently and starves the ring under exactly the load
  that produced the fallback. The throw is the guard. Applied instead: the
  KDoc now states that the fallback batch never returns through `release`, so
  the contract is written down rather than inferred.

- **R-031 ⏸️ Guard `dilution > 0` when `mixing` is false.** Half declined. The
  `eraseMode && mixing` half is a genuine contradiction — two different merges
  — and is applied. `dilution` with mixing off is not a contradiction, it is
  an inert field: it reads as "if this brush ever mixes, yield this much".
  Requiring it would make the settings sheet's own mixing toggle throw, since
  a `copy(mixing = false)` on a diluted preset would no longer construct — the
  finding says as much, and asks the UI to compensate. A validation whose
  precondition is "and change every caller so it cannot fire" is not a
  validation.

- **R-030 ⏸️ Round 1's major: "Tap fix-up raises the dab's flow but not
  `pressureOpacityMax`".** Refuted. The finding says the only `notePressure`
  before `end()` came from `emit` with the down sample — but `advance` calls
  `notePressure(next.pressure)` on every sample, as its first statement,
  precisely so a peak falling between two dabs is not lost. For the tap the
  finding describes, the ramp sample reaches `advance`, the ceiling is already
  at the ramp's pressure, and `end()` has nothing left to raise. The
  underlying worry — a tap that produces no `ACTION_MOVE` at all — is real but
  is not a bug here: if no sample ever reported a higher pressure, there is no
  higher pressure to recover. What the code did owe was saying whose job the
  pen-up sample is, and `end()`'s KDoc now says it.

- **R-029 ⏸️ Round 1's major: "NaN velocity ratio when `fastPxPerMs` is
  non-positive".** Refuted on the premise. `VelocityEffect`'s `init` requires
  `fastPxPerMs.isFinite() && fastPxPerMs > 0f`, so the zero the finding
  divides by cannot reach the field — "left uninitialized in a bad preset" is
  not a state this type has. The general observation is correct and worth
  keeping in mind: `Float.coerceIn` does *not* sanitise NaN, because both of
  its comparisons are false for it. That is why `tiltFraction` and
  `notePressure` test `isNaN` explicitly rather than relying on the clamp, and
  why a non-finite coordinate is refused at `IntRect.forDab` rather than
  clamped there.

- **R-025 ⏸️ `isIdentifierIgnorable` misses part of the Cf category
  (U+00AD, U+061C).** Refuted, by execution rather than by reading. The finding
  states the predicate "only covers a fixed subset of Cf: U+200B–U+200F,
  U+202A–U+202E, U+2060–U+206F and U+FEFF". `Character.isIdentifierIgnorable`'s
  actual contract is "a non-whitespace ISO control, **or** general category
  FORMAT", so it is exactly `isISOControl`-subset ∪ Cf. Run on this JDK,
  U+00AD and U+061C both return `getType == FORMAT (16)` and
  `isIdentifierIgnorable == true` — they were already rejected, and switching to
  `category == CharCategory.FORMAT` would have changed nothing. The comment the
  finding calls stale was correct; it now says *whole* Cf and names the two
  characters, so the next reader does not have to re-derive this.
  The finding's own parenthetical follow-up was right and is applied: U+2028 and
  U+2029 are Zl and Zp, outside both predicates, and end a line in a log or a
  JSON dump exactly as `\n` does. They are now rejected explicitly.

- **R-026 ⏸️ Replace `GPU_TILE_FRACTION = 0.125` with a Long numerator and
  denominator.** Declined. The hazard is real but hypothetical — `Double`
  multiplication is exact for 1/8 at any byte count a device can report, and
  the finding says as much ("low today") — while the cost is immediate:
  `10-performance.md` §4 writes the rule as `totalMem × GPU_TILE_FRACTION
  (1/8)` and pins the resulting table, so splitting the constant in two makes
  the code stop matching the normative text it implements. And the guarded-
  against edit is not a one-character slip: any change to the fraction has to
  move that table too, which is where it would be caught. Declining the shape
  change, not the concern.

- **R-027 ⏸️ Vary `glMaxTextureSize` in `CanvasPresetsTest`'s device helper.**
  Declined, and this is R-009's ground restated: `maxCanvasEdge` is bounded by
  memory and by the v1 ceiling and deliberately *never* by `glMaxTextureSize`,
  because tiles are 256 px — a big canvas never needs a big texture. That is
  stated at `MemoryBudget.kt`'s edge calculation, which is the "flag it as
  currently unconsumed" outcome the finding itself asks for in that case. A
  test sweeping the field would assert that an input nothing reads changes
  nothing. It becomes live when `03-canvas-engine.md` §3.2's viewport-sized
  `Accum`/`Scratch` targets land in PR 2.3, which is where R-002 already
  records it.

- **R-028 ⏸️ Pin the default preset's identity to `PHONE_SKETCH`.** Declined on
  the finding's own condition. `CanvasPresets.defaultIndex` derives the default
  — the largest *enabled* preset by tiles — rather than naming one, and no plan
  section fixes it to a particular id; `11-testing.md` §291 names only the
  behaviour the test already asserts. Pinning `PHONE_SKETCH` would pin an
  outcome of the derivation on one device tier as though it were the rule, and
  asserting the derivation instead would just restate the implementation.

- **R-023 ⏸️ Round 13's info item: "`withOpacity` must guard against NaN, not
  just clamp".** Refuted. The finding is explicitly conditional on a file it
  could not see — "if `withOpacity` is implemented with `opacity.coerceIn(0f,
  1f)` (the obvious choice)" — and it is not. `LayerProps.withOpacity` calls
  `sanitizeOpacity`, which tests `value.isNaN() || value == NEGATIVE_INFINITY`
  before it clamps, and `LayerRecord.toProps` calls the same helper so the
  setter and the decode boundary cannot drift. The reasoning about what a NaN
  would do downstream is correct; the premise is not. `LayerStackTest` already
  pins NaN, ±∞ and out-of-range through both doors.

- **R-022 ⏸️ Pin AGENTS.md's `LayerId` decode-guard claim with a test.**
  The mechanism raised is refuted, the ask is applied. Refuted: the concern is
  that kotlinx-serialization may construct a `@Serializable @JvmInline value
  class` without running its `init`, but `LayerId` is not `@Serializable` and
  never reaches a serializer. The serialized type is `LayerRecord`, whose `id`
  is a plain `String`; the guard runs in `toProps`, at the `LayerId(id)` call.
  Applied anyway, because the underlying ask is right and cheap: the claim was
  asserted in prose while the test that pins it sat unreferenced, so AGENTS.md
  now names it and says why no serializer sees the value class.

- **R-021 ⏸️ Couple the flat low-RAM tile grant to `totalMemBytes`.** Refuted,
  with the reviewer's own fallback applied. The premise — that a 1 GiB low-RAM
  device gets "double the share every other device gets" — is wrong, because
  the general branch is clamped: `LOW_RAM_GPU_TILE_BYTES` and
  `GPU_TILE_MIN_BYTES` are both 256 MiB, so a 1 GiB device gets 256 MiB either
  way (1 GiB × 1/8 = 128 MiB, raised to the floor). The flag removes the
  *ceiling*, not the floor: at 4 GiB it is 256 MiB against 512 MiB, which is
  the halving `10-performance.md` §4 pins. The suggested `minOf` would take the
  smallest devices *below* a floor every other device keeps. The finding's
  alternative — say the floor is deliberate rather than leaving it to fall out
  of a constant — was the right call and is now a comment at the branch.

- **R-024 ⏸️ Consolidate R-014's and R-015's duplicate entries.** Declined on
  this file's stated policy: it is an append-only log — "nothing is deleted,
  only resolved or declined with reasons" — and each raising is dated evidence
  of how often a finding came back, which is exactly what the dismissed-only
  stop rule is scored on. Merging them would erase that. The half of the
  finding that was a real defect is applied: the cross-references were
  position-relative ("the reason recorded below"), and R-015's ordinals did not
  say whether they counted R-015's raisings or the finding's. Both now say.

- **R-015 ⏸️ (fifth raising) Vary `largeMemoryClassMb` and the GL caps in
  `CanvasPresetsTest`'s device helper.** Declined again on the same ground, now
  stated once and for all: `MemoryBudget.compute` does not read
  `largeMemoryClassMb`, so sweeping it asserts that an unread number changes
  nothing. The GL half is different and already covered — `glMaxArrayLayers`
  does shape the pool, and `MemoryBudgetTest` sweeps it at 256, 128 and 64
  across every canvas and device; `CanvasPresetsTest` tests preset *selection*,
  which sits above that.

  **On the ordinals: do not trust them, and here is the audit.** Round 13's
  note claimed they count R-015's own raisings. They do not, consistently: the
  unlabeled entry below calls itself "third raising of R-002's substance" — the
  *finding's* count — while the heading ordinals that follow it start at
  "third" again. No entry is missing; the two conventions were mixed, so the
  arithmetic reconciles under neither and no ordinal here is load-bearing. What
  is checkable is the round each entry names, so that is the record: rounds 2
  and 2's test note (under R-002), then the unlabeled entry below, then the
  three labelled entries, then this one — six declines before round 14, which
  raised it a seventh time as a `MemoryBudgetTest` fixture note and got the
  reason written into the fixture itself. Future entries cite the round, not an
  ordinal.

- **R-014 ⏸️ (third raising) `LayerId` should reject Windows reserved device
  names.** The trailing-dot-and-space half of round 12's finding was applied and
  is the one that mattered: Win32 strips those silently, so `layers/sketch /`
  becomes `layers/sketch/` on a copy and the layer loads empty with no error
  anywhere. Reserved names stay declined — `CON`/`NUL` fail *loudly* on Windows
  rather than silently, detecting them properly needs a case-insensitive
  stem-and-extension parse, and nothing in this project hands a layer id to a
  Windows filesystem.

- **R-018 ⏸️ Rename `HistoryEntry.LayerProps` and `LayerDuplicate.copy`.**
  Declined: `06-document-and-persistence.md` §5.2 is normative for the entry
  kinds' *names* and their fields, and both are what it calls them. The
  collisions are real — the nested class shadows the model's `LayerProps`, and
  `entry.copy` (a record) sits beside `entry.copy(...)` (the generated clone) —
  and both are recorded in AGENTS.md as conventions rather than left as
  surprises. A rename would deviate from a normative declaration to buy
  readability a qualified name and an import alias already buy.

- **R-019 ⏸️ Move the undo formula onto `HistoryEntry.LayerMerge`'s KDoc.**
  Refuted: it is already there and has been since the entry was written. The
  KDoc states it as "restore `lowerTiles` **and delete `upperTiles − lowerTiles`
  from it**", which is the same reconstruction as the finding's
  `(merged.tiles − upperTiles) + lowerTiles` — verified equivalent over 3000
  random tile-set pairs in both the `bakesWholeBottom` branches. The finding's
  premise, that the formula "currently exists only inside this method's
  comment", is false.

- **R-020 ⏸️ Add a decode-path test for a NaN token in `project.json`.**
  Deferred, not declined: the test needs the loader's `Json` instance, and
  `data/` does not exist until roadmap step 3. Round 11 already reworded the
  comment that overclaimed reachability into a requirement on that loader, and
  12-roadmap.md's step-3 carry-in list is where it will be executed.

- **R-010 ⏸️ (second raising) Round 11's BLOCKER: "`assertIs` does not
  smart-cast, so `ok()`/`refusal()` do not compile".** Refuted in round 7 and
  again now, on the same evidence: `kotlin.test.assertIs` carries the contract
  `returns() implies (value is T)`, which is precisely a smart-cast, and the
  helpers have compiled on every one of the eleven heads this PR has had. The
  finding's own stated impact — "this file does not compile, so all 653 lines of
  layer tests are dead and CI is red for the whole module" — is checkable and
  false: `android` is green on `df303d0`, and `testDebugUnitTest lintDebug
  assembleDebug` passes locally at 108 tests. Rewriting the helpers to consume
  the return value would be harmless, but applying a change to satisfy a claim
  that is demonstrably untrue is how a false finding becomes precedent.

- **R-015 ⏸️ (fourth raising) Vary `largeMemoryClassMb` in the device helper.**
  Declined again, and this time the finding declines itself: it says to apply it
  "only after confirming `MemoryBudget.compute` actually consumes
  `largeMemoryClassMb`; if it doesn't, the current hard-coding is fine as-is and
  this can be closed." It does not consume it. Closed on the reviewer's own
  terms. See R-002.

- **R-014 ⏸️ (second raising) `LayerId` should reject Windows reserved device
  names.** The *character set* half of round 11's finding was applied — `*?<>|"`
  now join `:` and `\`, which were already there for exactly the
  copied-between-machines reason, along with a 255-byte NAME_MAX bound and
  `isISOControl()`. The reserved device names (`CON`, `NUL`, `LPT1`, …) stay
  declined: they are a Windows *shell* hazard, not a path hazard, they need a
  case-insensitive stem-and-extension parse to detect properly, and no code in
  this project ever hands a layer id to a Windows filesystem. The finding itself
  scopes them out as beyond "the cheap 90%".

- **R-016 ⏸️ Add `TileGrid.keysForPacked(r, out: IntArray, offset)`.** (PR #7,
  GLM round 10.) The premise is right — `keysFor` appends boxed `TileKey`s to a
  `MutableList`, and a value class boxes as a generic argument, so it is not an
  API a per-frame path may call. But nothing calls `keysFor` per frame yet: the
  dirty-tile upload and the touch handler are PR 2.4, and `TileKey.packed` is
  already public, so the allocation-free version can be written when it has a
  caller to shape it. Landing it now means an untested-in-anger API whose
  buffer-sizing contract (who allocates `out`, how big, what happens on
  overflow) is guessed rather than derived from use — the roadmap's "one
  coherent area" rule points the other way. Recorded so 2.4 does not reach for
  `keysFor` by reflex: **`keysFor` is not for per-frame paths**, and its KDoc
  says so.

- **R-011 ⏸️ (second raising) `fits()` should also require
  `maxLayersFor(...) >= 1`.** Still declined, for the reason recorded below,
  but round 10 offered a fallback the original raising did not — "if
  `MemoryBudget` already guarantees it, state that invariant in `fits`'s doc so
  future readers don't re-litigate it" — and that is fair: the invariant lives
  a class away and was nowhere stated here. `fits` now carries it, and
  `MemoryBudget.compute` fails fast if the constants ever stop supporting it at
  the `TILE_SIZE` floor (the round's separate, and correct, finding about the
  floor never being tested). Third raising of the code change should be read
  against this note.

- **R-015 ⏸️ (third raising) Vary `largeMemoryClassMb` in the test device
  helpers.** Declined again, unchanged: `MemoryBudget.compute` does not read
  the field, so a loop over it would assert that a number nothing consumes
  changes nothing. The field is normative in `10-performance.md` §4's
  `DeviceMemory`, which is why it is present and pinned rather than deleted.
  See R-002 and R-015 below.

- **R-017 ⏸️ Round 10's BLOCKER: "NORMAL.txt row 1 expects black instead of
  red".** Refuted on the PR — the row was misread. `NORMAL.txt` line 18 on head
  `0b307ff` is `FF0000FF FFFF0000 1.0  FFFF0000`, the source-over answer the
  finding itself derives. `FF000000` is line 25 ("black over white"), a
  different row with a different destination. The finding's own reasoning is
  correct and the file already agrees with it; nothing changed. This is the
  fourth consecutive round whose BLOCKER rested on misquoting a fixture row.

- **R-001 ⏸️→🟢 Throwing on a malformed layer id in `LayerRecord.init`** (PR
  #7, rounds 2, 6, 7). Declined three times on the reasoning below, then
  **superseded in round 8** — see R-001 under *Resolved* for the argument that
  changed the position and what was actually implemented. Kept here so the
  history of the decision is legible rather than rewritten.

  *Original reasoning:* the concern was real but the suggested fix was the
  wrong shape. (1) A `require` in the deserialization constructor makes one bad
  id fail the whole document open, which `06-document-and-persistence.md` §4
  forbids for tiles. (2) There was no filesystem in this PR to traverse. (3)
  The threat needs write access to app-private storage to plant the file. Point
  (1) is the one round 8 rebutted: §4's rule is about values with a sane
  fallback, and an id has none. Points (2) and (3) still hold, which is why the
  *path-construction* obligation remains step 3's and is written into the
  roadmap.

- **R-014 ⏸️→🟡 `LayerId` should also reject Windows reserved device names and
  trailing dots/spaces** (round 9, info). **Partially superseded, and the
  reasoning below is what was overturned.** The trailing-dot-and-space half was
  applied in round 12 and the character-set half in round 11 — see the second
  and third raisings above. Only the reserved-names decline still stands, and
  it now stands on the narrower ground recorded there (they fail loudly, not
  silently), not on the "never leave the device" claim below, which the later
  rounds contradicted: a project folder is copied between machines, and that is
  precisely why the trailing-dot rule went in. Kept unedited beneath this note
  so the change of position is legible rather than silent, as with R-001 and
  R-005. Declined for v1. Project folders live
  in `filesDir/projects/` and never leave the device: the share and export
  paths write a flattened PNG through `GalleryExporter`/`ShareCache`
  (`docs/plan/06-document-and-persistence.md` §9.5), not the folder, and
  OpenRaster export is post-v1 backlog. `CON`/`NUL`/trailing-dot names are
  legal on every filesystem the app actually writes to, so the check would
  guard a path that does not exist. Revisit if a proposal ever adds project
  export or sync — the constant belongs with that code, not ahead of it.

- **R-015 ⏸️ Vary `largeMemoryClassMb` in the test device helpers.** Declined,
  third raising of R-002's substance. `MemoryBudget.compute` does not read the
  field — that is deliberate and is now stated in its KDoc (GL tile memory is
  not the Java heap) — so a test varying it would assert on an input nothing
  consumes. The suggestion to give `CanvasPresetsTest` realistic per-tier
  values is declined for the same reason and would imply the budget depends on
  something it does not.

- **R-013 ⏸️ `TileGrid.MAX_EDGE + 1` could itself overflow in the test.**
  Declined: the premise is false. `MAX_EDGE` is `8192`, so `MAX_EDGE + 1` is
  `8193` and cannot wrap. The finding hedges on "if `TileGrid.MAX_EDGE` is
  defined as `Int.MAX_VALUE`" — it is not, and it is a `const val` two files
  away. Guarding against a constant that does not have that value would add a
  branch no test can reach.

- **R-010 ⏸️ Round 7's BLOCKER: "`assertIs` return value ignored, so
  `CanvasPresetsTest` does not compile".** Refuted. `kotlin.test.assertIs` is
  `inline fun <reified T> assertIs(value: Any?, message: String?): T` carrying
  the contract `returns() implies (value is T)` — a contract *may* reference a
  reified type parameter in an inline function, which is exactly the case that
  makes it smart-cast. The finding's premise ("contracts can't reference
  reified type parameters") is the error. Disproved by observation as well as
  by the signature: the file has compiled and passed on every CI run of this
  PR, and did so again at the moment the finding was posted — 97 tests green.
  Assigning the return value would also be fine; it is a style preference, not
  a compile fix, and the finding was filed as a build-breaking BLOCKER.

- **R-011 ⏸️ `fits()` should also require `maxLayersFor(...) >= 1`.** Declined:
  it cannot be false. `maxLayersFor` ends in `coerceIn(MIN_LAYERS, MAX_LAYERS)`
  with `MIN_LAYERS = 1`, so it never returns 0 for any input, including a
  zero-area canvas (guarded separately). The suggested check is dead code. The
  invariant the finding wants protected is real, and is now protected where it
  can actually break — `poolCapacityBytes <= gpuTileBudgetBytes` is asserted
  across budgets, slice counts and low-RAM in `MemoryBudgetTest`.

- **R-012 ⏸️ `mergeDown` should not move the selection when merging a
  non-active layer.** Declined: `docs/plan/05-layers.md` §3.1's operation table
  states the active layer after `mergeDown` is "the merged (lower) layer",
  unconditionally — the same table that specifies `delete`'s selection-
  preserving behaviour the finding contrasts it with, so the asymmetry is the
  plan's and is deliberate. `activeAfter` is therefore correct as recorded:
  the merged layer *is* `next.active`. Revisit only if the layer panel in step
  6 shows the plan's choice to be wrong in the hand.

- **R-007 ⏸️ Round 6's BLOCKER: "OVERLAY fixture row 6 is mathematically
  impossible".** Refuted — the finding misquoted the file. It read the row as
  `FF808080 FFC0C0C0 1.0 FF606060` and reasoned correctly that `0x80` is above
  0.5 so the `cd <= 0.5` branch cannot apply; but the row actually reads
  `FF404040 ...`, which is precisely the value the finding proposed changing it
  *to*. Its own arithmetic ("2·(0x40/255)·(0xC0/255)·255 ≈ 96.4 → 0x60, the
  correct branch-1 result for a backdrop of 0x40") confirms the fixture as
  written. Verified independently: `cd = 64/255 = 0.251`, `B = 2·cs·cd =
  0.37797`, `→ 0x60`. Refuted on the PR because a BLOCKER claiming the build
  must fail would otherwise mislead a merge decision.

- **R-008 ⏸️ SCREEN goldens pin premultiplied-space screen rather than W3C.**
  Refuted: `docs/plan/05-layers.md` §4 is *normative* for this project and
  gives the premultiplied form explicitly (`both = s.rgb·d.a + d.rgb·s.a −
  s.rgb·d.rgb`). Recomputed row 6 from that table by hand: `C0808000`, exactly
  what the fixture holds. The W3C unpremultiplied formulation is a different
  convention, not a correction. The finding's real complaint — that the
  convention was invisible to a reader — was valid, so every fixture header now
  states the formula and the rounding rule.

- **R-009 ⏸️ `glMaxTextureSize` should clamp `maxCanvasEdge`.** Re-raised at
  info level in round 6; already settled in round 1 and recorded in AGENTS.md
  under "Deviations". A canvas is a grid of 256 px slices, never one texture,
  so the driver's texture-size limit does not bound it; `10-performance.md` §4
  says so in a normative comment. The field's KDoc now states why it is
  captured, which should stop the question recurring.


- **R-002 ⏸️ `DeviceMemory.largeMemoryClassMb` is unused — consult it or delete
  it** (PR #7, GLM round 2, plus the related test note about `device()` never
  varying it or `glMaxTextureSize`). Declined: `DeviceMemory` is *normative* in
  `10-performance.md` §4, and the `MemoryBudget.compute` written out in that
  same section does not read `largeMemoryClassMb` either — the device tiers are
  defined on `totalMem` and `isLowRamDevice` alone. Both unused fields are
  captured platform readings the budget is expected to consult later
  (`glMaxTextureSize` when the viewport-sized `Accum`/`Scratch` targets of
  `03-canvas-engine.md` §3.2 land in PR 2.3). Deleting a field from a normative
  struct to satisfy a "dead code" reading would be a deviation from the plan
  that buys nothing; wiring `largeMemoryClassMb` into the `large` predicate
  would silently change the pinned worked table in §4. Varying the fields in
  `MemoryBudgetTest.device()` is declined for the same reason: asserting on
  inputs nothing consumes tests nothing, and the finding itself concedes it is
  "today harmless".

  *Round 3 re-raised this as an info-level "say why it is unused".* That is a
  different ask and was **applied**: both `largeMemoryClassMb` and
  `glMaxTextureSize` now carry a KDoc saying why the budget does not read them
  (GL tile memory is not the Java heap; a canvas is never one texture). The
  decline above stands unchanged — neither field is deleted and neither is
  wired into the `large` predicate, because both would deviate from a
  normative struct or silently move §4's pinned table.

## PR #12 (roadmap 2.4a) — GLM round 1

- **R-047 ❌ `noteLift(slot)` / `remove(slot)` can be reached with `slot = -1`
  and crash the touch path** (PR #12, GLM round 1, Major). **Refuted**: no call
  site can pass `-1`, and both guard already. `up()` opens with
  `val slot = indexOf(pointerId); if (slot < 0) return`, so an untracked
  pointer's lift returns before either helper. `down()` opens with
  `val slot = add(...); if (slot < 0) { out.onIgnore(pointerId); return }`, and
  the palm branch guards its own write with `if (slot >= 0)`. The finding's own
  scenario — a fifth pointer on a device reporting five contacts — is therefore
  a no-op in both directions, which
  `GestureArbiterTest.a pointer beyond the tracking table can land and lift
  without crashing` now pins: the extra pointer is ignored on the way in and its
  move and lift do nothing on the way out. The test was **applied** even though
  the crash claim was refuted, because "no reachable crash" is worth a
  regression test. The guards themselves are declined: they would be branches no
  test can reach without breaking encapsulation, and "one refactor away from an
  unguarded call" is true of every private helper in the codebase.

- **R-048 ⏸️ The allocation gate should `assumeTrue` instead of failing on a JVM
  without `com.sun.management.ThreadMXBean`** (PR #12, GLM round 1, Info).
  Declined. The hard failure is the point. This gate exists because
  `10-performance.md` §2.4 names it the mitigation for touch-path GC jank, and a
  skip turns it into exactly the silently-vacuous check that the same round's
  budget finding (applied, see below) was about: a green build that measured
  nothing. CI runs stock OpenJDK, where the extension is present, so the skip
  would only ever fire on a JVM nobody builds this on. A red test whose message
  reads "this JVM cannot measure per-thread allocation, so this gate would be
  vacuous" already says "wrong JVM", not "allocation regression".

- **R-049 ⏸️ Rename the 2.4a branch label to `fable/touch-navigation`, since
  `fable/stroke-path-touch` no longer describes a PR with no stroke path** (PR
  #12, GLM round 1, Info). Declined. The observation is fair — 2.4a carries no
  stroke path — but the branch exists, PR #12 is open on it, and the roadmap
  cell's job is to record where the work actually landed. Renaming the label
  without renaming the branch makes the table wrong for exactly the bisecting
  the finding wants to protect; renaming the branch mid-review closes the PR and
  discards the round. The 2.4b row already carries `fable/stroke-on-pixels`, so
  a reader looking for where strokes first land is sent to the right row.

## PR #12 (roadmap 2.4a) — GLM round 2

- **R-050 ❌ Restore `isFocusable` / `isFocusableInTouchMode` in `factory`
  rather than deleting them** (PR #12, GLM round 2, Minor). **Refuted**, and
  note this reverses the same reviewer's round-1 Minor, which said to delete
  them and correctly explained why ("Android delivers touch and hover events to
  a `View`'s listeners regardless of focusability — focus only gates key
  events"). Round 2 does not withdraw that reasoning; it asks whether some
  focus-dependent path might exist anyway, and makes its own suggestion
  conditional on verification: "Only apply this if verification shows a
  focus-dependent path exists."

  Verified, and none exists. `grep -rn
  "requestFocus\|onKey\|dispatchKeyEvent\|setOnGenericMotionListener\|OnKeyListener\|KeyEvent"`
  over `app/src/main` returns nothing at all: the app has no key handling, no
  generic-motion listener, and never requests focus. The stylus barrel button
  arrives as `ACTION_BUTTON_PRESS`/`RELEASE` in the ordinary `MotionEvent`
  stream — `CanvasTouchHandler.onTouch` now handles it there — not as a
  `KeyEvent`, so even §6's one plausible focus-dependent feature is not one.
  The deletion stands. Restoring the flags would reintroduce a `SurfaceView`
  that grabs focus on first touch for no benefit.

  Recorded rather than silently re-applied because a reviewer that asks for a
  change and later for its revert, on code whose behaviour has not changed
  between the two rounds, must not be able to walk a decision back and forth by
  repetition.

## PR #12 (roadmap 2.4a) — GLM round 4

- **R-051 ⚠️ Applied in substance, refuted in mechanism: assert
  `host.view.isIdentity` after a cancel** (PR #12, GLM round 4, Minor). The
  observation is right and the fix is wrong. `cancel rolls the stroke back and
  leaves the view alone` really did assert only the host events, leaving the
  second half of its own name unchecked, so an assertion belongs there.

  But the suggested one cannot fail. `Host.view` is written *only* by
  `onViewChanged`, and the line immediately above it —
  `assertEquals(listOf("cancel"), host.events)` — already proves no `"view"`
  event was emitted. `host.view.isIdentity` is therefore implied by the
  preceding assertion and adds no coverage at all. Worse, it is specifically
  blind to the regression the finding names: "changed it without emitting
  `onViewChanged`" is the one case where the handler's transform moves and the
  host's copy does not.

  Mutation-checked both ways. With `handleCancel` made to do
  `view = view.copy(tx = view.tx + 1f)` and emit nothing, the suggested
  `host.view` assertion leaves the suite green; `h.view.isIdentity` fails it.
  The assertion is on the handler's own view, with a comment recording why.

  Worth spelling out because this is the same class of defect the reviewer has
  been most valuable at finding in *this* PR's tests — an assertion that reads
  as coverage and cannot fail — arriving this time in its own suggestion.

## PR #12 (roadmap 2.4a) — GLM round 5

- **R-052 ⏸️ Assert `assertEquals(viewBeforeCancel, h.view)` rather than
  `h.view.isIdentity`** (PR #12, GLM round 5, Minor). Correct, and **deferred to
  2.4b** rather than declined — it is a real improvement that arrived after
  steady state was declared.

  The point stands: `isIdentity` pins the fixture's *starting value*, while the
  assertion's own message names the *property* "cancel left the view alone".
  They coincide only because this test starts from an identity view, so a future
  pan/zoom preamble would break the assertion spuriously. Capturing the view
  before the cancel and comparing expresses the invariant directly.

  Not applied here because the round that raised it is the third consecutive
  nits-only round, and the finding itself concedes "the mutation-catching power
  is identical as written" — the R-051 mutation (mutate the transform, emit
  nothing) fails the assertion either way, so today this changes robustness and
  intent, not coverage. Steady state was declared on rounds 3 and 4; taking one
  more cosmetic round would restart the cycle on exactly the reasoning the stop
  rules exist to bound, and the same argument would then apply to round 6.

  Carried in `docs/plan/12-roadmap.md` as a 2.4b carry-in, to be done when that
  file is next touched.

## PR #13 (roadmap 2.4b) — GLM round 1

- **R-053 ❌ `DabRing` slots are released from two threads, so the ring can
  starve permanently** (PR #13, GLM round 1, Major). **Refuted.** The premise —
  releases arriving from both the input thread and the GL thread — is correct,
  and worth saying out loud, which is why `EngineSession` now carries a comment
  about it. The conclusion is not: `DabRing.acquire` and `DabRing.release` are
  both `@Synchronized` (`engine/core/Dab.kt`), as is `freeSlots`. The finding
  itself is conditional — "If `DabRing` uses a non-atomic index/flag" — and the
  answer is that it does not. There is no lost-slot window and no starvation.

  Not applied, and specifically *not* routed through `frontBuffered.execute`, as
  the finding's fallback suggests: that would defer every empty-batch release by
  a GL-thread hop, so a run of samples the stabilizer swallows would hold slots
  for a frame each and manufacture the starvation the change was meant to
  prevent.

- **R-054 ❌ `IntRect.forDab(x, y, radius)` may not bound the rotated ellipse,
  so dabs could be dropped from neighbouring tiles** (PR #13, GLM round 1,
  Info). **Refuted**, twice over. `aspect` is minor/major and is bounded to
  `0.1..1` (`TipShape.Flat.MIN_ASPECT`, and `StrokeDriverTest` pins
  `aspect in (0, 1]` for every emitted dab), so it only ever *shrinks* the
  ellipse below `radius` — the circumscribing circle of radius `radius` bounds
  it at every angle. And `forDab` already pads: it floors/ceils
  `x ± radius ± 1`, a full pixel beyond the radius.

  The one case worth checking is the sub-pixel dab, where the quad is padded to
  `max(radius, 1) + 1 = 2` px while `forDab` gives only `radius + 1 = 1.3` px.
  That is still sound, because the *painted* extent is bounded by the falloff,
  not the quad: `coverage` is zero at and beyond `drawRadius = max(radius, 1)`,
  and `radius + 1 ≥ max(radius, 1)` holds for every radius. No painted pixel
  falls outside the rect.

- **R-055 ⏸️ The anti-alias feather is 1 px on the major axis but `aspect` px on
  the minor axis of an elliptical dab** (PR #13, GLM round 1, Minor).
  **Deferred**, with the finding's own analysis corrected in two places.

  The asymmetry is real. `v_local.y` is divided by `aspect`, so the `[inner, r]`
  band of width 1 in local units is `(r − inner)·aspect` canvas px across the
  minor axis.

  But the finding has the sign backwards. It reasons from "aspect 8", and
  `aspect` is minor/major — it cannot exceed 1. So the long edges of a flat
  brush are not *blurred* to 8 px; they are *sharpened* to `aspect` px, which at
  `aspect = 0.25` is a quarter-pixel band and therefore **aliased**. The defect
  is the opposite of the one reported, and it breaks the promise §7.3 actually
  makes ("never thinner than 1 canvas px").

  The suggested fix — `inner = r − 1/aspect` — is also not right. It buys a 1 px
  minor-axis band by making the *major*-axis band `1/aspect` px: at
  `aspect = 0.25` a hardness-1.0 brush would get a 4 px feather along its long
  axis, trading an aliased edge for a smeared one. A correct fix normalises per
  fragment by the gradient — `fwidth(d)` is exactly right here, since a merge
  renders a tile 1:1 with canvas px — but `DabStamp`, the CPU twin §15 requires
  this shader to match, has no derivative to mirror it with, so the twin needs
  the analytic gradient written out.

  Deferred rather than rushed because it is **unreachable today**: every shipped
  preset is `TipShape.Round`, `BrushPresets.ALL` is `[INK_PEN]`, and at
  `aspect = 1` the two axes coincide exactly. It belongs with the flat and
  bristle tips of `04-tools.md` §2, which is the first PR that can actually see
  it. Recorded in `12-roadmap.md`.

## PR #13 (roadmap 2.4b) — GLM round 2

- **R-056 ❌ `eraseBranch`'s window runs to end-of-source, weakening both ERASE
  pins** (PR #13, GLM round 2, Major). **Refuted.** The reasoning is sound and
  the conclusion would follow — `substringBefore` does return the whole receiver
  when the delimiter is absent — but the premise is false. It claims that
  because MIX is the fall-through, "after `u_strokeMode == 1` there is no
  further `u_strokeMode ==` comparison anywhere in the shader". There is:
  `merge.glsl` tests **mode 1 before mode 0**, so the mode-0 comparison bounds
  the window.

  Extracted from the shipped `MERGE_GLSL` and printed, the comparisons appear in
  the order `[u_strokeMode == 1, u_strokeMode == 0]`, and the window is exactly:

  ```
  ") return u_alphaLock ? L : L * (1.0 - S.a); if ("
  ```

  It contains neither PAINT's `S + L * (1.0 - S.a)` nor `MIXLERP(` — checked
  both ways, not eyeballed. The suggested extra `.substringBefore("MIXLERP(")`
  would be a harmless no-op today, and is declined only because it would encode
  a false belief about the branch order into the test: the next reader would
  take it as evidence that MIX can fall inside the ERASE window, and would be
  wrong.

  Round 1's mutation check already demonstrated the window is tight — deleting
  the scaling from the ERASE branch alone fails this assertion, and did not
  before the scoping landed.

## PR #13 (roadmap 2.4b) — GLM round 3

- **R-057 ✅ the axis fields are per-*handler*, not per-`handle*`-call** (PR #13,
  GLM round 3, Minor). **Applied — and it is a live bug, not the documentation
  nit it was filed as.** The report frames the risk conditionally: *"If a future
  change emits a sample from `handleMoveEnd` … it will silently stamp the palm's
  pressure/tilt onto the pen's stroke."* That future change is already in the
  tree. `GestureArbiter.tick` resolves the pending window on the clock —
  `if (!stylusOnly && heldMs >= PENDING_MS) beginFingerDraw(ids[slot], out)` —
  and `beginFingerDraw` calls `onDraw`, whose handler emits the stroke's opening
  sample. `handleMoveEnd` calls `tick`, and sets no axes.

  So the reachable sequence is §5's own: a palm lands while the pen hovers and
  is ignored, the pen leaves, its 500 ms grace expires, and the user draws with
  a finger while the palm still rests. Both pointers move; the palm is processed
  last (it is the lower pointer index only by accident, but the arbiter ignores
  its move, so nothing else overwrites the fields); `handleMoveEnd` then opens
  the finger's stroke carrying the palm's axes. The new test
  `a finger stroke that resolves on the clock carries its own axes` fails on the
  pre-fix handler with `actual <0.1>` — the palm's pressure, on the finger's
  first sample — which is the same defect class round 1 fixed, arriving through
  the clock instead of through `actionIndex`.

  The fix is to stop having a "current pointer" at all: `trackPressure`,
  `trackTilt` and `trackOrientation` join `trackX`/`trackY`, and `track()`
  writes all six together, so the axes physically cannot belong to a different
  pointer than the position beside them. `emitTracked(slot, timeNs)` reads one
  slot. The three fields and `setAxes` are gone, which also disposes of the
  comment the reviewer was reading: a claim that could be violated is replaced
  by a structure that cannot be.

  `track()` deliberately stays **after** `arbiter.move` in `handleMove`. Moving
  it earlier would make an arbiter decision see the current sample, which sounds
  tidier and is wrong: on the first move past the slop the previous slot value
  is the finger-**down** point, and that is the sample the stroke would
  otherwise lose. The live sample two lines later supplies the current one, so
  the opening segment survives. (Reordering also silently changes what
  `captureNavPointers` records as the gesture's previous position.)

- **R-058 ✅ `DabPass.release()` keeps the direct instance buffer alive** (PR #13,
  GLM round 3, Minor). **Applied.** `release()` reset `instanceCapacityDabs = 0`
  but left `instanceData` and `instanceBuffer` pointing at the grown
  allocations. Because the capacity is what `ensureInstanceCapacity` gates on
  (`if (dabs <= instanceCapacityDabs) return`), a capacity of 0 guarantees the
  next call reallocates both — so the retained pair could never be read again,
  and `instanceBuffer` is a *direct* buffer whose off-heap bytes survive until
  the buffer object itself is unreachable. A pass kept across context-loss
  cycles held one dead allocation per cycle. Both are now reset to the empty
  forms the field initialisers use.

  `distinctKeys` and `seen` are deliberately left alone: their sizes track the
  tile grid rather than this capacity, nothing in `release()` invalidates them,
  and they are on-heap. They are reuse; the other two were waste. Not
  unit-testable — `release()` is pure GL — so the reasoning is recorded here
  rather than pinned.

## PR #13 (roadmap 2.4b) — GLM round 4

- **R-059 ⚠️ `Readback`'s both-busy wait is not the §10.1 ordering rule its KDoc
  claims** (PR #13, GLM round 4, Major). **Diagnosis accepted and the KDoc
  rewritten; the suggested code change declined.**

  The mechanism is exactly right and the claim it demolishes was mine. The class
  KDoc said the both-PBOs-busy wait in `enqueue` "is the correctness rule, not
  back-pressure", and that a merge for stroke *n+1* cannot be issued until
  stroke *n*'s readback has been mapped. Neither half survives reading
  `enqueueChunk`: the wait fires only when the round-robin lands on a slot still
  in flight, so a readback of ≤ `READBACK_CHUNK` (64) tiles — any normal stroke
  — leaves the other slot free and the next `enqueue` skips the wait entirely.
  A comment asserting a guarantee the code does not provide, on the class whose
  whole job is ordering, is worse than no comment: it is the thing step 3 would
  have read and believed. Corrected, at length, including where the rule really
  has to live.

  The suggested fix — `for (chunk in chunks) if (chunk.inFlight) drain(chunk,
  block = true)` at the top of `enqueue` — is declined, on the finding's own
  reasoning. It says, correctly, that "the in-file drain alone cannot protect a
  capture that happens before `enqueue` runs". The journal's "before" is
  captured at the merge call site, upstream of `enqueue`; draining every slot
  here would therefore add a blocking GL-thread stall — the stall the two-PBO
  design exists to avoid — while leaving the hole exactly as open as it was.
  The call the capture site needs is `finish()`, which already exists and
  already drains every slot blockingly. What is missing is the *caller*, and
  the caller is step 3's: `TileStore`, the journal and the undo stack do not
  exist yet, and `CanvasRenderer.endStroke` is passed `readback = null` today.
  Recorded in the KDoc so step 3 cannot miss it.

- **R-060 ✅ `MergePass.merge` leaves the previous stroke's keys on the
  empty-buffer path** (PR #13, GLM round 4, Minor). **Applied.** `keys.clear()`
  sat below `if (buffer.isEmpty) return 0`, so that exit handed the caller a key
  list belonging to a different stroke, while the KDoc two lines above promises
  `keys` holds every key *this* merge touched and "must be read back in full".

  Latent, not live: `CanvasRenderer.endStroke` gates its readback on
  `merged > 0`, so the stale list is never read today. That is the reason to fix
  it rather than a reason not to — the invariant currently holds by the call
  site's discipline instead of the method's, and the next caller has no way to
  know that. One line, no cost. Not unit-testable: `merge` needs a `StrokeBuffer`,
  which needs a `TilePool`, which needs a GL context; `engine/gl` has only
  shader-contract tests for this reason.

## PR #13 (roadmap 2.4b) — GLM round 5

- **R-061 ✅ a move-opened stroke stamped its opening pair with one timestamp**
  (PR #13, GLM round 5, Major). **Applied; the stated impact refuted.**

  The mechanism is real and the fix is the right one. When the arbiter opens a
  stroke from `arbiter.move`, `onDraw` emitted the tracked *previous* position
  — the finger-down point — stamped with `lastEventNs`, which `handleMove` had
  just set to *this* move's time; `handleMove` then emitted the current
  position with the same value. Two positions, one timestamp, zero elapsed
  time. The `tick`-opened path was worse: `lastEventNs` there belongs to the
  last pointer processed in the event, which need not be the drawing one.

  `trackTimeNs` joins the other five per-pointer arrays, `store` writes all
  seven together, `emitTracked(slot)` reads the slot's own time, and
  `lastEventNs` is deleted — it had no other reader. Mutation-tested: restoring
  the current-event stamp fails the new test with `expected:<0> but
  was:<30000000>`.

  **The impact as stated is wrong, and the correction matters because it is the
  reason this is a Major-shaped fix and not a Major-shaped bug.** The finding
  predicts "division by zero, NaN/Inf velocity, or a clamped-to-zero speed".
  None occur. `DabGenerator.updateVelocity` opens with an explicit
  `if (elapsedNs <= 0L)` branch that *defers* the distance into
  `pendingDistance` rather than dividing by it, precisely so a run of
  same-timestamp samples does not read as slower than the identical gesture
  elsewhere; and `StrokeInput.timeNs` documents that it is "non-decreasing, and
  not strictly increasing" and that "anything deriving speed from it owes a
  zero-delta branch". The debt was already paid. So what the fix buys is
  accuracy — the opening segment's speed goes from *deferred* to *measured* —
  not the crash it was filed as. Applied on that basis, not the stated one.

- **R-062 ⚠️ a live `StrokeDriver` could outlive its `EngineSession`** (PR #13,
  GLM round 5, Major). **Pairing applied; two sub-claims corrected.**

  The core observation holds. `onStrokeSample`, `onStrokeEnd` and
  `onStrokeCancel` all read `session` — Compose state meaning "which engine
  exists now" — while the driver was opened against whichever engine was
  current at pen-down. `CanvasSurface`'s `AndroidView` sits inside
  `key(canvas)`, so a canvas-size change builds a new `SurfaceView` and a new
  `EngineSession` and releases the old one. `StrokeUiState.engine` now pins the
  stroke to its own session, and every later call goes through it.

  Applied even though the *replacement* case **cannot diverge in this PR**:
  only step 3's New Canvas dialog changes the canvas size, and nothing else
  re-runs the factory. ~~The null case (dispose → `onSession(null)`) was already
  handled on every path.~~ **That sentence was wrong, and round 5's own delta is
  what made it wrong — see R-063.** It was true of the reads the delta replaced,
  not of the ones it introduced. It is worth pairing now precisely because the failure would be silent
  when it does become reachable — `stampDabs` and `endStroke` both no-op when
  no stroke is open, so a mismatch shows up as dabs quietly going nowhere
  rather than as an error. Same reasoning as R-060.

  Two corrections. First, the impact overstates it: "engine state-machine
  errors, or an exception at `endStroke()`" cannot happen, because
  `CanvasRenderer` guards both entry points with `stroke ?: return`. Second,
  and more importantly, the suggested *"install `strokeState.driver` only once
  the engine has accepted the stroke"* **cannot be implemented as written and
  its diff does not do what its comment claims.** `EngineSession.beginStroke`
  returns `Unit` and dispatches through `frontBuffered.execute { ... }` — the
  refusal happens later, on the GL thread, and the main thread never learns of
  it. Moving the assignment below the call therefore establishes nothing;
  a comment saying "installed only once the engine has accepted" would be a
  false claim of exactly the kind round 4's R-059 was about. Declined, and the
  assignment stays where it is, next to the driver it pairs with.

## PR #13 (roadmap 2.4b) — GLM round 6

- **R-063 ✅ pinning the stroke to its engine removed the dispose path's safety
  net** (PR #13, GLM round 6, Major). **Applied. A regression introduced by
  round 5's own fix, and the finding is exactly right — including its reading
  of what my REVIEW.md entry got wrong.**

  R-062 replaced every `session` read in the stroke handlers with
  `strokeState.engine`. `session` is nulled by `CanvasSurface`'s
  `DisposableEffect` → `onSession(null)`, which is what made `onStrokeSample`'s
  `?: return`, `onStrokeEnd`'s `driver.cancel()` branch and `onStrokeCancel`'s
  `?.` safe on teardown. Nothing cleared the new pin, so after disposal it held
  a **released** `EngineSession` and all three handlers went straight through to
  it. My entry claimed "the null case was already handled on every path": true
  of the reads I removed, false of the reads I added. Struck through above
  rather than quietly edited, because the mistake is the point — a fix that
  narrows one hole by widening another is the failure mode this loop exists to
  catch, and it took the reviewer to see it.

  Reachable, unlike R-062's replacement case: a surface torn down mid-gesture —
  back navigation, system teardown — still delivers trailing move and cancel
  events. The consequences are worse than the finding says, too.
  `stampDabs`, `endStroke` and `cancelStroke` all queue through
  `frontBuffered.execute` with **no** `isValid()` guard (only `redraw()` has
  one), and `stampDabs` returns its `DabRing` slot *inside* the queued block —
  so a block that never runs never releases, and a few of those strand the ring
  until every later `acquireDabBatch` returns null.

  Fixed at the seam the reviewer names: `onSession` now cancels the driver and
  drops the pin before assigning `session`, which covers arrival and departure
  alike. `cancelStroke` is deliberately *not* called on the outgoing engine —
  it is exactly the released one, and §4 says a cancelled stroke leaves no
  trace, so there is nothing it still owes. Not unit-testable: `onSession` is a
  lambda inside a composable and this project has no Compose test
  infrastructure. Considered and rejected: guarding `EngineSession`'s methods
  with `isValid()` instead — it returns false before the surface is ready too,
  so it would silently drop legitimate early calls, and the `stampDabs` guard
  would leak the very batch it declined to stamp.

## PR #13 (roadmap 2.4b) — GLM round 7

**Round scored: nits-only.** Nothing raised this round changed behavior. The
Major's mechanism is structurally impossible here, the first Minor's premise is
contingent on a contract I verified does not hold, and what was applied is a
no-op reorder plus test assertions. First round of the nits-only streak; two
consecutive are needed.

- **R-064 ⚠️ `driver.cancel()` runs while the released engine is still pinned**
  (PR #13, GLM round 7, Major). **Reorder applied; the mechanism refuted.**
  The finding is explicitly conditional — *"If the driver's `cancel()`
  synchronously dispatches its cancel path (a common design for input
  drivers)"* — and the condition is false, not merely unmet. `StrokeDriver.cancel`
  is `isActive = false` and nothing else; the class takes `(preset, seed, zoom)`
  and holds no listener, no host, no callback of any kind, so it cannot reach
  `onStrokeCancel` even in principle. There is no re-entrant window to close and
  no `DabRing` slot to strand.

  Applied anyway, on the finding's *other* argument — "dropping the pin first is
  strictly safer and costs nothing" — which is true and is the whole reason this
  is a nit rather than a decline. Nulling before the side effect makes the seam
  correct on its own terms instead of on a fact about `StrokeDriver`. The comment
  says which of the two it is, so a later reader does not conclude a re-entrancy
  hazard was found here.

- **R-065 ❌ `onSession` changed from idempotent to destructive on repeat
  emissions** (PR #13, GLM round 7, Minor). **Refuted; the contract documented
  instead, which is the action the finding itself asks for in that case.**
  The finding is honest that it is contingent — *"This is contingent on
  `CanvasSurface`'s emission contract, which isn't visible in this chunk, so
  verify before acting"* — so I verified it. `onSession` has exactly two call
  sites: `AndroidView`'s `factory` (line 66), which runs once per view instance,
  and `DisposableEffect(Unit)`'s `onDispose` (line 94). `update` does not call
  it. A canvas-size change goes through `key(canvas)`, which disposes (emitting
  null) before the new factory runs. So a non-null session is never re-emitted
  without an intervening null, and the re-attach scenario has no path.

  The suggested `if (attached === session) return@CanvasSurface` is declined as
  dead code defending an emission the contract does not make — and the finding's
  own fallback says so: *"If the callback is strictly transition-only, document
  that contract at the CanvasSurface call site instead."* Done.

- **R-066 ✅ the "exact" replay test omitted flow and aspect** (PR #13, GLM round
  7, Minor). **Applied, and widened past what was asked.** The test's stated
  justification is journal replay, which needs the whole dab back, and it
  compared three fields. `angle` and `hardness` were missing too, so all four
  were added rather than the two named.

  Mutation-tested, because an assertion that reads as coverage is this PR's most
  frequent defect: introducing a process-wide counter into `DabGenerator`'s flow
  computation — precisely the non-replayed state the test claims to guard —
  fails the new assertion with `dab 0 flow diverged. Expected <0.94> ... actual
  <0.98>`. Not vacuous. It also passes unmodified, so there is no real
  non-determinism to escalate.

## PR #13 (roadmap 2.4b) — GLM round 8

**Round scored: nits-only. Second consecutive → STEADY STATE.** Three Minors,
all about tests and a comment; no production code changed and no substantive
claim was declined. Rounds 7 and 8 together satisfy the two-consecutive
nits-only rule.

- **R-067 ✅ the fall-through check could be satisfied from inside the last
  branch** (PR #13, GLM round 8, Minor). **Applied, and strengthened past what
  was suggested.** `body.lastIndexOf("u_strokeMode ==") < body.indexOf("MIXLERP(")`
  proves textual order only: a `MIXLERP(` call moved *into* mode 0's body also
  sits after every mode comparison. My comment claimed it meant "no earlier
  branch can swallow mode 2" — a guarantee the check did not make, which is the
  same defect as round 4's R-059 and, as the finding says, the risky part,
  because the next reader trusts it.

  The finding offers a comment correction and warns "do not add a check that
  would false-fail against the current shader". A real check turned out to be
  available: `merge.glsl` uses **early returns**, not an else-chain, so
  brace-matching each branch decides the question. `branchBody` extracts a
  braced block by brace count, or the single statement up to its `;` for the
  braceless mode-1 form, and the test now pins that neither branch contains the
  MIX arithmetic, that both `return`, and that `MIXLERP(` sits past mode 0's
  closing brace. Necessary *and* sufficient, and the comment now says which.

  Mutation-tested: adding `vec3 dead = MIXLERP(L.rgb, S.rgb, 0.5);` inside mode
  0's block fails with "the PAINT branch must not do the MIX arithmetic". The
  old assertion passed that mutation, which is exactly the finding's claim.

- **R-068 ✅ `orientation` was accepted and never recorded** (PR #13, GLM round
  8, Minor). **Applied.** `Host.onStrokeSample` took `orientation` and dropped
  it, so the per-pointer axis fix was two-thirds pinned: a regression swapping
  orientation between palm and pen passed the whole suite. `lastOrientation`
  joins the other two, and both axis tests now pass distinct per-pointer
  orientations and assert the drawing pointer's.

- **R-069 ✅ "the palm, ignored" was a comment, not an assertion** (PR #13, GLM
  round 8, Minor). **Applied.** In the per-pointer axes test the palm's move was
  labelled ignored and nothing checked it — and the existing assertions could
  not have: the pen moves last, so it overwrites `lastPressure`/`lastTilt`
  whether or not the palm emitted first. `samples.none { it.first > 300f }`
  closes it (palm at x ≈ 400, pen at x ≤ 140, identity view). This covers the
  **superseded** case — a finger the pen took over from — which
  `a rejected palm emits no stroke samples` does not.

  Mutation-tested: relaxing `handleMove`'s emit guard from
  `strokeLive && pointerId == drawingId` to `strokeLive` fails it with
  "the superseded palm must not emit samples: [(100.0, 100.0), (401.0, 400.0),
  (140.0, 100.0)]".

## PR #14 (roadmap 2.5a) — GLM round 1

**Round scored: substantive.** A real BLOCKER that would have crashed on the
first pen-up, two real Majors, and a Minor that found a false no-allocation
claim in *two* files.

- **R-070 ✅ every committed stroke double-released its `DabRing` slots** (PR #14,
  GLM round 1, BLOCKER). **Applied. The finding is right and the consequence is
  worse than it says.**

  `onDrawFrontBufferedLayer` drained `pendingBatches` and released each batch;
  `onDrawMultiBufferedLayer` then released `params` as well. The question was
  whether graphics-core replays the same objects. It does — verified from
  1.0.4's bytecode, not inferred: `commitInternal()` runs
  `mSegments.add(mActiveSegment.release())` and the multi-buffered callback
  `poll()`s that collection straight into `params`. So every batch was released
  twice.

  The predicted impact was diverging free-slot accounting. The actual one is
  louder: `DabRing.release` is deliberately **not** idempotent —
  `require(!free[i]) { "batch at slot $i was released twice" }` — so the second
  release throws out of a GL callback on the *first* pen-up. The 2.3b comment
  this replaced ("releasing arrives with the ring that owns the slots") was
  written before the front path existed and quietly became wrong when it did.

  Fixed by making `pendingBatches` the single owner: the replay releases
  nothing, and every exit drains the queue — the front callback, `endStroke`,
  `cancelStroke` and `release`. `endStroke` drains **inside its `execute`
  block**, which is §8.3's own `dabPass.drain(untilStrokeEnd)` and which the
  verified FIFO ordering puts before the replay, so the queue is always empty by
  the time `params` arrives. Without that drain the fix would have traded a
  crash for lost dabs: a batch published but not yet drawn at pen-up would never
  be stamped, and its slot would never come back.

- **R-071 ✅ an aborted present leaked the Accum scissor** (PR #14, GLM round 1,
  Major). **Applied.** `compositeIntoAccum` enables the scissor and the only
  reset is at the end of `presentToWindow`, which has two early exits above it.
  A leaked scissor is not a dropped frame — it is applied, in Accum
  coordinates, to whatever graphics-core binds next. Narrow to reach
  (degenerate buffer dimensions during teardown), one cheap guard to close.

- **R-072 ❌ `u_strokeMode` should use `shaderId` like `u_blend`** (PR #14, GLM
  round 1, Major). **Refuted.** The finding is explicitly conditional — *"If
  `StrokeSpec.mode` is a `BlendMode` (which the `mergeStroke(L, S)` semantics
  strongly suggest)"* — and it is not. `StrokeMode` is its own enum
  (PAINT/ERASE/MIX), `merge.glsl` switches on its **ordinal**, and
  `StrokeShaderContractTest` pins exactly that because nothing else in the
  codebase would notice the enum being reordered. `MergePass` uploads it the
  same way. `shaderId` belongs to `BlendMode`, which is the layer's compositing
  mode and a different question.

  The finding's own fallback is what was applied: *"If it is a distinct enum,
  keep the ordinal but pin the contract in a comment so a future reader doesn't
  'fix' it to `shaderId`."* The comment now says which enum is which and why the
  two lines differ.

- **R-073 ✅ an unsupported device stranded every published batch** (PR #14, GLM
  round 1, Major). **Applied.** `stampDabs` published without consulting
  `isSupported` — it is only known after the first `ensureContext` — and the
  front callback returned on `!isSupported` before draining, so eight batches
  exhausted the ring and `acquireDabBatch` returned null for the rest of the
  session. R-063's shape, on the live path rather than teardown. Both ends are
  closed: `stampDabs` releases on the spot, and the callback drains anything
  already queued.

- **R-074 ✅ the no-allocation comment was false, in two files** (PR #14, GLM
  round 1, Minor). **Applied, and the same defect fixed in `ScreenTransform`.**
  `BufferScissor.bounds` justified its shape with "§2.4 allows no allocation"
  while using a local `fun corner` that captures and mutates five locals — which
  Kotlin compiles to `FloatRef`/`BooleanRef` wrappers. Confirmed from the
  compiled class: 34 `Ref` references. Roughly seven objects per call, once per
  input batch, on the latency path the comment claimed to protect.

  `ScreenTransform.screenBoundsOf` has the identical pattern with the identical
  claim, and is where this one was copied from. Its history makes the point:
  an earlier round removed four boxed `Pair`s from that function *for this exact
  reason* and replaced them with the local `fun` — keeping the allocation and
  moving it somewhere harder to see. Both are unrolled now, and both comments
  say what actually allocates.

- **R-075 ✅ `runPages` had no size guard** (Minor), **R-076 ✅ the initial preview
  VBO was unchecked** (Minor), **R-077 ✅ `linked[5]`** (Minor), **R-078 ✅ the
  per-frame `IntRect`** (Info), **R-079 ✅ `pageTextureOrNull` tested the
  sentinel's sign** (Info), **R-080 ✅ `toGlScissor` needs its clipped-input
  contract** (Info), **R-081 ✅ the one-tap assertion was brittle** (Info). All
  applied as described. Two are worth a line each: `linked[5]` is now a
  capture-at-link-time local, because nothing distinguishes one `GlProgram` from
  another and the `onContextLost` comment already records that this family of
  lists has been wrong twice; and the brittle assertion became a brace-tolerant
  regex, mutation-tested **both ways** — it still fails a raw `texture()` fetch
  on the one-tap path, and it does *not* fail when the return is brace-wrapped.

- **R-082 ❌ the preview capacity never shrinks after a spike** (PR #14, GLM round
  1, Minor). **Declined.** The cost it names is the per-run orphaning
  `glBufferData`, which is a buffer *rename*, not a copy — the driver hands back
  fresh storage and the old one retires with the last draw that read it. Sizing
  that rename at the historical peak instead of the current need is not
  proportional to work done, and the staging `FloatBuffer` is written only up to
  `n` tiles whatever its capacity. Against that, hysteresis adds a second
  reallocation path to a class that just gained one, on the front-buffer frame
  path. `DabPass` sets the precedent: it grows and never shrinks, and its
  release resets to the floor, which is what `CompositePass.release` now does
  too. Revisit with a profile rather than in the abstract.

- **R-083 ❌ the 2.5a row should not read `⬜` while this PR ships its code** (PR
  #14, GLM round 1, Minor). **Declined on the convention, prose clarified.**
  Rows in this table flip to ✅ **with the merge hash** — a value that does not
  exist until the PR merges. 2.4b's row read `⬜` for the whole life of PR #13
  and flipped on merge; 2.4a's did the same. A `🔁` marker appears nowhere in
  the document, so introducing one here would be the inconsistency it is meant
  to avoid.

  The finding is right that one sentence misleads, though: "written before any
  2.5 code exists" was true when committed and reads as a claim about the PR's
  contents afterwards. It now says the seam was fixed in its own commit ahead of
  the first line of 2.5 code, and notes explicitly that rows stay `⬜` until
  their PR merges.

## PR #14 (roadmap 2.5a) — GLM round 2

**Round scored: dismissed only.** The round's one substantive claim — a code
change — was refuted on a premise the finding did not check, and what was
applied is documentation plus a widened guard. Scored this way deliberately
rather than as "nits only": a round that refuses the only behavioural ask is
dismissed-only whatever else it tidies. First of the streak; two consecutive
are needed.

- **R-084 ❌ `stampDabs`'s `!isSupported` gate drops batches while support is
  unknown** (PR #14, GLM round 2, Major). **Refuted; the comments it caught
  were genuinely wrong and are fixed.**

  The finding reasons from "`isSupported` … is only known after the first
  `ensureContext`" — quoting a comment I wrote in round 1 — to the conclusion
  that a batch published before the first GL callback "reads
  `isSupported == false` and is released without ever being drawn", and
  escalates with *"If `isSupported` defaults to `false` … every session's first
  stroke is truncated."* It defaults to **`true`**:

  ```kotlin
  @Volatile
  var isSupported: Boolean = true
      private set
  ```

  and the only write is `isSupported = renderer.onContextCreated(...)`, so the
  flag only ever goes true → false. Before the probe, the value read is the
  initial `true` and the batch is published normally; the gate cannot fire on a
  supported device at any point in the session. The secondary
  happens-before concern is answered by the `@Volatile` that is already there,
  and whose KDoc says why.

  The gate stays because it is an optimisation with no downside — without it the
  batch is queued and then released undrawn by the callback's drain, which is
  one pointless round trip. What the finding is right about is that my round-1
  comment contradicted the code it sat beside: it claimed batches are published
  "without consulting `isSupported`" *after* I had made `stampDabs` consult it.
  Both comments now say the same true thing.

- **R-085 ✅ the `toGlScissor` comment misstated GL's error semantics** (PR #14,
  GLM round 2, Minor). **Applied, and the guard widened to match.**

  My round-1 comment said GL answers a negative scissor with
  `GL_INVALID_VALUE` and keeps the previous box. That conflates two cases.
  `glScissor` rejects a negative **width or height** — that is the documented
  error condition, and the one where the previous box survives. A negative
  **x or y** is legal: the box is accepted and intersected with the
  framebuffer.

  **The replacement I wrote was also wrong, and round 3 caught it — see
  R-086.** It claimed unclipped input would "scissor the wrong rows"; the
  intersection actually reproduces the clipped dirty rect exactly.

  Worth recording that this is the third comment in this PR to claim something
  the code or the platform does not do — after `BufferScissor`'s
  no-allocation claim and `EngineSession`'s `isSupported` line above. The
  pattern is mine: a justification written from memory rather than checked.

  The guard now also rejects inverted rects, the case GL genuinely refuses,
  which the narrower `require` did not cover. Safe for every caller by
  construction — `bounds` yields `left <= right` and `top <= bottom` — and
  mutation-tested: dropping that half of the condition fails the new
  `toGlScissor refuses the inputs GL would mishandle` test. (A first draft of
  that `require` included `rect.right <= Int.MAX_VALUE`, which is always true —
  removed before committing rather than shipped as a condition that cannot
  fail.)

## PR #14 (roadmap 2.5a) — GLM round 3

**Round scored: nits only.** One finding, applied, documentation only — no
behaviour changed and nothing was declined. That breaks round 2's
dismissed-only streak and starts a nits-only one at 1; two consecutive are
needed.

- **R-086 ✅ unclipped scissor rects render the *correct* region, not "the wrong
  rows"** (PR #14, GLM round 3, Minor). **Applied. The finding is right, and
  what it caught is the comment I wrote in round 2 to fix a wrong comment.**

  Round 2 replaced a false claim about `GL_INVALID_VALUE` with a different false
  claim: that unclipped input "would scissor the wrong rows and draw a plausible
  frame in the wrong place". It would not. `toGlScissor` emits
  `(left, H − bottom, right − left, bottom − top)`; a `bottom > H` gives a
  negative `y`, which GL accepts, and the scissor's effective region is the box
  intersected with the framebuffer. Since the y-flip presumes a target exactly
  `bufferHeight` tall, that intersection *is* the dirty rect clipped to the
  buffer. Checked by computing both regions over six cases including
  `top < 0`, `bottom > H` and both at once — all six match exactly.

  So the guard's two halves are worth very different amounts, and the comment
  now says which is which: `right >= left && bottom >= top` is load-bearing,
  because that is the case GL rejects while silently keeping the previous
  frame's box; the clipping half is a tripwire for a caller that bypassed
  `bounds`, with nothing visible at stake. Corrected in the source, the test
  comment, and R-085 above.

  **This is the fourth false justification in this PR, and the first one written
  by the commit that named the pattern.** The other three — `BufferScissor`'s
  no-allocation claim, `EngineSession`'s `isSupported` line, and round 1's GL
  semantics — were at least written before the pattern was visible. This one
  was written immediately after, in a commit whose own message says
  "justifications written from memory instead of checked", and was itself
  written from memory instead of checked. The code has been correct every time;
  the explanations have not. The practical rule this yields, and the one worth
  keeping: a claim about *platform behaviour* gets computed or looked up before
  it is written down, exactly like a claim about a test that cannot fail gets
  mutation-tested.

---

## PR #15 — the predicted tail (roadmap 2.5b)

### Round 1 — three refusals

Round 1 raised 15 findings. Twelve were real and applied (the record is the
commit); three do not survive contact with the code, and are recorded here so a
re-raise is answered from evidence rather than re-litigated.

- **R-087 — declined (refuted).** *"`DabGenerator.copyInto`'s `require` can
  crash the input path on a reused driver"*: the claim is that a `StrokeDriver`
  outliving one stroke would reach `copyInto` with a tail generator built from a
  different preset or seed, and throw `IllegalArgumentException` mid-gesture.

  It cannot fire. `StrokeDriver`'s `preset` and `seed` are `val` constructor
  parameters; `generator = DabGenerator(preset, seed)` is assigned once and
  never reassigned; and `tailGenerator` is `generator.copy()`, which is
  `DabGenerator(preset, seed)` on those same two values. The `require` compares
  the tail generator's preset and seed against *the generator it was copied
  from* — identity-equal by construction. Reuse across strokes would not change
  that, and in fact does not arise: `CanvasScreen.onStrokeBegin` constructs a
  fresh `StrokeDriver` per stroke (`CanvasScreen.kt:131`). Violating the
  `require` needs a caller to pass an unrelated `DabGenerator`, which no code
  does and which is exactly what the `require` is there to catch.

  The suggested `try`/`catch` around it would convert a guard that can only fire
  on a genuine wiring bug into a silent rebuild, which is the opposite of what
  the guard is for.

- **R-088 — declined (refuted).** *"`StrokeDriverTest`'s tail-marking
  assertions may be vacuous against cleared-batch defaults"*: the finding is
  conditional on `DabBatch` defaulting `predictedFrom` to 0, and says to
  disregard it otherwise. It defaults to **−1** (`Dab.kt:143`, in `clear()`),
  so `committedCount` on a cleared batch is `count`, not 0. A `predict()` that
  never marked the batch leaves `predictedFrom` at −1 and `committedCount` at
  `count > 0`, and both assertions fail. Not vacuous.

  The contrast the finding asks for was added anyway — the real batch's `−1`
  and its full `committedCount` are now asserted beside the tail's — because it
  makes the test read as proof instead of requiring the reader to go and check
  the default. That is presentation, not a fix.

- **R-089 — declined.** *"Add a debug-mode assertion that `clearTail()` ran
  before the first tail stamp of a frame"* (Info). The contract has exactly one
  caller — `EngineSession.drainPending` — and the invariant is two lines of it.
  Enforcing it needs a flag set in `clearTail`, consumed in `stampDabs`, reset
  somewhere per frame, and compiled out of release: four moving parts across two
  classes, on the GL thread, to guard a call pair that is visible in one screen
  of code. If the tail ever gets a second producer this becomes worth having;
  today the machinery would be larger than the thing it checks.

### Round 1 — one finding whose own fix was vacuous

The after-`end()` case (a straggler predicted frame arriving after pen-up) was a
real gap and is now covered. The suggested snippet for it was **itself unable to
fail**: it drove `driver()`, whose stabilizer strength is 0, so the leash is
already on the pen, `Stabilizer.finish` walks nothing and `DabGenerator.end`
emits nothing for a non-tap — leaving `committed == 0` and the closing
`assertEquals(committed, finishedOut.count)` comparing 0 against 0, which passes
whether or not `predict` appended a tail. Caught by the
"must have emitted something to be worth checking" guard this suite puts in
front of every count comparison; the test now uses `driver(stabilizer = 0.7f)`
so the flush is real. Tenth vacuous assertion caught across #12–#15, and the
first one arriving from the reviewer rather than from me.

### Round 1 — the false-justification count is now five

`CanvasTouchHandler.fill`'s KDoc claimed a predicted `MotionEvent` carries its
lookahead *inverted* relative to a real event's backlog — "the further-ahead
points are historical and the event's own is the nearest". The reviewer caught
it; it is wrong. `MultiPointerPredictor.predict` obtains the event at the first
predicted instant and calls `addBatch` for each later one, and `addBatch` pushes
the current sample into history and makes the new one current — so historical
samples are the *nearest* predictions and the event's own is the *furthest
ahead*. Read out of the 1.0.0 bytecode.

The code was right, as it has been all five times: `fill` writes history first,
so the last index really is the tip, `keepCount`'s prefix truncation really does
drop the furthest samples, and `PredictionGate` really is fed the most demanding
point. Inverting the fill order to match the comment would have broken all
three. The rule from PR #14 — a claim about platform behaviour gets computed or
looked up before it is written — was followed for the recycling question in the
same file and skipped for this one.

A sixth was caught in the same round, by mutation testing rather than by the
reviewer: a comment added *while applying* round 1 claimed `pressureOpacityMax`
"reaches the dab through flow alone". It reaches no dab field at all — it is
read only by `StrokeDriver.opacityCeiling`, which the merge applies once at
pen-up. Dropping it from `copyInto` kills no test, and both `DabGenerator`'s
KDoc and the test comment now say so.

### Round 2 — one refusal

Round 2 raised four findings. Three applied: the `predict` precondition is now a
`require` rather than a comment (see below), the 2.5b roadmap row no longer bans
a `TailBufferTest` for a class it had just stopped naming, and EXECUTION.md's
"That is now load-bearing" got its antecedent back after round 1's insertion
pushed it six lines from its referent.

- **R-090 — declined.** *"`DabPass.stamp`'s range `require` throws on the render
  thread; clamp `from`/`until` instead"*: the concern is that the range is
  produced by another component (`DabBatch.predictedFrom`, split by
  `CanvasRenderer.stampDabs`) and a producer off-by-one would kill the process
  mid-stroke rather than spoil one frame.

  The range cannot be out of bounds from that caller, and it is now provable
  rather than conventional. `DabBatch.count` is `private set` and moves only in
  `add` (`count = i + 1`, guarded by `isFull`) and `clear` (`count = 0`);
  `predictedFrom` was given `private set` in round 1 of this PR and moves only
  in `markPredictedFromHere` (`predictedFrom = count`, and only while it is
  negative) and `clear` (`-1`, together with the count). So
  `committedCount ∈ [0, count]` holds by construction, and both halves of the
  split — `0..committedCount` and `committedCount..count` — are in range.
  Round 1's `private set` is what closed the door this finding is worried about;
  clamping now would be the second lock on it.

  The wider point is a choice this codebase has already made in the same place.
  `BufferScissor.toGlScissor` carries exactly this shape — a `require` on the GL
  thread whose own comment calls it "a tripwire for a caller that bypassed
  `bounds`" — and DabPass's "a crash loses the painting" line is about
  `PoolExhausted`, an *expected* runtime outcome the pass declines gracefully,
  not about a malformed argument. A clamp does not make a wiring bug survivable;
  it makes it invisible, and draws a plausible frame from a header that was
  already wrong. That is the failure mode this project keeps choosing against.

  Consistency cuts the same way in both directions this round, which is why the
  sibling finding was applied rather than declined: preconditions that can only
  fire on a wiring bug stay loud, in `copyInto` (R-087), in `stamp` (here), and
  now in `predict` too.

---

## PR #16 — stylus axes and dispatch (roadmap 2.5c)

### Round 1 — one refusal

Three findings. Two applied — the unbuffered request hoisted out of the `when`
arm whose nested re-check duplicated the value it switched on, and the hover
KDoc corrected to say that `SOURCE_CLASS_POINTER` unbuffers mouse and
touchscreen hover too, not only a pen.

- **R-091 — declined (refuted).** *"The wrapped canvas azimuth creates a seam
  for interpolated or predicted samples"*: the concern is that downstream code
  blending orientation between two samples would lerp the long way when they
  straddle ±π — 3.13 and −3.13 passing through 0 — and that subtracting
  `view.rotation` *moves* that seam, so a stroke clean at rotation 0 breaks
  after the paper is turned.

  The hazard is real in general and is already handled, by a helper that exists
  for exactly it. Every site that combines two orientations goes through
  `Stabilizer.easeAngle`, and there are three, enumerated rather than assumed:

  - `Stabilizer.push` (`Stabilizer.kt:122`) — the per-sample ease,
  - `Stabilizer.finish` (`Stabilizer.kt:176`) — the pen-up catch-up walk,
  - `DabGenerator` (`DabGenerator.kt:294`) — interpolating between two samples
    to place a dab.

  `easeAngle` is the shortest-arc formula the finding asks for, reduced modularly
  instead of through trig: `delta = (to − from) % 2π`, then `−= 2π` above π and
  `+= 2π` below −π, then `from + delta·t`. For 3.13 and −3.13 that yields
  +0.023, the short way. Nowhere else differences or averages orientation —
  `DabGenerator.kt:300` and `:326` are plain assignments, and `StrokeInput.set`
  copies.

  It is also already tested, and the test straddles ±π exactly as the finding
  proposes: `StabilizerTest`'s
  `the orientation eases the short way around the circle` pushes from just under
  π to just over −π and asserts the result stays near π rather than crossing
  zero. `StabilizerTest` line 51 even names that test as what covers the input
  layer's wrapping.

  So no new test was added — one would duplicate that case — and no call site
  needed changing. Worth recording rather than dismissing, because the finding
  is correct about *where* the seam sits and would be a real bug in a codebase
  that lerped orientation raw; what makes it a non-issue here is a helper that
  predates this PR.

---

## PR #18 — the debug overlay (roadmap 2.5d)

### Round 2 — one refusal

- **R-092 — declined (refuted).** *"`resetPeaks` races with the GL thread's peak
  updates"*: the finding reasons that `resetPeaks` is documented as "called at
  pen-down", that pen events are handled on the main thread, and that `frame()`
  runs on the GL thread — so the read-modify-write on `stampMsMax` could lose a
  reset, and `@Volatile` does not fix that.

  The reasoning about `@Volatile` is correct and the conclusion does not follow,
  because the premise is wrong: `resetPeaks` is **not** called from the main
  thread. It runs inside `CanvasRenderer.beginStroke`, and
  `EngineSession.beginStroke` (`EngineSession.kt:296`) is
  `frontBuffered.execute { renderer.beginStroke(...) }` — the GL thread, like
  every other command the session sends the renderer. `frame()` reaches
  `PerfStats` through `drawStrokeFrame` → `publishFrame`, also the GL thread,
  and `commitMs` through `endStroke`, likewise inside an `execute` block. Every
  write to this object is on one thread; the class doc's "written on the GL
  thread, read on the main thread" is accurate as written.

  The finding names this branch itself — "If they are the same thread, close
  this as no-op and note the class doc is accurate" — and that is the branch the
  code is on. The suggested `resetPending` flag would add a second state machine
  to guard a race that cannot occur, and would break the tests that reset and
  assert immediately, which the finding also anticipates.

  **What the finding did surface is a doc defect**, and that is applied: the
  KDoc said "called at pen-down", which is *when* it happens and reads as *where
  from*. It now names the thread and the call path, and records what would have
  to change if a caller ever did reset from the main thread — so the next reader
  who follows this reasoning finds the answer instead of re-deriving it.

## PR #22 — distinct rail icons (2026-08-27)

- **R-097 ⏸️ Round 2, minor: "GROUPED paint-only divider may render a
  trailing separator."** Declined on the code: `groupedSlots`
  unconditionally appends the four secondary slots (smudge, blur, fill,
  eyedropper), so a GROUPED rail whose brush group is the whole rail cannot
  exist — the `hasToolsAfterBrushGroup` flag the finding asks for can only
  ever be `true`. Independently, `ToolColumn` never renders a divider after
  the final slot (`if (index == slots.lastIndex) continue`), so even a
  divider index pointing at the last slot draws nothing. Two guards make the
  suggested change dead code.

  *Round 1 of this PR applied two minor findings (a divider kept in the
  degraded GROUPED/FULL rails, and a distinct fallback glyph); round 2's
  first finding (Edit aliases Create's pencil glyph) was applied as `Tune`.
  Round 3 was empty.*

## PR #23 — allocation-free visibleCanvasRect (2026-08-27)

- **R-098 🟢 Round 1, major: "cached `viewportCorners` duplicates
  `viewportWidth`/`viewportHeight` state."** Applied: the corners are now
  derived from the viewport size at the call site. The zero-allocation
  profile is unchanged either way, and a second copy of the one viewport
  fact was exactly the kind of cache a future resize path forgets to
  update. Recorded here because the same reasoning then moved the code
  twice more: round 2 applied a readability nit (walk the edges instead of
  decoding a linear corner index).

- **R-099 ⏸️ Round 3, minor: iterate `floatArrayOf(0f, vw)` instead of 0/1
  flags.** Declined: `floatArrayOf` is a plain vararg constructor — it
  allocates two arrays on **every call**, and this function runs on every
  front-buffered stroke frame and every committed frame. The suggestion
  reintroduces exactly the per-frame allocation this PR exists to remove;
  `for (right in 0..1)` compiles to index arithmetic with no allocation, and
  the readability the finding is about was already addressed in round 2.

## PR #35 — live zoom readout (2026-08-27)

- **R-100 ⏸️ Round 1, major: "Live readout freezes: the view transform is
  not snapshot state."** Refuted on the declaration. The finding extends the
  `strokeState` comment ("plain vars, not Compose state") to the view
  transform, but they are different things: the screen's transform is
  `var view by rememberSaveable(stateSaver = VIEW_TRANSFORM_SAVER) {
  mutableStateOf(ViewTransform()) }` — snapshot state. Every navigation step
  writes it (`applyNavigation → host.onViewChanged → updateView`), and the
  chip's `Text` reads `view.scale`/`view.rotation` inside the
  `AnimatedVisibility` **content lambda**, its own recompose scope, so it
  recomposes with the live value every frame the fingers move — and during
  the exit fade, since the lambda stays composed until the animation ends.
  The plain-object claim applies to `CanvasTouchHandler.view` (the handler's
  internal copy the tests mutate) and `StrokeUiState`, never to the
  composable's `view`. `ResetViewPill` reads the same state the same way
  and its readout updates live in the shipped v1.0.1+. A `withFrameNanos`
  sampling loop would double-represent state the snapshot system already
  invalidates.

## PR #53 — stroke-gated navigation (2026-08-27)

- **R-101 ⏸️ Final round, minor: make `finishLeave` tolerate an unstarted or
  repeated release.** Declined: `requestLeave` is the sole Leave producer and
  stores its callback before dispatch. `beginLeave` starts gate work before
  launching the job. Its handed-off and failure branches are exclusive, and a
  retry clears `leaveJob` ownership before reopening the gate, so the old grace
  timer cannot release again. The checks expose a broken invariant; relaxing
  them would hide one.
## PR #58 — Canvas share chooser title (2026-08-27)

- **R-101 ⏸️ Round 1, major: "Empty painting name produces a blank chooser
  title."** Refuted at the call site: the title handed to `sharePainting`
  is `paintingName`, which `CanvasContent` builds as `state.title.ifBlank
  { stringResource(R.string.studio_untitled) }` (already the a11y
  description's value). A blank title cannot reach the chooser; adding a
  second fallback inside `sharePainting` would guard a state the caller's
  contract excludes.

- **R-102 🟢 Round 1, minor: `ifEmpty` misses whitespace-only titles.**
  Applied: the Studio chooser fallback and — for consistency with it — the
  cell's own Untitled display now use `ifBlank`, so a blank-stored title
  shows and shares the same name.

- **R-103 ⏸️ Round 1, minor, outside diff: add `ClipData.newRawUri` so the
  `EXTRA_STREAM` grant propagates.** Declined: the grant-propagation gap
  the finding describes is pre-API-24 and OEM-specific below that band;
  minSdk is 29 (ADR 0002) and `Intent.createChooser` propagates
  `FLAG_GRANT_READ_URI_PERMISSION` on every API this app runs. The extra
  ClipData would be dead defence here; revisit if a device report ever
  shows a `SecurityException` out of a share.

- **R-104 ⏸️ Round 1, minor, outside diff: a failed Canvas share toasts
  the save-failure string.** Real, and applied in PR #62 ("Report Studio
  action outcomes"), which adds `studio_share_failed` to both locales and
  repoints both share paths at it. Keeping the string change out of this
  PR's scope.

## PR #109 — Water slider preset opacity (2026-08-28)

- **R-105 ⏸️ Round 1, major, outside diff: "a downstream consumer may
  still read `preset.opacity` as water load, so water strokes could render
  at full strength regardless of the load slider."** Refuted against the
  code: water load flows only through `WaterParams` →
  `RmwStrokePolicy.spec` → `RmwSpec.Water/Watercolor.behavior` →
  `WatercolorPass` (`u_waterLoad = behavior.waterLoad * batch.flow`);
  slider write-back routes `updateActiveToolSecondary` → `withWaterLoad`;
  size write-back routes `withSize`. `ToolSliderPreset.forKind` has exactly
  two consumers (ToolRail, CanvasScreen's ledge), both reading size fields
  plus `secondaryValue`. The one place `preset.opacity` feeds
  `StrokeSpec.opacity` (CanvasScreen pen-down) is dead for Water:
  `renderer.endStroke` dispatches RMW strokes to `endRmwStroke`, which takes
  no opacity ceiling, and `WatercolorPass.stamp` reads `dilution`,
  `alphaLock`, and `rmw` — never `stroke.opacity`. The old test name's
  "pressure headroom" was `pressureFlow = pressureWater`, not opacity.

## PR #107 — Watercolor cancel restore failure (2026-08-28)

- **R-108 ⏸️ Round 3, info: split the else-branch so a live backup's
  pre-gesture water survives a transient GL failure.** Declined: the
  trade is the PR's stated intent. Cancel's core promise is that no
  gesture-added water survives; on an unrestorable page, keeping it
  means keeping exactly that water. Pre-gesture wetness is a 12 s
  transient medium, and a GL allocation failure mid-cancel already
  means degraded output elsewhere. The reviewer's own close condition
  ("if false is only returned when the backup is absent") does not
  hold, but the choice between the two failures is deliberate and
  documented in the PR body.

- **R-109 ⏸️ Round 3, info: split the extraction guard into one
  assertion per anchor.** Declined: diagnostic polish; the prompt
  itself permits skipping, and the combined message already names both
  suspects.

## PR #106 — Paintbrush migration tuning (2026-08-28)

- **R-106 ⏸️ Round 2, major: "`Log.w` can crash local JVM unit tests
  exercising the fallback path."** Refuted:
  `unitTests.isReturnDefaultValues = true` is already set
  (`app/build.gradle.kts`), and the store's existing `Log.w` drop paths
  run under the JVM suite today (the invalid-override test). The
  fallback-path test is green locally and in CI.

- **R-107 ⏸️ Round 3, minor: the fallback graft re-enters the
  validating constructor with unguarded user input and can throw,
  discarding the user's file.** Refuted: `withSize` clamps into the
  replacement's own window (`value.coerceIn(sizeMin, sizeMax)`,
  BrushPreset.kt), `flow` arrives 0..1 from the already-validated user
  preset, and every other field comes from the valid replacement — the
  fallback expression cannot throw. The PR's own test
  (`an unmigratable size window adopts the replacement instead`)
  exercises exactly the claimed scenario (size 1500 outside the
  replacement's 4..400 window) and passes with the clamped 400.

## PR #136 — durable paint slots (2026-08-28)

- **R-105 ⏸️ Round 1, minor: catch `assignPaintSlot` invariant failures in
  `selectBrushPreset`.** Declined. The callback exists only in `UiState.Ready`,
  after `loadPaintSlots` initializes shared state; failed DataStore reads
  initialize it from an empty list, and the catalogue always has the fallback
  paint. A throw therefore signals a programming error. Catching it would
  activate the brush without assigning its slot, recreating the inconsistency
  this PR removes.

- **R-106 ⏸️ Round 1, outside diff: `synchronized(paintSlotLock)` may block
  the UI or reorder persistence.** Refuted on the implementation. The monitor
  only updates a `MutableStateFlow` and calls non-blocking `Channel.trySend`;
  it performs no DataStore or suspending work. One app-scoped coroutine drains
  that conflated channel and awaits each `dataStore.edit` sequentially.
  Superseded full snapshots may be skipped, but the latest cannot be overtaken.

## PR #141 — selectable application themes (2026-08-28)

- **R-107 ⏸️ Round 1, major: replace every `ThemeContractTest` source
  check with a behavioral test.** Applied where behavior is a plain-JVM
  decision: enum decoding was already covered, and preference recovery now has
  `PreferenceFlowRecoveryTest`. Declined for Android integration. This repo has
  no `androidTest` or emulator job. A fake-`Prefs` ViewModel test would widen a
  concrete data-layer boundary solely for a test. The remaining source
  contracts pin Activity, Compose, and resource wiring that the JVM cannot run.

- **R-108 ⏸️ Round 1, minor: cold start renders Saffron Compose frames
  before the stored theme.** Refuted at `MainActivity`: a null theme executes
  `return@setContent`, so neither `BangniTheme` nor navigation composes before
  the first preference value. The visible Saffron surface is the fixed resource
  launch window, deliberately used because it cannot read DataStore. Keeping a
  splash on screen would retain that same window longer, not remove it.

- **R-109 ⏸️ Round 1, outside diff: resource-level dark-mode guards are
  absent.** Refuted on the full PR. `values/themes.xml` uses the fixed Light
  parent, launch background, light system bars, and
  `android:forceDarkAllowed=false`; no night resources remain. The contract
  rejects every values directory carrying a night qualifier and pins the
  launch colour to the default palette.

- **R-110 ⏸️ Round 1, outside diff: `kotlin-test` may be absent.**
  Refuted in `app/build.gradle.kts`: `testImplementation(libs.kotlin.test)` is
  already present, and the complete JVM suite compiled and passed at the
  reviewed commit.

- **R-111 🟢 Round 1, outside diff: sibling plans retain obsolete theme
  terminology.** Initially refuted after the current plans were clean. Latest
  main's backlog consolidation now records PR #141's four fixed selectable
  palettes and rejects a competing theme path. The dated `k3.md` snapshot and
  roadmap step 1 remain as historical records.

- **R-112 🟢 Round 2, minor: live-stroke updates can mix checker themes or
  replay a stale palette after begin refusal.** Applied test-first. Checker and
  canvas-void mutations now wait for commit or cancel, preventing a dirty front
  frame from patching new cells over the old baseline. A newer immediate choice
  clears any refused stroke's deferred value.

- **R-113 🟢 Round 2, minor: default-equal Material roles can escape the
  scheme test.** Applied. `BangniTheme` now constructs `ColorScheme` directly,
  so every role is required; a source contract prevents regression to the
  defaulting factory.

- **R-114 🟢 Round 2, minor: the Appearance semantics slice can absorb later
  sections.** Applied. The contract now requires the exact Drawing-section
  boundary before checking the theme radio group.

- **R-115 🟢 Round 2, minor: contrast coverage omits used chrome surfaces.**
  Applied. Palette tests now cover primary and secondary across their actual
  surfaces, selected markers, and shared error roles; the plan records the
  exact palette ratios.

- **R-116 🟢 Round 2, major: preference retry loop is unbounded and stops
  logging after the first failure.** Applied. Reads now back off
  exponentially and give up after five attempts, ending the flow on its last
  value with a final warning; a `ReplaceFileCorruptionHandler` resets a
  corrupt store before any flow sees it. Tests pin the attempt count, backoff
  order, single fallback, and post-load stability.

- **R-117 🟢 Round 2, major: substring contracts match comments and only
  scan Theme.kt for `isSystemInDarkTheme`.** Applied. Kotlin sources are
  loaded comment-stripped, and the dark-mode token is rejected across every
  `.kt` file under `app/src/main/java`.

- **R-118 ⏸️ Round 2, major: `ThemeColorPolicy` in `engine/core` couples the
  engine to UI theming.** Declined as a move; documented instead. The repo is
  single-module and `engine/core` is its only Compose-free JVM home (02 §2.2);
  GL consumes just the constant `canvasVoid`. 08 §5.1 now says so.

- **R-119 ⏸️ Round 2, minor: null initial theme can flash the default
  palette.** Refuted like R-108: a null theme executes `return@setContent`, so
  nothing composes before the first emission. A contract now also pins that
  every emitted state is non-null by construction (`map(::UiState)` over a
  non-null flow).

- **R-120 ⏸️ Round 2, info: latch the loaded theme against a transient null.**
  Declined. `stateIn` never re-emits its initial value and the recovery flow
  emits only concrete palettes, so null is initial-only; the R-119 pin locks
  that invariant instead of adding a second state holder.

- **R-121 ⏸️ Round 2, info: apply retry/fallback resilience to every
  preference flow.** Declined for scope. The corruption handler now covers the
  persistent-failure mode for the whole DataStore file; the retry helper
  exists because the launch gate depends on the theme flow's first emission.
  Migrating sibling flows changes their failure behavior and tests; that is
  separate work.

- **R-122 ⏸️ Round 2, minor: gate only density changes on stroke commit.**
  Refuted. The checkerboard pair is palette-derived (`surface`/
  `surfaceVariant` at `CanvasScreen`), so theme updates do reach GL and must
  wait for the commit/cancel scene to avoid mixing old and new cells in a
  dirty front frame.

- **R-123 🟢 Round 2, minor: cancel's dead-surface return drops the deferred
  appearance.** Applied test-first. All three early returns in
  `endStroke`/`cancelStroke` restore the deferred value for the next change
  or session recreation; the contract counts them.

- **R-124 ⏸️ Round 3: tertiary mapping, container/inverse contrast pairs,
  the system-bar light invariant, and the 02 §7 launch gate re-flagged.**
  Declined: each is already present at the reviewed SHA, in the suggested
  form or a broader one (the system-bar test covers four surfaces, not just
  background). The rebase rewrote SHAs, so the delta window re-audited
  unchanged code.

- **R-125 ⏸️ Round 3, minor: make the retry fallback a provider latched to a
  shared `loadedTheme`.** Declined. A contract pins the activity-scoped
  ViewModel as the only `Prefs.appTheme` collector, and activity-scoped
  ViewModels survive recreation; a second collector cannot observe a flash.
  The latch would add cross-collector mutable state to `Prefs` for a case the
  architecture forbids.

- **R-126 ⏸️ Round 3, re-flagged: move `ThemeColorPolicy` out of
  `engine/core`.** Still declined per R-118.

- **R-127 ⏸️ Round 3, re-flagged: gate only density changes on stroke
  commit.** Still refuted per R-122; the checkerboard pair is
  palette-derived.

- **R-128 🟢 Round 3, minor: backoff cap, guard ordering, marker robustness,
  string-safe comment stripping.** Applied. The retry backoff caps its
  multiplier at 16; the appearance contract pins guard-before-immediate
  order; configure-argument markers drop trailing commas; comment stripping
  keeps string and char literals; the dark-mode scan covers all of
  `app/src/main`.

- **R-129 🟢 Round 4, minor: a reconfigure can inherit a stale deferred
  appearance.** Applied test-first. `configure` now clears
  `pendingCanvasAppearance` before queueing the newer full appearance, and
  the contract pins it.

- **R-130 🟢 Round 4, minor: dedup theme emissions, clamp the backoff shift,
  strip XML comments in contracts.** Applied. `distinctUntilChanged` ends the
  theme flow so unrelated preference writes do not recompose the app; the
  backoff exponent is capped at 4 (the 16× multiplier's match); XML contract
  loads ignore `<!-- -->` comments; StudioViewModel is forbidden from even
  reading `prefs.appTheme`.

- **R-131 ⏸️ Round 4: the round-2/3 items were flagged again verbatim
  (tertiary roles, container pairs, system-bar invariant, engine/core move,
  density-only gating, fallback provider, 08:718 pair enumeration).**
  Declined as already applied or already adjudicated — see R-116, R-118,
  R-122, R-124, R-125. The hybrid audit keeps re-reading unchanged code.

- **R-132 ⏸️ Round 4, minor: record per-palette computed ratios in `Color.kt`
  comments.** Declined. The ratios live in 08 §5.1's table and are enforced
  by `ThemeColorPolicyTest`; duplicating computed numbers into source
  comments invites drift.

- **R-133 ⏸️ Round 4, minor: AGENTS.md should demand deleting leftover
  night resources.** Declined. The rule already says add none, and the
  contract rejects every night-qualified directory, which covers leftovers.
  (Round 4 landed as PR #148: #141 was merged while its review ran.)

## PR #148 — theme review round 4 (2026-08-28)

- **R-134 🟢 Round 1, minor: backoff constants are silently coupled; the
  dedup pin scans all of Prefs.** Applied. The shift/multiplier invariant is
  documented at the constants, and the `distinctUntilChanged` assertion is
  scoped to the `appTheme` flow section with loud marker failures.

- **R-135 ⏸️ Round 1, info: `UserPreferencesTest` belongs under `data/`.**
  Refuted. The class lives in `engine/core` and tests the stored enums there
  (`Hand`, `TouchDrawingMode`, `AppTheme`, …); `data/Prefs` only persists
  them. The tree entry mirrors the real package.

## PR #151 — probed bitmap byte order (2026-08-28)

- **R-136 ⏸️ Round 3, info: add an instrumented round-trip test for
  `BitmapLayoutProbe.probe()`.** Declined. The suite is JVM-only by design
  (AGENTS.md): no androidTest directory exists, and adding one means adding
  the emulator CI job too. The device-dependent half is three framework
  calls around `classify`, which the JVM tests pin for both layouts plus
  the loud no-match failure; the probe cannot drift without a framework
  change no test here could anticipate anyway.

## PR #150 — darker and louder themes (2026-08-28)

- **R-137 🟢 Round 1, minor: bar icons flash light on Activity recreation
  with a dark theme.** Applied. Cold starts still begin light (DataStore is
  unreadable), but recreation now seeds the bar style from the retained
  ViewModel's tone; the contract pins the seeding and the tone-to-style
  wiring.

- **R-138 ⏸️ Round 1, minor: dark palettes inherit light-baseline defaults
  for unmapped M3 roles.** Refuted. `bangniColorScheme` calls the complete
  `ColorScheme` constructor — no parameter is defaulted — which R-113
  introduced and `scheme construction cannot inherit Material baseline roles`
  plus `BangniColorSchemeTest`'s role-by-role mapping pin.

- **R-139 🟢 Round 1, minor: error expectations came from the code under
  test.** Applied. `ThemeColorPolicyTest` now pins the exact light/dark
  Material error baselines as literals, so `BangniColorSchemeTest` is a pure
  wiring test again.

- **R-140 🟢 Round 1, info: stale "shared error" wording and the roadmap
  graph.** Applied. 11-testing says per-tone error content; §4's graph gains
  step 14 depending on 13.
## PR #153 — tracing references beyond the canvas (2026-08-29)

- **R-141 ⛔ Round 1, major: gate `drawReferenceAcrossVoid` on `useSandwich`
  or the void composites the reference twice in transitional states.**
  Refuted. `useSandwich` is defined two lines above as
  `readyCache != null`, so the suggested guard is a tautology; no state
  exists where the branch runs without Below, and `belowIsCacheable`
  accepts every blend mode while `rebuild` never skips the base draw —
  Below always carries the baked reference there. The reviewer's own
  fallback (document the invariant beside the call) is applied as a
  comment.

- **R-142 🟢 Round 1, minor: document the fractional-scale abutment
  caveat on `rawScreenBoundsOf`.** Applied to the KDoc. At non-integer
  mapped edges `ceil` can leave a sub-pixel sliver of true void uncovered
  beside the canvas; accepted and documented rather than snipping band
  edges into the canvas, which would double-composite the border column
  over transparent paper.

- **R-143 🟢 Round 1, minor: the four-band arithmetic is untested.**
  Applied. Extracted as `voidBandsAround(canvas, clip)` in
  `engine/core` with JVM tests for the suggested cases: covered clip,
  canvas past one edge, canvas inside the clip (disjoint, union equals
  clip minus canvas), and partial overlap.

- **R-144 🟢 Round 1, minor: assert the exact magnified bounds rect.**
  Applied; the loose inequalities are gone.

- **R-145 🟢 Round 1, info: the absolute axis-alignment epsilon is
  zoom-dependent.** Applied as the relative form
  `min(|a|, |b|) >= AXIS_ALIGNED_EPS * max(|a|, |b|)`, which reads as the
  rotation's tangent and is invariant under zoom. The degenerate
  all-zero basis skips the pass, which is correct — nothing renders at
  scale zero.

- **R-146 🟢 Round 2, minor: the canvas-equals-clip boundary case is
  untested.** Applied. Exact equality walks all four band conditions on
  their strict `<` boundaries, which is the only guard — the draw loop no
  longer filters empty bands — keeping degenerate rects away from
  `glScissor`.

- **R-147 🟢 Round 3, minor: reword the rotation-guard comment to name the
  case that returns.** Applied verbatim. The round-2 boundary assertion
  was re-posted against the commit that already applied it; treated as a
  stale anchor, not a new finding.

## PR #152 — gallery variant with the tracing image (2026-08-28)

- **R-148 🟠 Round 1: `withdraw` never checked row ownership.** Applied.
  `!row.owned` joins the guard; the KDoc and AGENTS.md both promised
  "ours and untampered", and a recycled row id with 0/0 recorded state
  must not be deletable on the tamper half's say-so alone.

- **R-149 🟠 Round 1: flat decode double-materializes the reference.**
  Partially applied. The premise is wrong — import normalizes the asset
  to ≤ canvas pixel bytes (`TracingReferencePolicy.normalizedSize`), so
  no 50 MP decode exists — but the size check ran *after* the allocation,
  so a replaced/hand-mangled asset file could balloon first. Bounds are
  now decoded (`inJustDecodeBounds`) and compared before any pixel
  allocation. The `BitmapRegionDecoder` streaming rewrite is declined:
  the offline path is bounded and debounced, and the canvas's own
  `streamTiles` already exists for the per-frame side.

- **R-150 🟠 Round 1: unsettled withdrawal cleared the recorded URI.**
  Applied in both ViewModels. `withdraw` now returns whether the row is
  settled (gone, or no longer ours to touch); a retryable delete failure
  keeps the URI and leaves the variant due, so the row cannot be
  orphaned in the gallery or duplicated when a reference returns.

- **R-151 🟠 Round 1 (as minor a/c/d/f + test g/h/i): the surrounding
  hardening.** Applied: `ensureActive` after the reference decode, a
  failed clean copy no longer aborts a pending variant/withdrawal, the
  contract test pins its own delimiters (which immediately caught this
  PR's own false-pass — `private fun sync(` never existed), the
  suffix-cap KDoc tells the truth, the shadowed `reference` local is
  `placed`, and the policy suite pins OR semantics plus floor-past
  staleness.

- **R-152 ⏸️ Round 1: a permanently undecodable reference re-encodes the
  painting on every sweep.** Declined. The state requires the app-private
  asset to be externally deleted or mangled *after* commit — the loader
  drops an unreadable reference at open, so the sweep sees it only
  through a race window — and the sweep is human-triggered (Studio
  show/return), not a loop. Settling would freeze a stale variant row;
  eventual retry is the cheaper wrong.

- **R-153 ⏸️ Round 1, info: make reference publication opt-in beyond the
  visibility/opacity gate.** Declined. The product owner directed the
  auto-store; the settings help string discloses it. An opt-in toggle is
  a separate product decision for the owner, not a review fix.

- **R-154 ⏸️ Round 1, info: two writers own the reference gallery
  fields.** Refuted as a new hazard. It is the `galleryUri` pattern
  exactly, and the interleaving it fears is closed by the leave gate:
  the Studio's sweep only runs on refresh, and navigation returns only
  after the canvas's final checkpoint completed.

- **R-155 ⏸️ Round 1, info: share the tile pass between the two
  flattens; ViewModel tests for the staleness gate.** Declined. The
  double flatten is the AGENTS-accepted "seconds on IO" cost, debounced
  30 s / leave; the ViewModel split follows the repo's stated rule
  (decisions pure and tested, MediaStore/VM orchestration untested —
  AGENTS.md's gallery-debounce precedent).

- **R-156 🟢 Round 2, minor: contract test pinned delimiter existence, not
  order; `ReferenceComposite.includes` duplicated the policy gate; a missing
  asset logged as dimension drift.** All three applied — the probe window is
  now bounded by construction, the composite delegates to
  `ReferenceGalleryPolicy.includes`, and the gone-asset case gets its own
  log line.

- **R-157 🟠 Round 3: AAPT would trim the suffix's leading space; a
  permanently undecodable asset churned the sweep forever.** Both applied.
  The resource is quoted (`" (with reference)"` — verified it survives into
  `packaged_res`), and `syncReferenceVariant` settles on a null decode: the
  loader already drops a *missing* asset at open (so the sweep's withdraw
  branch owns that case), leaving only corruption or the load→decode race,
  neither of which heals — re-encoding the clean copy every sweep for them
  was the R-152 cost with no payoff. This narrows R-152's decline: the
  settle applies to the undecodable asset, while retry still governs
  row-write and withdrawal failures.

- **R-158 🟢 Round 4, minor: the withdrawal test pinned two of three
  fields.** Applied — the withdrawn byte count is asserted too.

- **R-159 🟠 Round 5: a transient probe failure forgot the reference row.**
  Applied. The probe's outer catch now retries like the delete path does —
  an orphaned "with reference" row is the privacy-sensitive failure this
  feature exists to avoid — with one carve-out: `IllegalArgumentException`
  (a URI the provider will never accept) stays settled, because that probe
  can never succeed and retrying it is the R-157 churn pattern.

- **R-160 🟠 Round 6: `variantDue` never settled on reference-less
  paintings.** Applied. `ReferenceGalleryPolicy.variantInvolved` (pure,
  tested) short-circuits the due check when there is no reference to mirror
  and no row to reconcile, so a plain painting's pixel revisions cannot
  relaunch a no-op gallery job forever; the Main block also skips the
  equal-copy document write and the dirty flag when a run settled nothing.

- **R-161 🟡 Round 7: the early return might skip trailing cleanup.**
  Verified and refuted — the Main block after the guard contains only the
  outcome-gated counters, the document copy, and `markDirty`, in that
  order, and ends there; no flags, observers, or in-flight guards exist
  on this path. The finding's own escape clause ("if the block truly
  ends with the outcome-gated persistence, the change is correct")
  was checked statement by statement and holds.

- **R-162 🟠 Round 9: the variant branch ran whenever the job opened.**
  Applied. `variantDue` now gates the sync and withdraw branches, not just
  the combined early return — a clean-copy retry can no longer re-encode
  identical variant pixels and churn the row's modified date.

- **R-163 🟡 Round 9, audit: CHANGELOG missed the channel-swap fix.**
  Applied — the Unreleased section now records the BGRA export/thumbnail
  correction the merge carries, beside this PR's variant entry.

- **R-164 ⏸️ Round 9, audit: void bands on an empty canvas; band tile-set
  resubmission; unguarded contract-test markers.** Declined for scope —
  all three sit in PR #153's renderer and test code, already landed on
  main with its own review rounds; touching the frame-budget hot path
  here would ship changes their device gate never saw. Re-raise them on
  the next audit round or a dedicated follow-up.

- **R-165 🟡 Round 10: the duplicate test pinned one of three variant
  fields.** Applied — the stamp and byte count resets are asserted too,
  matching the withdrawal test's convention.

- **R-166 🟢 Round 11, info: an assertion message named u where the row
  index pinned v.** Applied — diagnostic only, but the message now points
  at the axis the coordinate actually left.

- **R-167 🟢 Round 12, info: duplicate probe-refusal log line.** Applied —
  `probeRow` already logs the refusal with its stack; the decision shows in
  the insert that follows.

- **R-168 🟡 Round 13: a pending withdrawal seemed stranded after process
  death.** Refuted by the search the finding itself prescribes: the Studio
  sweep reconciles it on every app start — a reference edit bumps
  `updatedAt`, an unsettled withdrawal leaves `lastGallerySyncAt` behind,
  so `updatedAt > lastGallerySyncAt` holds and `syncReferenceVariant`
  retries the withdrawal without needing any edit in the document. The
  session counters gate only the in-canvas fast path.

- **R-169 🟡 Round 13: one checkbox governs two gallery entries.** The
  two-checkbox UI is declined — the rows are one feature, and per-row
  toggles over-UI a rare combination — but the label no longer promises a
  single copy: "Also delete the gallery copies" (zh already
  number-neutral).

## PR #154 — Chinese ink brush stroke shape and drying (2026-08-29)

- **R-170 🟡 Round 4: "nothing pins the splay easing itself."** Declined —
  factually wrong. `the Chinese ink tuft eases from a round touch into its
  splay` (DabGeneratorTest, first commit) pins the whole property the
  finding asks for: first dab round, second dab still above the snap
  midpoint, last dab converged on the tip aspect. It fails if `travel`
  stops propagating (every dab stays round) or if `settle` is removed
  (dab 2 snaps), which is the finding's own verification recipe. The
  suggested `next.aspect < first.aspect` inside the turn fixture would
  duplicate it weaker and pressure-confounded.

## PR #155 — ink lanes follow the path frame (2026-08-29)

- **R-171 🟡 Round 4: "laneAngle silently swapped the predicate from `ink`
  to `inkDynamics`."** Refuted: there is no second field. `inkDynamics` is
  the class's only ink state (line 36); the `ink` at both call sites is a
  local `val ink = inkDynamics` alias (lines 230/427/446), so the
  predicates are identical by construction and no divergent-nullity path
  exists. A comment at `laneAngle` now states the aliasing so the next
  reader does not have to re-derive it.

## PR #156 — in-app (i) documentation (2026-08-30)

- **R-172 ℹ️ Round 1: "add a Compose UI test for InfoButton."** Declined —
  per AGENTS.md the suite is JVM-only and no androidTest directory exists;
  adding one means adding the emulator CI job too. InfoButton/InfoDialog
  carry no decision logic (a state boolean and a pass-through string), so
  there is nothing JVM-testable; the existing source-marker contract tests
  already cover the call sites they restructure.

## PR #160 — thumbnail write retry (2026-08-30)

- **R-174 🟡 Round 1: "gate `maybeSyncGallery` on the thumbnail result" and
  "nothing re-runs a checkpoint on `thumbDirty` alone."** Both refuted
  against the code, per the review's own if-then framing. Gallery sync never
  consumes `thumb.png`: `maybeSyncGallery` flattens the flushed tiles through
  `CpuFlatten`/`ImageEncode` and mirrors that PNG — the shelf thumbnail is a
  separate artifact — so a failed thumbnail cannot stale the gallery and the
  gate would only delay an unrelated mirror. And the retry does not depend on
  a new edit: leave and `ON_STOP` checkpoints run unconditionally
  (`checkpointNow`), and `checkpointLocked`'s fast path exempts
  `thumbDirty` (`!dirty && !thumbDirty`), so a kept flag forces the full
  path at exactly the moment the shelf is next seen. The remaining window —
  a failed write with the app simply left open — closes at the next quiet/
  ceiling checkpoint after any edit, which is the pre-existing cadence. The
  round's two test-robustness notes (whitespace normalization per the house
  rule; a comment distinguishing the two clear constants, which are both
  used) were applied.

## PR #161 — rotation-safe transient dialogs (2026-08-30)

- **R-175 ℹ️ Round 1: "scan every ui/ file for AlertDialog flags using plain
  `remember`."** Declined as a design suggestion (the review marked it
  as such). A repo-wide scanner needs an allowlist of legitimately transient
  state (menus, popovers, focus flags, drag state), and that allowlist is
  exactly the judgment the scanner was meant to remove — each new false
  positive would be resolved by growing the list until the test asserts the
  list. The convention lives in this test's KDoc and the three fixed files
  pin the pattern; new dialogs are reviewed against it like any other
  convention. The round's two matching-robustness fixes (whitespace
  normalization; asserting the draft's declaration rather than its seeding
  expression) were applied.

## PR #158 — dock corners sit flush on the bottom edge (2026-08-30)

- **R-177 🔴 Round 2: "`Shape` has no `copy(...)` — the dock shape call
  likely does not compile."** Refuted with the resolved dependencies and
  the CI run on the flagged head. The premise ("`Shapes.large` is
  statically typed as `Shape`") is wrong for Material 3: in
  `material3-android:1.4.0` (this project's resolved artifact),
  `Shapes.getLarge()` returns
  `androidx.compose.foundation.shape.CornerBasedShape`, and
  `foundation-android:1.12.0` declares
  `CornerBasedShape.copy(topStart, topEnd, bottomEnd, bottomStart:
  CornerSize)` — so `MaterialTheme.shapes.large.copy(bottomStart = …,
  bottomEnd = …)` resolves without any cast or project-local extension.
  The `android` job on the exact flagged head `5fb8bfd` compiled the
  module and ran `DockShapeContractTest` (which pins this very line)
  green. The suggested `as RoundedCornerShape` cast would *narrow* the
  contract for no gain: `copy` is defined on the `CornerBasedShape` the
  theme already exposes, and casting would break if a theme ever supplied
  a `CutCornerShape`.

## PR #157 — mouse-wheel zoom at the cursor (2026-08-30)

- **R-173 ℹ️ PR #157 round 1: "test `onGenericMotion` itself with Robolectric
  or androidTest."** Declined — the suite is JVM-only by policy (AGENTS.md:
  no `androidTest` directory exists, and adding one means adding the emulator
  CI job; `MotionEvent` cannot be constructed on the JVM). The test file's
  own header states this exact boundary: `onTouch`/`onHover`'s translation is
  device-only and named rather than mocked, and `onGenericMotion` joins that
  list. Everything the handler *decides* is reachable through `handleScroll`
  and `ScrollZoom.pivot` (round 2 extracted the touchpad-vs-cursor pivot
  choice into that pure helper precisely so this stays true) and fully
  tested. Same disposition as R-172's Compose-UI-test ask. The
  review's other two findings (accept `SOURCE_TOUCHPAD`, sum historical
  scroll samples) were applied in the same round.

## PR #161 addendum — the round-2 stable-keys claim (2026-08-30)

- **R-176 🟡 Round 2 (post-steady-state): "positional `rememberSaveable`
  restore could attach a delete dialog to the wrong painting."** Refuted —
  the shelf grid already supplies stable keys:
  `items(state.paintings, key = { it.id })` (StudioScreen.kt), with named
  keys for the fixed New/empty cells, so saveable state inside
  `PaintingCell` is keyed to the painting's id, not its position, and a
  reorder between save and restore cannot re-attach an open dialog. The
  review's own prompt for the finding ends "If a stable key is already
  present, no change is required" — it is. Answered on the PR before the
  merge because an unanswered wrong-painting-delete claim would have
  misled the merge decision.

## PR #162 — the layer header stops starving its buttons (2026-08-30)

- **R-181 🟠 Round 1: "`TextOverflow` import isn't anywhere in the diff"
  (possible unresolved reference).** Refuted by the file and the build:
  `LayerPanel.kt` has imported
  `androidx.compose.ui.text.style.TextOverflow` since the layer rows
  began ellipsizing (line 593 uses it), which is why the diff did not
  need to add it and why the `android` job compiled this head green. Per
  the finding's own "if it is already present, no change is needed". The
  round's other points were applied: the count label ellipsizes, the
  weighted-spacer ban matches any spelling, the trailing-actions anchor
  moved to the yielding title's own modifier, and the count's
  unweighted-by-intent priority is now stated in a comment.

## PR #163 — preference reads recover instead of crashing (2026-08-30)

- **R-179 🟡 Round 1 (outside diff): "verify `loadPaintSlots` callers
  cannot run while `paintSlotState.value` is null."** Verified and closed
  as no-change, per the finding's own if-then framing: the gate it asks
  callers to add already lives inside the API. `loadPaintSlots` opens
  with `paintSlotIds.first()` on `paintSlotState.filterNotNull()`, so it
  suspends through the whole retry window; the sole external call site
  (`CanvasViewModel`'s open path) goes through it, and `assignPaintSlot`
  is reachable only from slot UI that renders off `paintSlotIds`
  emissions — which exist only after the state is non-null. A longer
  retry window therefore lengthens a suspension, never a
  `requireLoadedPaintSlotIds` crash. The round's three in-diff fixes
  (no pause after the final attempt; widened member-start terminators —
  `@`-annotated members already exist at that indent; the staleness
  floor raised to the true count of 17, which the old "16" comment
  itself miscounted) were applied.

## PR #165 — dense built-flags for the sandwich rebuild (2026-08-30)

- **R-180 🟠 Round 1: "dense built-flags pin `grid.tileCount` at
  construction time" (crash-class if the grid resizes).** Refuted: the
  bound cannot move. `TileGrid` is a data class whose `tilesX`/`tilesY`
  are vals derived from immutable constructor vals, so `tileCount` is
  constant per instance; `CanvasRenderer.grid` is a `private val` built
  once from the canvas size, and the cache captures that same instance
  for life — a resize is a new renderer and a new cache. `grid.index` is
  total for every key `grid.keysFor` emits because both derive from the
  same immutable dimensions. The `invalidateScratch` growth the finding
  read as a resize hint is first-use sizing against that same constant.
  The round's two Minors — the `!below && !above` early return (the
  common active-layer-only edit) and the `BuiltFlags` holder that fuses
  each flag array with its count — were applied.

## PR #167 — committed frames cull to the visible rect (2026-08-30)

- **R-178 🟠 Round 1 (outside diff): "visibleCanvasRect rotation and
  empty-rect contract unverified."** Refuted against the implementation,
  per the finding's own if-then framing. Rotation: the function walks all
  four viewport corners (a 2×2 loop over the edges) through the inverse
  transform and accumulates min/max, so a rotated viewport's bounding box
  is exact by construction — there is no two-corner shortcut to fix.
  Empty rect: `compositeIntoAccum` returns false only when binding the
  Accum FBO fails; the cull rect is consumed by the tile loops, so an
  off-screen canvas culls every tile draw, leaves paper/reference in
  their viewport-space passes, and returns true — no per-frame failure
  path exists. The round's three in-diff test-robustness fixes (regex
  tolerance for both pins, a clamped source window) were applied.

## PR #170 — surrogate-safe gallery names (2026-08-30)

- **R-182 ℹ️ Round 1 (outside diff): "no tests for surrogate-boundary
  truncation."** Refuted by the diff itself: `GalleryNamesTest` ships
  three tests in this same PR — the cap never splitting a pair, the
  reference-suffix cut never splitting a pair, and a whole emoji
  surviving inside the cap. The review's own coverage note explains the
  miss ("no patch returned by GitHub" for the test file), so the finding
  was raised blind to the file that answers it. The round's real Minor —
  `takeWholeCharacters` indexing at -1 for a non-positive count — was
  applied as a `count <= 0` guard with String.take semantics.

- **R-184 ℹ️ Round 2: "hoist `takeWholeCharacters` to a top-level
  internal extension so the `count <= 0` guard becomes testable."**
  Declined. The guard is deliberate armor for a branch unreachable
  through every caller — both public paths bound their counts first —
  and widening a private member extension to module visibility purely to
  execute dead-defense code inverts the smallest-surface preference for
  no live-behavior coverage. This differs from PR #165's BuiltFlags test
  (applied): there the extracted logic carries reachable, render-
  correctness invariants; here the three-line guard's contract is
  readable at a glance and the surrogate behavior it protects is already
  pinned by three tests through the public API.
