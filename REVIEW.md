# REVIEW.md — living review backlog

Findings from AI review rounds (GLM on PRs, periodic deep reviews) and
their dispositions. Stable IDs; nothing is deleted, only resolved or
declined with reasons. Point-in-time review snapshots archive under
`docs/reviews/`.

Legend: 🐞 bug · 🔧 improvement · ✨ idea · ⬜ open · 🟢 done · ⏸️ declined/deferred

## Open

- **R-003 🔧 `Composite.tile` skips a tile the model lists.** (PR #7, GLM round 3.)
  Raised as inconsistent leniency — `tile()` raises on a wrong-sized tile but
  silently treats a `null` read as transparent. Resolved by *documenting* the
  contract rather than by raising: a `null` is legal and expected (an
  unreadable tile file per `06-document-and-persistence.md` §4, a readback not
  yet landed), and `tile()` is the flatten and export path, so a painting with
  one bad tile must still open. Raising would be R-001's mistake again. The
  `TileReader` and `Composite.tile` KDocs now say which is which. 🟢

- **R-001 🔧 Layer ids reach the filesystem unvalidated.** (PR #7, GLM round 2.)
  `LayerId` names `layers/<layerId>/` on disk, but nothing checks its shape, so
  a hand-crafted `project.json` with an id of `../../x` would be a path
  traversal once `TileStore` exists. Deferred to roadmap step 3, which is the
  PR that first turns an id into a path — see the decline note below for why
  the suggested fix was not taken as offered. **Step 3 must not land without
  it.**

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
