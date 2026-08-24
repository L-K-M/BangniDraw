# REVIEW.md — living review backlog

Findings from AI review rounds (GLM on PRs, periodic deep reviews) and
their dispositions. Stable IDs; nothing is deleted, only resolved or
declined with reasons. Point-in-time review snapshots archive under
`docs/reviews/`.

Legend: 🐞 bug · 🔧 improvement · ✨ idea · ⬜ open · 🟢 done · ⏸️ declined/deferred

## Open

- **R-001 🔧 Layer ids reach the filesystem unvalidated.** (PR #7, GLM round 2.)
  `LayerId` names `layers/<layerId>/` on disk, but nothing checks its shape, so
  a hand-crafted `project.json` with an id of `../../x` would be a path
  traversal once `TileStore` exists. Deferred to roadmap step 3, which is the
  PR that first turns an id into a path — see the decline note below for why
  the suggested fix was not taken as offered. **Step 3 must not land without
  it.**

## Resolved

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

- **R-001 ⏸️ Throwing on a malformed layer id in `LayerRecord.init`** (PR #7,
  GLM round 2). The *concern* is real and is tracked as open above; the
  *suggested fix* is not the right shape and is declined. Three reasons.
  (1) It contradicts the plan's stated posture for corrupt data:
  `06-document-and-persistence.md` §4 says "one bad tile must never fail an
  open" and §13 says an unreadable field degrades with a log line — a
  `require` in the deserialization constructor makes one bad id fail the whole
  document, which is exactly the failure mode the plan forbids. The right
  degrade is to drop the malformed layer and count it in the unreadable tally
  §4 already defines, and that is `ProjectStore.load` code, which does not
  exist yet. (2) There is no filesystem in this PR at all, so there is nothing
  to traverse; the mitigation has to live at the boundary that builds the path,
  not two layers above it. (3) The threat needs write access to app-private
  storage (`filesDir/projects/`) to plant the file, which on a non-rooted
  device means the attacker is already inside the sandbox. Revisit in step 3,
  where the fix is "validate the shape *and* never build a path by
  concatenating untrusted text", not a constructor throw.

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
