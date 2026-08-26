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
5. Score the round. Answer these in order; each question either names a score or passes you
   to the next, and (d) always names one. Every round lands in
   exactly one, and the questions are exhaustive by construction rather than by assertion.

   a. **Did a usable review arrive?** If not — the action errored, timed out, was cancelled,
      posted nothing, or posted a report that is blank, truncated or error-shaped (a
      rate-limit stub, a finding cut off mid-sentence) — the round is **`no review`**. A
      malfunctioning reviewer that says nothing looks exactly like a clean bill of health, so
      it is never scored as one: this is not `empty`.

      Re-run it once — most failures are transient, but wait out the window first if the
      failure was a rate-limit stub: an immediate retry is the one most likely to fail for a
      reason waiting would have fixed, and it spends the single re-run doing it.

      If the re-run delivers a coherent review, score the round on **that** review, from (b)
      down, like any other. Only a round still
      unreviewed after the re-run is `no review`; such a round counts toward no *streak* rule
      and pauses any streak it interrupts rather than resetting it (see the streak paragraph
      after the rules). It does satisfy rule 6 below, which exists for it.

      If the re-run also fails, **do not stop the pipeline**: a broken reviewer is not a
      reason to leave finished work unmerged. Keep the score `no review` and merge under
      *Unreviewed merges* below.

   b. **Did you apply a substantive finding the review raised** — one that changed behavior,
      closed a hole, or corrected a false claim? Only the review's findings score the round.
      Work driven by a human comment happens regardless (Step 3 puts those in their own lane,
      never subject to a cutoff) and does not make an otherwise-empty round look productive.
      - Yes, and the head ended green — the push was green, or it went red and **one**
        follow-up fix commit made it green: **`useful feedback`**. A rescued build is a
        normal round, not a failed one.
      - Yes, but the change had to be reverted because it broke behavior or contradicted
        `PLAN.md`: **`integration failed`**. The head is green again *because* of the revert,
        which is why this cannot sit under the bullet below — the round did not land the
        finding, so it is not `useful feedback` either.
      - Yes, and the head did not end green — one follow-up fix commit could not save it:
        **`integration failed`**. Revert to the last green state before continuing.

   c. **Did the review raise a substantive finding you did not apply** — declined, refuted, or
      deferred, whether to a later PR in the plan or as an out-of-scope follow-up? (Deferring
      to a later PR is the most common kind here, and it is still not applying the finding.)
      - And the head ended green: **`dismissed only`**, however many cosmetic fixes you
        applied alongside it. One applied typo must not launder a refused blocker into the
        faster streak.
      - And the head did not end green: **`integration failed`**, exactly as at (b) and (d),
        and revert to the last green state before continuing. The outcome decides, not the
        weight of the findings — so a refused blocker must not launder a red head into
        `dismissed only` either. Without this branch the loop could reach steady state under
        rule 3 with a broken head and no revert. But if you pushed nothing this round there
        is nothing to revert and nothing you broke: a red head is then the flaky-job or
        moved-base case below. Fix or re-run CI until the head is green, then score the round
        here as written: the score is *deferred* until the head is green, never undefined.
        (An earlier draft said "this score does not apply", which left that round in no bucket
        at all — the exhaustiveness this procedure claims is only worth having if no branch
        can opt out of it.)

   d. **Did the review raise any finding at all?** Restatements and self-answered "✅ fine"
      items are not findings.

      A **restatement** repeats something already applied or already resolved — and you must
      check that against the code before scoring any re-raise as one, exactly as you would
      re-check a decline. "I already fixed that" is self-graded in precisely the way a
      dismissal is, and it buys the *fastest* exit in the list rather than the slowest: a
      partial fix (the read path sanitised, the write path still joining the raw string) is a
      live finding, not a restatement. A re-raise of a finding you *declined or deferred* is
      **never** a restatement — it is a live finding
      you did not apply — at (c) if it was substantive, at (d) if it was a nit. This matters because "restatement" is the one
      word in this procedure that can quietly demote a real finding into the fastest exit in
      the list: call a re-raised blocker a restatement and the round scores `empty`, which
      merges after one round. You would be grading that call yourself, exactly as with a
      dismissal.
      - No: **`empty`**.
      - Yes, and you applied at least one, and the head ended green: **`nits only`**. Every
        applied finding was cosmetic — a substantive one would have been scored at (b) and
        never reached here.
      - Yes, and you applied at least one, but the head did not end green: **`integration
        failed`**, exactly as at (b). A cosmetic change that breaks the build past one
        follow-up fix is a failed integration, not a trivial round; the weight of the finding
        does not decide that, the outcome does.
      - Yes, and you applied none: **`dismissed only`**.

      The applied-none bullet and `empty` do not look at the head — no *review* finding was
      applied — but that does not mean nothing was pushed: human-comment work lands every
      round under Step 3 and never scores the round. A red head can also arrive from a flaky
      job or a moved base. **No score authorizes
      merging a red head.** If the loop would end here with CI red, fix or re-run CI first;
      the exit rules say when reviewing stops, not that the result is mergeable.

   Worked examples. Every edit to the questions above has broken at least one of these rows;
   three of them were found by review rather than by re-reading the prose. Walk the tree
   against this table before changing it.

   | The round | Score |
   | --- | --- |
   | review job errored, re-run fixed it, re-run found nothing | `empty` |
   | review job errored, re-run errored too | `no review` |
   | applied a real fix, push green | `useful feedback` |
   | applied a real fix, push red, one follow-up fix rescued it | `useful feedback` |
   | applied a real fix, push red, one follow-up fix could not save it | `integration failed` |
   | applied a typo, **declined a blocker** | `dismissed only` |
   | declined a blocker, applied nothing | `dismissed only` |
   | declined a blocker, applied a cosmetic fix, head left red | `integration failed` |
   | applied a real fix, then reverted it as contradicting `PLAN.md` | `integration failed` |
   | declined a blocker, pushed nothing, head red from a flake | score deferred until green |
   | deferred a blocker to a later PR, applied a nit, green | `dismissed only` |
   | applied only cosmetic fixes, green | `nits only` |
   | applied only a cosmetic fix, and it broke the build for good | `integration failed` |
   | review raised only nits, you declined them all | `dismissed only` |
   | review raised nothing, or only restatements and "✅ fine" | `empty` |

