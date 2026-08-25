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
`docs/EXECUTION.md` carries the same list with the full round-scoring rules —
change both or neither.

- **One** round is **empty**: a coherent review arrived and raised nothing —
  no findings, or only restatements and self-answered "✅ fine" items. One is
  enough — don't spend a CI cycle re-confirming it,
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
  and could not get CI green with one follow-up fix, or had to revert it),
- everything remaining is out of scope for the PR (pre-existing behavior,
  product decisions) — collect those as follow-up suggestions instead.

**A re-raise is a prompt to re-read, not a reason to stop.** There is
deliberately no rule here for "the reviewer repeated something you declined".
Re-check the decline against the code instead; the repetition is never evidence
you were right. A stuck reviewer still terminates via the dismissed-only rule
above, on the same two-round budget. PR #7 declined R-001 in rounds 2, 6 and 7
and applied it in round 8; R-005 in rounds 5, 7 and 9, applied in round 10.
Both were path traversal through an unvalidated layer id. A rule firing on the
first re-raise would have merged at round 6 with both holes open.

A round where **no usable review arrived** — the action errored, timed out, was
cancelled, finished without posting its report, or posted a blank, truncated or
error-shaped one — is an infrastructure failure, not an empty round. A
malfunctioning reviewer that says nothing looks exactly like a clean bill of
health, so never score it as one. Re-run it once; a round the re-run fixed is
scored on the re-run's review and counts like any other, and only a round still
unreviewed after the re-run earns nothing and breaks a streak.

If the re-run also fails, **merge anyway — do not stop work because the
reviewer is down.** CI must still be green, you must re-read your own diff
adversarially first (you are the only check left), and the scorecard and merge
commit must both say that no review ran. An unreviewed merge is fine to do and
bad to hide. If three PRs in a row merge unreviewed, tell the user: that is a
broken reviewer, not a flaky one.

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
