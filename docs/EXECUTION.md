# Build 帮你Draw from its plan, one PR at a time

> This file is the standing instruction for the agent that implements the
> roadmap. Point a session at it with the short prompt in the "How to
> invoke" section at the end. Keep it in step with `CLAUDE.md` (review
> policy) and `docs/plan/12-roadmap.md` (the steps).

You are an autonomous engineer working in the git repository `/work/GitHub/BangniDraw`
(GitHub: `L-K-M/BangniDraw`, default branch `main`). Your job is to implement the app
described in the plan, as a sequence of reviewed pull requests, until the roadmap is done.

## Read these first, in this order (do not skip)
1. `PLAN.md` — the constitution. Every decision in it is binding.
2. `docs/plan/12-roadmap.md` — the ordered steps, what each creates, and its acceptance tests.
3. `AGENTS.md` — build/test commands, toolchain quirks you must not "fix", conventions.
4. `CLAUDE.md` — how to handle review feedback (apply / decline with reasons / refute with evidence).
5. `CONTRIBUTING.md`, `CICD.md`, `REVIEW.md`.
6. The `docs/plan/NN-*.md` document(s) that the step you are about to build references.
   Read them fully before writing code for that step.

## Hard rules
- Never push to `main` directly. All work lands through a PR merged by you at steady state.
- Never merge a PR whose CI is red. Never hand-edit `versionCode`. Never create a `v*` tag by hand.
- Never add a permission to the manifest, a network dependency, or a signing secret.
- Never change `applicationId`. Never restate dependency versions outside `gradle/libs.versions.toml`.
- Follow the package layout in `PLAN.md` §3 and the names in `docs/plan/02-architecture.md`.
- Everything that decides something is pure Kotlin with no `android.*` imports and has a JVM
  unit test (`./gradlew testDebugUnitTest`). `./gradlew lintDebug` must be clean.
- All user-visible text goes in `res/values/strings.xml` AND `res/values-b+zh+Hans/strings.xml`.
- If the plan is silent on something, choose the simplest option consistent with `PLAN.md`
  and record the choice in the PR description. If the plan contradicts itself, follow `PLAN.md`
  and note the contradiction in `AGENTS.md` under a "Deviations" heading in the same PR.
- Stop and ask the user ONLY for product decisions that `PLAN.md` explicitly reserves for them
  or when you are blocked for more than three attempts on the same problem. Otherwise keep going.

## Environment
- Build/test with the commands in `AGENTS.md`. If the local machine cannot run Gradle (for
  example an arm64 Linux sandbox without a working `aapt2`), do not fight it: rely on GitHub
  Actions CI as the build oracle and fix forward until it is green.
- Use `gh` for everything GitHub (it is already authenticated).

