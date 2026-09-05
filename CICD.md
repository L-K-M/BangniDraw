# CI/CD

Every workflow carries the family contract: least-privilege `permissions:`,
an explicit `concurrency:` group, `timeout-minutes:` on every job, and
`gradle/actions/wrapper-validation` before anything executes the wrapper
(supply-chain gate against a tampered `gradle-wrapper.jar`).

## Workflows

### ci.yml — CI
- **Triggers:** push to `main`, every PR, manual dispatch.
- **Permissions:** `contents: read`.
- **Concurrency:** `ci-${{ github.ref }}`; cancels superseded **PR** runs
  only — never an in-progress `main` run, because a permanently "cancelled"
  main commit masks breakage and breaks bisection.
- **Job `android`** (45 min cap): checkout → wrapper-validation → Temurin
  JDK 17 → setup-gradle → `./gradlew testDebugUnitTest lintDebug
  assembleDebug` → upload `bangnidraw-debug-<sha>` APK artifact (14-day
  retention, `if-no-files-found: error`).
- No Android SDK install step: the ubuntu runner image ships the SDK, and
  AGP resolves `compileSdk android-37.0` against it (paired with
  `android.suppressUnsupportedCompileSdk` in `gradle.properties`). If the
  runner image ever drops the SDK, add `android-actions/setup-android`.

### release.yml — Release
- **Trigger:** pushed `v*` tag. **Permissions:** `contents: read` by default;
  only the publish job receives `contents: write`.
- **Concurrency:** no cancellation — a half-cancelled publish is worse than
  a slow one.
- Two-job trust split: a read-only build job checks out without persisted
  credentials, validates the wrapper, enforces the tag↔versionName gate,
  re-proves `testDebugUnitTest lintDebug`, runs `assembleRelease`, and
  uploads `dist/bangnidraw-vX.Y.Z.apk` + `.sha256`. A separate write-capable
  publish job downloads only those artifacts and creates the GitHub
  Release, titled `帮你Draw vX.Y.Z`, with generated notes. Every action in
  this privileged workflow is pinned to a verified immutable commit.
  `vX.Y.Z-rc.1`-style tags auto-mark as pre-release.
- **Signing:** both build types use the checked-in `app/debug.keystore`
  (see `docs/decisions/0005`). No signing secrets exist. Sideload-only by
  design; a future switch to a real key breaks upgrades for every
  installed user and must be treated as a product decision.

### zai-code-review.yml — GLM PR Review
- **Trigger:** `pull_request_target` (opened, reopened, synchronize,
  ready_for_review) — secrets are available to the job, hence the guards.
- **Guards:** runs only for non-draft PRs whose head repo IS this repo —
  fork PRs never see the secret or the write-capable token. The action is
  pinned to an immutable commit (`7d0ce7b` = v0.0.9 of
  `L-K-M/zai-code-review`); verify SHA↔tag before bumping:
  `git ls-remote https://github.com/L-K-M/zai-code-review refs/tags/v0.0.9`.
- **Concurrency:** keyed on the PR number (for `pull_request_target`,
  `github.ref` is the base branch and would collide across PRs); superseded
  reviews of an outdated diff are cancelled.
- **Time budget:** `timeout-minutes: 180`, sized to the work rather than to a
  round number — the action chunks the diff and spends ~4.5 min of
  high-effort reasoning per chunk, so a large PR runs well past an hour.
  It is not a knob to trim: a job killed by this timeout posts nothing, and
  a reviewer that says nothing looks exactly like one that found nothing
  (CLAUDE.md's infrastructure-failure rule). PR #193 hit that four times at
  90 minutes, discarding 19 chunks of real findings each time. Measured over
  that run, a chunk averages 4.52 min including the retry an
  output-token-limited one triggers — so 90 bought about 20 chunks, which is
  exactly where it kept dying. 180 divides to 39.8, so treat **38** as the
  ceiling rather than 40: 40 chunks is 180.8 min of chunk time, which busts
  the budget before the runner has checked anything out, and the mean hides a
  wide spread (1.5–6.3 min per chunk over that run), so the two chunks of
  margin are worth about one slow one. A PR past 38 needs a bigger budget
  *before* the run, not another silent timeout after it. Raise the number when
  that happens — do not remove it, since the family contract wants every job
  bounded.
- **Raising that budget does not rescue a run already in flight — or a re-run
  of it.** GitHub resolves a run's workflow file once, when the run is
  created, and "Re-run jobs" replays that stored definition rather than
  re-reading the ref. Measured: #193's review was re-run at 06:06 UTC on
  2026-09-05, after the 180 above had already merged to `main`, and the job
  was cancelled at 07:36:29 — 89 min 58 s, the old cap to the second. For
  `pull_request_target` the workflow comes from the base branch, which is
  exactly the case where the fix is sitting on `main` and looks like it must
  apply. It does not. Get a *fresh* run instead: push to the head branch
  (`synchronize`) or close and reopen the PR (`reopened`) — both are in this
  workflow's `types`.
- **Graceful degradation:** if the `ZAI_API_KEY` secret is absent the job
  logs a skip and stays green.
- **Trust boundary:** the guard is same-repo, not admin-only — anyone with
  push access effectively hands `ZAI_API_KEY` and a write token to the
  pinned action. That is why the commit pin matters.

## Secrets

| Secret | Used by | Purpose |
| ------ | ------- | ------- |
| `ZAI_API_KEY` | zai-code-review.yml | Z.ai API key for GLM reviews. Set with `gh secret set ZAI_API_KEY --repo L-K-M/BangniDraw`. Absent ⇒ reviews skip, everything else unaffected. |

No release-signing secrets exist, deliberately (decision 0005).

## Dependabot

Weekly `github-actions` and `gradle` update PRs. Gradle updates are grouped
(`androidx`, `kotlin`+`ksp`) so toolchain-coupled bumps land as one PR.
Mixbox has had one release since 2022 and is expected to stay put; if
Dependabot ever proposes a Mixbox bump, re-verify the license before
merging.

## Troubleshooting

| Symptom | Likely cause / fix |
| ------- | ------------------ |
| `wrapper-validation` fails | `gradle-wrapper.jar` doesn't match an official checksum — restore it from a trusted clone; do not "update" it to make CI pass. |
| CI can't find compileSdk / platform | Runner image changed. Add `android-actions/setup-android` to the job, or bump the pinned platform. |
| Release job fails at the version gate | Tag was created by hand or on the wrong commit. Delete the tag and re-cut with `scripts/release.sh X.Y.Z --push`. |
| "works in debug, breaks in release" | Missing R8 keep rule — `app/proguard-rules.pro`, see AGENTS.md. |
| Review still dies at the old timeout after raising it | The run is a re-run; it replays the workflow file stored at run creation. Push to the head branch or reopen the PR to get a fresh run. |
| Review workflow skipped on a PR | Draft PR, fork PR (by design), or `ZAI_API_KEY` unset. |
| Local `assembleDebug` dies in `aapt2` on an arm64 Linux box | The SDK's aapt2 is x86_64-only. Build on CI, a Mac, or an x86_64 machine. |