**The review loop ends when ANY of these hold.** Rules 1-5 are steady state; rules 6 and 7
are last-resort exits and are not — the scorecard must say which it was. (`CLAUDE.md` carries
the same list; change both or neither.)

**Rules 2, 3 and 5 fire on ONE round, changed from two on 2026-08-26 at the user's
direction to shorten the cycle.** This is a deliberate speed-for-thoroughness trade, not a
discovery that the old reasoning was wrong — that reasoning still stands and is worth stating
plainly, because whoever reads this next should know exactly what was given up:

- You grade your own dismissals. Two rounds meant a decline had to survive being re-raised
  once before it could end the loop; one round means a refusal you got wrong ends it
  immediately.
- Rounds N and N+1 are not independent. Across PRs #12-#14 a fix pushed in one round
  introduced a defect caught in the next on three occasions — the clearest being #13's round
  6, which caught a dispose-path regression that round 5's own fix had introduced. The round
  that catches that class is the one this change skips.
- Rule 2's cost is different in kind from the other two, and was going unstated. A nits-only
  round is evidence about the *reviewer's* coverage, not about your grading, so neither bullet
  above covers it: one such round now ends the loop on the strength of a single pass. A hole
  the reviewer simply had not reached yet — PR #7's R-005, first raised in round 5 — is
  exactly what a second, deeper pass was there to catch, and it is the one case the security
  obligation cannot backstop, because an unraised finding cannot be escalated.

