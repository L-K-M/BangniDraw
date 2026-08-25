# 0002 — minSdk 29 and lockstep toolchain with Meltorama

- **Status:** accepted
- **Date:** 2026-08-24

> Covers PLAN.md §2 and decision 2. This is the one document allowed to
> state version numbers in prose (PLAN.md §2); everywhere else, "see
> `gradle/libs.versions.toml`". When the catalog moves, update the table
> here in the same PR or leave a dated note in AGENTS.md.

## Context

Two things need pinning before the first PR: the platform floor and the
build toolchain.

**Platform floor.** Three features that PLAN.md's principles depend on
each have a minimum API:

| Need | Mechanism | Minimum |
| --- | --- | --- |
| "Permission-free" gallery (principle 5): insert into `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = Pictures/帮你Draw` and `IS_PENDING`, then rewrite our own item in place with `openOutputStream(uri, "wt")` | Scoped storage; ownership via `OWNER_PACKAGE_NAME` | API 29 |
| Low-latency stroke path (principle 2): `GLFrontBufferedRenderer` from `androidx.graphics:graphics-core` | Front-buffered `SurfaceControl` layer above the multi-buffered one | API 29 |
| `SurfaceControlCompat.Transaction` (`setBuffer`, `setFrameRate`) | SurfaceControl public API | API 29 |

Below 29, the gallery needs `WRITE_EXTERNAL_STORAGE` plus a runtime
prompt and a second code path (`MediaStore.Images.Media.insertImage` or
raw `File` writes into `DCIM`), and the front-buffered renderer is not
available at all — the low-latency path would need a `GLSurfaceView`
fallback with different latency characteristics that the UI would have to
explain. Meltorama chose minSdk 26 because it has neither need; 帮你Draw has
both. The devices that matter — every S Pen tablet from the Tab S6 Lite
and Tab S7 onward, every S22+ Ultra, the Z Fold line — all run Android 10
or later.

**Toolchain.** The family's rule is that its Android pair (Meltorama and
now 帮你Draw) share a toolchain so that CI, scripts, agent setup hooks and
fixes port across without translation. Meltorama's catalog is CI-proven
at these pins as of today:

| Component | Pin | Note |
| --- | --- | --- |
| Gradle wrapper | 9.7.0 | committed wrapper; `wrapper-validation` runs before any Gradle step |
| AGP | 9.3.1 | provides built-in Kotlin (K2) — **no `kotlin-android` plugin** |
| Kotlin | 2.4.10 | plugins applied: `kotlin-compose`, `kotlin-serialization` only |
| KSP | 2.3.11 | for Hilt |
| Hilt | 2.60.1 | |
| Compose BOM | 2026.06.01 | Material 3 |
| kotlinx-serialization | 1.11.0 | `project.json`, brush presets |
| kotlinx-coroutines | 1.11.0 | |
| activity-compose / core-ktx / lifecycle / navigation-compose | 1.13.0 / 1.19.0 / 2.11.0 / 2.9.8 | |
| JDK | 17 | |
| compileSdk | `compileSdkVersion("android-37.0")` (string form) | paired with `android.suppressUnsupportedCompileSdk=37` in `gradle.properties` |
| targetSdk | 37 | |

Drawing-specific additions, absent from Meltorama:

| Component | Pin | Note |
| --- | --- | --- |
| `androidx.graphics:graphics-core` | 1.0.4 | `GLFrontBufferedRenderer`, `SurfaceControlCompat` |
| `androidx.input:input-motionprediction` | 1.0.0 | `MotionEventPredictor` (its own minSdk is 23) |
| `com.scrtwpns:mixbox` | 2.0.0 | CPU-side Mixbox; ADR 0003 |

## Decision

**minSdk 29, targetSdk 37**, compileSdk `"android-37.0"` in string form
with `android.suppressUnsupportedCompileSdk=37` — the pair moves together
or not at all. The toolchain is pinned in `gradle/libs.versions.toml` at
the Meltorama values above and is kept in lockstep with Meltorama: a bump
lands in one repo, is CI-proven, and is then ported to the other as a
single "toolchain" PR. No `kotlin-android` plugin (AGP's built-in Kotlin
support); only `android-application`, `kotlin-compose`,
`kotlin-serialization`, `ksp`, and `hilt` are applied.

There is exactly **one** storage code path (`GalleryExporter` over
MediaStore with `IS_PENDING` and in-place `"wt"` rewrites) and exactly one
stroke path (front-buffered). No `Build.VERSION.SDK_INT` branches for
storage or rendering exist in the codebase, and lint's `NewApi` check is a
hard gate so none creep in.

## Consequences

- **Android 8 and 9 devices are excluded.** In practice that is the
  pre-2019 S Pen line — devices whose last OS update stopped at Android 9
  (to verify per model: Galaxy Tab S3 and Galaxy Note 8 are the likely
  examples) — and low-end tablets never updated past Oreo. Nobody paints
  with an S Pen on those in 2026 at a scale that justifies two storage
  paths and a degraded latency path.
- The manifest requests **no permissions at all**, which is enforceable
  (lint + a `ManifestTest` that asserts the `uses-permission` list is
  empty), not just promised.
- `RecoverableSecurityException` on API 29 when we have lost ownership of
  our gallery item (reinstall) is *not* prompted through — we insert a
  fresh item, the same as on 30+. One code path, one behavior.
- Toolchain lockstep means 帮你Draw inherits Meltorama's quirks verbatim:
  the compileSdk string form + suppress flag, no `kotlin-android`, JDK 17,
  the `.claude/setup-android.sh` session hook. AGENTS.md lists them under
  "don't fix these".
- A toolchain bump that breaks one sibling blocks the other; that is the
  point — one debugging session instead of two.
- **Revisit if:** the family's other Android app moves its toolchain and
  the port here fails for a drawing-specific reason (graphics-core or
  motionprediction incompatible with a new AGP/Kotlin); or a stable
  successor to `GLFrontBufferedRenderer` requires a higher floor, in which
  case minSdk rises with it — it never falls, because the storage path
  would have to fork.
