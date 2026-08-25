# REVIEW.md — living review backlog

Findings from AI review rounds (GLM on PRs, periodic deep reviews) and
their dispositions. Stable IDs; nothing is deleted, only resolved or
declined with reasons. Point-in-time review snapshots archive under
`docs/reviews/`.

Legend: 🐞 bug · 🔧 improvement · ✨ idea · ⬜ open · 🟢 done · ⏸️ declined/deferred

## Open

_Nothing open._ Two items are **deferred** rather than declined, both needing
the `data/` loader that arrives with roadmap step 3, and both now in that step's
carry-in list in `docs/plan/12-roadmap.md`: R-020, the NaN-token decode test,
and R-029 — `ProjectStore.load` must degrade on a case-insensitive layer-id
collision rather than throwing. `LayerStack` refuses one at construction as of
round 15, which is the right answer for code building a stack; a document that
*arrives* with one has to open anyway, with the layer counted among the
unreadable.

## Resolved

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