Two things deliberately did **not** change, and they are what keeps the trade tolerable:
rule 4 still needs two rounds, because one failed integration is as likely to be a fluke as a
pattern and shortening it would not speed up the healthy path at all; and the
rule-independent security obligation below still sends any unapplied security-relevant
finding to the user before the merge. That second one is what makes the PR #7 case discussed
further down — R-001 raised in round 2 and re-raised in rounds 6 and 7 before it was applied
— *partly* survivable under a one-round exit, and the qualifier is the honest part. R-001 was
on the table from round 2, so a round-2 exit still puts its decline in front of a person.
R-005 was not raised until round 5, and the obligation below covers only a finding that "was
raised and you did not apply it" — no obligation can catch what no round ever surfaced, so a
round-2 exit merges R-005's traversal with no human touchpoint at all. What one round really
gives up is the later rounds in which the reviewer had not yet reached the second hole, and
nothing downstream substitutes for them.
1. **one** round was empty, **OR**
2. **one** round was nits only — no blocker, nothing genuinely helpful, **OR**
3. **one** round was dismissed only, **OR**
4. feedback integration failed in two consecutive rounds, **OR**
5. **one** round scored `dismissed only` in which every declined or deferred
   finding was out of scope for this PR (pre-existing behavior, product decisions) — collect
   those as follow-up suggestions instead. Strictly a special case of rule 3, since (c) scores
   an out-of-scope deferral `dismissed only` and nothing else: it is kept because what you owe
   the next PR differs (a follow-up list, not a decline record), not because it fires where
   rule 3 would not, **OR**
6. a round scored `no review` and its re-run failed too — merge under *Unreviewed merges*
   below. A successful re-run is not a new round: step (a) scores the same round on the
   re-run's review. A round that stays `no review` still consumes a round number, including
   toward rule 7 — otherwise a reviewer that fails forever is also a loop that runs forever,
   which is the one thing rule 7 exists to stop. Listed here because merging is otherwise
   gated on the loop ending, and without this rule an agent reading that gate literally would
   stall on a broken reviewer: the one outcome the section it points at exists to forbid,
   **OR**
7. **fifteen** rounds have run — scored rounds, where a round and its re-run count as one,
   and a round still `no review` after its re-run counts too (see rule 6). A backstop, not a
   target. Rules 1-5 all require something in the loop to have gone wrong — the reviewer to
   run dry, or your integration of its findings to fail, or the findings to fall outside the
   PR. A reviewer whose real,
   in-scope findings you keep applying and landing green satisfies none of the five — it is
   neither stuck nor wrong — and that case has no exit at all without a cap. PR #7 took 12
   rounds and PR #8 took 11, so 15 is not a number you should reach often; if you do, the
   scorecard and the merge commit both say the cap fired and what was still outstanding.

**When the reviewer contradicts itself** — demands a change, then demands its revert, on code
you have not touched in between — re-check *both* positions against the code once. Then score
the round on its merits and let rule 3 end it if the dismissals keep coming. Deliberately not
its own stop rule: "it contradicted itself" is your reading of two reviews, and a reviewer
that has genuinely changed its mind on new evidence looks identical from the outside.

**A re-raise is a prompt to re-read, not a reason to stop.** There is deliberately no rule
here that fires when the reviewer repeats a finding you declined. When it does, go back to
the code and re-check the decline before answering; never treat the repetition itself as
evidence you were right the first time.

A genuinely stuck reviewer still terminates, through rule 3 rather than a rule of its own: if
it only re-raises declined items and you decline them again, that round scores `dismissed
only` and ends the loop.

That is a bound, not a guarantee, and PR #7 shows what the one-round threshold costs. It
declined R-001 in rounds 2, 6 and 7 and applied it in round 8; it declined R-005 in rounds 5,
7 and 9 and applied it in round 10. Both were path traversal through an unvalidated layer id
reaching a directory name. Under the two-round rule the streak never started, because rounds
6 and 7 each *applied* something real (a `flatten()` guard, a layer-cap resize) and so scored
`useful feedback`. **Under the one-round rule this PR would have merged at round 2**, with
both holes open — the first decline of R-001 would have ended it.

What saves half of that case is not the stop rule but the obligation below: path traversal is
security-relevant, so a declined R-001 reaches the user for a decision before any merge,
whichever rule fired — and it has to survive a mislabel, because the same agent whose wrong
decline created the risk is the one scoring the round. The obligation therefore keys on the
**finding's substance, never on the round's score**: a security-relevant decline filed as
"pre-existing behavior" under rule 5 escalates exactly as one filed as a decline under rule 3.
Without that tie-breaker the cheapest way out of a security finding is to relabel it as a
scope call, and rule 5 would end the loop in one round with a follow-up list instead of a
decision. (R-005 is not saved by any of this, for the reason given above: it was never raised
before the exit.) The obligation is now load-bearing rather than a backstop, and it is the reason
this section still spells the PR #7 history out instead of trimming it. Read a
`dismissed only` round as "I have stopped learning from this reviewer", and check that it is
actually true before you act on it — under one round there is no second chance to notice you
were wrong.

