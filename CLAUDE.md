## Pull request babysitting

When you push a branch and open a pull request in this repo (or the user points
you at one), subscribe to it with `subscribe_pr_activity` immediately — don't
wait to be asked. Also arm an hourly `send_later` self check-in (webhooks don't
deliver CI successes, new pushes, or merge-conflict transitions). Then handle
review feedback under this policy:

### Respond to every round on its merits
- Triage each comment into exactly one of: **apply** (real bug or improvement),
  **decline with recorded reasons** (commit message and chat), or **refute with
  evidence** (official docs, actual CI runs, the code itself) when a claim is
  factually wrong.
- Verify factual claims against primary sources before acting on them. Never
  apply a change just to appease a reviewer.
- Never flip-flop: if an earlier round declined something for stated reasons,
  don't apply it later unless genuinely new evidence appears. Keep a running
  list of what was declined and why.
- Post a PR comment only when an incorrect claim would otherwise mislead a
  merge decision (e.g. "this won't compile"); otherwise let commit messages and
  chat summaries carry the record.

### Declare steady-state and stop when any of these hold
`docs/EXECUTION.md` is the source of truth for this list and for the full
round-scoring rules; this is a summary. Change both in the same commit — and if
they ever disagree anyway, EXECUTION.md wins *and the drift is a bug to fix
before the next round is scored* — not a standing arrangement. A tiebreaker
alone would quietly make a stale file authoritative; "change both or neither"
alone is how two files drift and both look authoritative. You need both.

- **One** round is **empty**: a coherent review arrived and raised nothing —
  no findings, or only restatements and self-answered "✅ fine" items. One is
  enough — don't spend a CI cycle re-confirming it. A restatement repeats
  something already applied or resolved — check that against the code before
  calling a re-raise one, since "I already fixed that" is self-graded like a
  dismissal and buys the *fastest* exit; a partial fix is a live finding.
  **A re-raise of something you declined or deferred is never a restatement**: it is a live finding, and the round is a
  **dismissed-only** round only if you decline it *again* — useful feedback if
  you apply it,
  as PR #7 eventually did with both. Otherwise "that's just a restatement"
  becomes a one-round exit from any finding you don't like,
- **two consecutive** rounds are **nits only**: you applied something, every
  applied finding was cosmetic (wording, a comment, a rename, a test message),
  and the review raised no substantive claim you declined or deferred — a round
  that applies a typo while refusing a blocker is dismissed only, not this,
- **two consecutive** rounds are **dismissed only**: the review raised at least
  one finding, and no substantive one was applied — every substantive finding
  declined, refuted, or deferred — or nothing was applied at all. Two, not one:
  you are grading your own dismissals, so a single such round is not evidence
  of anything. Restatements and "✅ fine" items are not findings;
  a round of only those is empty, not this,
- feedback integration failed in two consecutive rounds (you applied something
  — of any weight, cosmetic included — and could not get CI green with one
  follow-up fix, or had to revert it),
- **two consecutive** rounds where everything remaining is out of scope for the
  PR (pre-existing behavior, product decisions) — collect those as follow-up
  suggestions instead. Two, like dismissals: "out of scope" is your judgment
  about the reviewer's finding, so one round of it is not evidence,
- a round found no usable review and its re-run failed too — merge unreviewed,
  per the paragraph below. Listed here because merging is otherwise gated on
  steady state, and a broken reviewer must not stall the pipeline,
- **fifteen** rounds have run. A backstop, not a target: every other rule here
  needs the reviewer to slow down, and one whose real, in-scope findings you keep
  applying and landing green satisfies none of them — neither stuck nor wrong —
  so without a cap that case has no exit at all. The scorecard and merge commit say the cap fired and what was still open.

**When the reviewer contradicts itself** — asks for a change, then for its
revert, on code you have not touched — re-check both positions against the code
once, then score the round on its merits and let the dismissed-only rule end it.
Not its own stop rule: that reading is yours, and a reviewer that genuinely
changed its mind on new evidence looks the same from outside.

**A re-raise is a prompt to re-read, not a reason to stop.** There is
deliberately no rule here for "the reviewer repeated something you declined".
Re-check the decline against the code instead; the repetition is never evidence
you were right. A stuck reviewer still terminates via the dismissed-only rule
above — a bound, not a guarantee. PR #7 declined R-001 in rounds 2, 6 and 7 and
applied it in round 8; R-005 in rounds 5, 7 and 9, applied in round 10. Both
were path traversal through an unvalidated layer id. A rule firing on the first
re-raise would have merged at round 6 with both holes open — and the
dismissed-only rule would have fired at round 7 had rounds 6 and 7 been
decline-only. They were not, so the streak never started. The fix survived
because the reviewer was still finding other real things, not because two
rounds is a safe budget.

A round where **no usable review arrived** — the action errored, timed out, was
cancelled, finished without posting its report, or posted a blank, truncated or
error-shaped one — is an infrastructure failure, not an empty round. A
malfunctioning reviewer that says nothing looks exactly like a clean bill of
health, so never score it as one. This reviewer's reports open with
"Actionable suggestions identified: N" and close with an HTML marker comment;
a report missing either end was cut off, whatever it appears to say. Re-run it
once; a round the re-run fixed is
scored on the re-run's review and counts like any other, and only a round still
unreviewed after the re-run earns nothing and *pauses* a streak rather than
resetting it — a round that carries no information must not erase the round
before it, or a flaky reviewer gets an indefinite pardon from the
dismissed-only rule. Otherwise "consecutive" is strict: any scored round of a
different kind resets the streak.

If the re-run also fails, **merge anyway — do not stop work because the
reviewer is down.** CI must still be green, you must re-read your own diff
adversarially first (you are the only check left), and the scorecard and merge
commit must both say that no review ran. An unreviewed merge is fine to do and
bad to hide, so tell the user in the report for that PR — the first one is what
they would most want to know about, and a scorecard they never open is not a
notification. Telling them is not stopping; keep going. If three PRs in a row
merge unreviewed, say plainly that the reviewer is broken rather than flaky, and
keep merging: they decide whether to fix it before the next step.

At steady-state: post a short scorecard in chat (what was real, what was
refuted, what's deferred), then merge the PR into `main` once its build checks
are green — the user tests `main` locally, so a merge-ready PR left open is a
PR they can't see. Use a merge commit titled `Merge PR #NN: …` like the rest of
the history. Then call `unsubscribe_pr_activity` and delete any pending self
check-in triggers for that PR. Ignore further automated review rounds after
that point.

**Exceptions:** comments from human reviewers are never subject to the
steady-state cutoff — always address them. And always unsubscribe when the PR
is merged or closed, or the user says stop.