## Step A — Plan the PRs for the next roadmap step
1. Find the first roadmap step in `docs/plan/12-roadmap.md` that is not yet landed on `main`
   (check `git log main` and the roadmap's status notes; step 1 is already landed).
2. Break that step into **realistic, reviewable PRs**. A PR is realistic when:
   - it builds and passes tests on its own (no "part 1 of 3 that doesn't compile"),
   - it changes one coherent area (roughly ≤ 1,500 lines of non-generated diff; split if larger),
   - it ships with the unit tests for every pure class it adds, and updates the docs it affects
     (`AGENTS.md` quirks, `docs/plan/12-roadmap.md` status, `REVIEW.md` declines).
   The roadmap already suggests seams (e.g. step 2 → 2a tiles/pool/compositor, 2b stroke path/input).
   Use them; split further if needed. Order the PRs so each one is useful on its own.
3. Write the PR list (title + one-line scope + acceptance check) to `docs/plan/12-roadmap.md`
   under the step's heading before starting, so the plan reflects what you are doing.

## Step B — Build one PR
1. `git checkout main && git pull --ff-only`, then `git checkout -b fable/<short-topic>`.
2. Implement the scope. Keep commits small with descriptive messages. Add tests as you go.
3. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` if the machine can; fix everything.
4. Push the branch and open a **non-draft** PR (the automated reviewer skips drafts):
   `gh pr create --title "<what it does>" --body "<scope, decisions made, how it was tested, acceptance checks from the roadmap>"`.
   Reference the roadmap step and the plan documents it implements.

## Step C — The review loop (repeat until steady state)
A **round** is: the current head commit of the PR has finished CI AND the automated review
(GLM, workflow `zai-code-review.yml`) has posted for that commit — or 20 minutes have passed
since the push with CI finished and no review appeared (then treat the round as "no feedback").

For each round:
1. Wait for CI: `gh pr checks <N> --watch`. If CI is red, fix it first (that is not review
   feedback; it does not count as a round).
2. Collect feedback for the current head commit:
   - `gh pr view <N> --comments`
   - `gh api repos/L-K-M/BangniDraw/pulls/<N>/comments` (inline review comments)
   - `gh api repos/L-K-M/BangniDraw/pulls/<N>/reviews`
   Only consider comments posted after your latest push.
3. Triage every finding into exactly one bucket, per `CLAUDE.md`:
   - **apply** — a real bug, a real improvement, or a missing test. Fix it.
   - **decline** — not worth doing or contrary to `PLAN.md`; write the reason in `REVIEW.md`
     (stable ID, one paragraph) in the same PR.
   - **refute** — factually wrong; verify against official docs / the code / a CI run before
     deciding, and only post a PR comment if the wrong claim would otherwise block a merge.
   Never apply a change just to satisfy the reviewer. Never flip-flop on something already
   declined with reasons unless there is genuinely new evidence.
   Comments from **human** users are always addressed and never subject to the cutoff below.
4. If you applied anything: commit, push, run tests/lint, and wait for CI on the new commit.
   This push starts the next round.
5. Score the round. Answer these in order; the first "yes" is the score. Every round lands in
   exactly one, and the questions are exhaustive by construction rather than by assertion —
   an earlier draft of this list defined six overlapping buckets and three of them turned out
   to leave real rounds unscoreable.

   a. **Did a usable review arrive?** If not — the action errored, timed out, was cancelled,
      posted nothing, or posted a report that is blank, truncated or error-shaped (a
      rate-limit stub, a finding cut off mid-sentence) — the round is **`no review`**. A
      malfunctioning reviewer that says nothing looks exactly like a clean bill of health, so
      it is never scored as one: this is not `empty`.

      Re-run it once — most failures are transient. If the re-run delivers a coherent review,
      score the round on **that** review, from (b) down, like any other. Only a round still
      unreviewed after the re-run is `no review`; such a round counts toward no rule and
      breaks any streak it interrupts.

      If the re-run also fails, **do not stop the pipeline**: a broken reviewer is not a
      reason to leave finished work unmerged. Keep the score `no review` and merge under
      *Unreviewed merges* below.

   b. **Did you apply a substantive finding** — one that changed behavior, closed a hole, or
      corrected a false claim?
      - Yes, and the push went green: **`useful feedback`**.
      - Yes, but CI went red and one follow-up fix commit could not save it, or the change had
        to be reverted because it broke behavior or contradicted `PLAN.md`:
        **`integration failed`**. Revert to the last green state before continuing.

   c. **Did the review raise a substantive finding you did not apply** — declined, refuted, or
      deferred as an out-of-scope follow-up? Then **`dismissed only`**, however many cosmetic
      fixes you applied alongside it. One applied typo must not launder a refused blocker into
      the faster streak.

   d. **Did the review raise any finding at all?** Restatements and self-answered "✅ fine"
      items are not findings.
      - No: **`empty`**.
      - Yes, and you applied at least one: **`nits only`** — every applied finding was
        cosmetic, or (b) would already have caught it.
      - Yes, and you applied none: **`dismissed only`**.

**Steady state is reached when ANY of these hold** (`CLAUDE.md` carries the same list; change
both or neither)
- **one** round was empty, **OR**
- **two consecutive** rounds were nits only — no blocker, nothing genuinely helpful, **OR**
- **two consecutive** rounds were dismissed only, **OR**
- feedback integration failed in two consecutive rounds, **OR**
- everything left is out of scope for this PR (pre-existing behavior, product decisions) —
  collect those as follow-up suggestions instead.

**A re-raise is a prompt to re-read, not a reason to stop.** There is deliberately no rule
here that fires when the reviewer repeats a finding you declined. When it does, go back to
the code and re-check the decline before answering; never treat the repetition itself as
evidence you were right the first time.

A genuinely stuck reviewer still terminates, through rule 3 rather than a rule of its own: if
it only re-raises declined items and you decline them again, those rounds score `dismissed
only`, and two running end the loop. That is the same two-round budget, without the failure
mode — PR #7 declined R-001 in rounds 2, 6 and 7 and applied it in round 8, and declined
R-005 in rounds 5, 7 and 9 and applied it in round 10. Both were path traversal through an
unvalidated layer id reaching a directory name. A stop rule firing on the first re-raise would
have merged at round 6 with both holes open.

Only rounds where a review actually ran count toward these. A *no review* round earns nothing
and breaks any streak it interrupts, so nits only → no review → nits only is **not** two
consecutive nits-only rounds.

**Unreviewed merges.** If a round scores *no review* and the re-run also fails, the reviewer
is unavailable, not silent, and waiting for it is a work stoppage. Merge anyway, on these
terms:
- CI must still be green. "Never merge red CI" is not what this rule relaxes.
- Re-read your own diff adversarially first — the reviewer was the outside check and it is
  gone, so you are the only one left. Budget real minutes for it, not a glance.
- Say plainly, in the PR scorecard and in the merge commit, that no review ran and why. An
  unreviewed merge is a fine thing to do and a bad thing to hide: the record is what lets
  someone come back and look at this diff on purpose.
- Do not let it become the habit. If three PRs in a row merge unreviewed, the reviewer is
  broken rather than flaky — say so to the user, who can decide whether to fix it before the
  next step.

The first rule is deliberately a hair trigger. A reviewer that raises nothing has run out of
things to say about this diff, and a further round costs a CI cycle and a review cycle to
re-confirm that. Do not wait for a second empty round to be polite to the process.

The third rule is deliberately *not* a hair trigger, for the opposite reason: you are grading
your own dismissals. The same agent wrote the code, decided the finding was wrong, and scored
the round — so one round of self-graded refusal is not evidence of anything, and ending the
loop on it would make the review optional whenever you disagreed with it. Two rounds is the
cheapest check on that available here. If you find yourself refuting a whole round, re-read
the findings against the code before you score it, not after.

Human comments are never subject to any of these cutoffs — address them however late they
arrive.

Keep a short running log in the PR description (edit it with `gh pr edit`): round number,
what was applied, what was declined and why, what failed. This is how the next person (or you,
after a restart) knows where the loop stands.

## Step D — Merge at steady state
1. Confirm CI is green on the head commit and every human comment is addressed.
2. Post a short scorecard as a PR comment: findings applied / declined / refuted / integration
   failures, and the acceptance checks you ran.
3. Merge with a merge commit titled like the rest of the history:
   `gh pr merge <N> --merge --subject "Merge PR #<N>: <PR title>" --delete-branch`
4. `git checkout main && git pull --ff-only`. Confirm the CI run on `main` is green
   (`gh run list --branch main --limit 1`). If it is red, fix it in a new PR immediately before
   anything else.
5. Update `docs/plan/12-roadmap.md`: mark the PR landed (commit hash, date). When all PRs of a
   step are landed, mark the step landed and note anything that deviated from the plan.

## Step E — Move on
Go back to Step A for the next PR of the current step, or the next step when the step is
complete. Continue until every step through v1.0 in `docs/plan/12-roadmap.md` is landed.
Do not start post-v1 backlog items unless the user asks.

## Housekeeping you do along the way
- Dependabot PRs: treat them like any other PR — CI green + the review loop — but never let
  them block roadmap work; handle them between your own PRs. Re-verify Mixbox's license if a
  Mixbox bump is ever proposed.
- When a release is due (the roadmap says so, or the user asks): `scripts/release.sh X.Y.Z --push`
  from a clean `main`. Nothing else creates tags.
- Whenever you learn a durable quirk (a flaky driver behavior, a lint rule, a Gradle footgun),
  add it to `AGENTS.md` in the same PR.

## Reporting
After each merge, print one paragraph: which PR merged, how many review rounds it took, why
steady state was declared — which rule fired, or that no review ran and the PR merged
unreviewed — what you declined and why, and what the next PR is. Report failures plainly with the output; never claim CI is green without having
seen the run result.

## How to invoke
Start a session in `/work/GitHub/BangniDraw` and give the agent this prompt:

> Read `docs/EXECUTION.md` in this repository and follow it exactly. It tells you how to
> implement the plan in `PLAN.md` as a sequence of reviewed pull requests, how to run the
> review loop, when a PR has reached steady state — that file has the conditions, and a
> summary of them, this one included, is never authoritative — when to merge, and how to move
> on to the next roadmap step. Begin with Step A.