**One obligation is rule-independent.** If a security-relevant finding — path traversal,
injection, authorization, secret handling, data loss — was raised and you did not apply it,
the user sees it and decides, before the merge, whatever rule ended the loop. Scoping this to
rule 3 was the previous draft's mistake: the same finding also reaches a merge through rule 4
(a round that declines a blocker and leaves the head red scores `integration failed`), through
rule 5 (a pre-existing hole is "pre-existing behavior", so it is out of scope *and*
security-relevant), and through rule 7, which only requires the scorecard to *record* what was
outstanding. Whether a declined hole reached a person should not depend on which exit happened
to fire. R-001 and R-005 were both path traversal through an unvalidated layer id, both
declined three times by the same agent that scored the rounds, and both were right.

Only rounds where a review actually ran count toward the **streak** rules (2, 3, 4, 5) — never
toward rule 6, which fires precisely because no review ran. A *no review* round earns nothing
and **pauses** any streak it interrupts rather than resetting it: a round that carries no
information should not erase the information in the round before it, and resetting would hand
a flaky reviewer an indefinite pardon from rule 3 — the rule that exists precisely because you
grade your own dismissals. Otherwise "consecutive" is strict: any *scored* round of a
different kind resets the streak.

That pause only ever matters when the loop continues past the `no review` round, and rule 6
fires on exactly the condition that produces one. So read rule 6 as the *end of the line*, not
as a reflex: it applies when the reviewer is the only thing left to wait for. If you still
have work in hand — a human comment to address, a fix you were mid-way through — do that, push
it, and take the next round on its merits; the failed round keeps its `no review` score and
pauses whatever streak it interrupted. Merge unreviewed when there is nothing left but the
review.

**Unreviewed merges.** If a round scores *no review* and the re-run also fails, the reviewer
is unavailable, not silent, and waiting for it is a work stoppage. Merge anyway, on these
terms:
- CI must still be green. "Never merge red CI" is not what this rule relaxes.
- Re-read your own diff adversarially first — the reviewer was the outside check and it is
  gone, so you are the only one left. Budget real minutes for it, not a glance.
- Say plainly, in the PR scorecard and in the merge commit, that no review ran and why. An
  unreviewed merge is a fine thing to do and a bad thing to hide: the record is what lets
  someone come back and look at this diff on purpose.
- Tell the user in the report for that PR, not just in the commit — the first unreviewed
  merge is the one they would most want to know about, and a scorecard they never open is not
  a notification. Telling them is not stopping: keep going.
- Before concluding the reviewer is broken, check the shape of its output. Reports that look
  complete but no longer open with the expected line ("Actionable suggestions identified: N")
  or end with a structurally closed template tail mean its *template* changed and the matcher
  needs updating — a one-line fix. Raw Markdown also carries an HTML marker, but GitHub MCP may
  strip comments, so never require that marker through an MCP client. Calling that a
  broken reviewer sends the user looking in the wrong place, and this integrity check is the
  only thing separating a silent malfunction from a clean bill of health.
- Otherwise, if three PRs in a row merge unreviewed, the reviewer is broken rather than
  flaky. Say so plainly, and keep merging — they can decide whether to fix it first.

The empty-round rule (1) is deliberately a hair trigger. A reviewer that raises nothing has run
out of
things to say about this diff, and a further round costs a CI cycle and a review cycle to
re-confirm that. Do not wait for a second empty round to be polite to the process.

The dismissed-only rule (3) is deliberately *not* a hair trigger, for the opposite reason: you are
grading
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
the loop ended — which rule fired, and whether that was steady state, a cap, or an
unreviewed merge — what you declined and why, and what the next PR is. Report failures plainly
with the output; never claim CI is green without having seen the run result.

## How to invoke
Start a session in `/work/GitHub/BangniDraw` and give the agent this prompt:

> Read `docs/EXECUTION.md` in this repository and follow it exactly. It tells you how to
> implement the plan in `PLAN.md` as a sequence of reviewed pull requests, how to run the
> review loop, when a PR has reached steady state (the conditions are defined in that file;
> this prompt deliberately does not restate them), when to merge, and how to move on to the
> next roadmap step. Begin with Step A.
