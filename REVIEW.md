# REVIEW.md — living review backlog

Findings from AI review rounds (GLM on PRs, periodic deep reviews) and
their dispositions. Stable IDs; nothing is deleted, only resolved or
declined with reasons. Point-in-time review snapshots archive under
`docs/reviews/`.

Legend: 🐞 bug · 🔧 improvement · ✨ idea · ⬜ open · 🟢 done · ⏸️ declined/deferred

## Open

_Nothing open._

## Resolved

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

## Declined, with reasons

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

- **R-014 ⏸️ `LayerId` should also reject Windows reserved device names and
  trailing dots/spaces** (round 9, info). Declined for v1. Project folders live
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

- **R-005 ⏸️ Type `HistoryEntry`'s id fields as `LayerId` rather than `String`**
  (PR #7, GLM round 5, info). The ergonomic argument is fair — `LayerId` is a
  value class, so it costs nothing at runtime — but
  `docs/plan/06-document-and-persistence.md` §5.2 opens with "**This section is
  normative for `HistoryEntry`** — names, fields and the on-disk encoding" and
  writes every one of those fields as `String`. `HistoryEntry` is the
  serialization-facing shape, like `LayerRecord`, which this PR also kept as
  the plan declares it; changing the field types would deviate from a normative
  declaration for a convenience the journal can get with a wrapper in step 3.
  Declined on the same reasoning as R-002. The name collision the same round
  raised (`HistoryEntry.LayerProps` vs the model's `LayerProps`) is declined
  for the identical reason and is now recorded in AGENTS.md as a convention
  rather than left as a surprise.

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
