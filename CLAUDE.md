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
- **One** round is **empty**: a completed review arrived and raised nothing —
  no findings, or only restatements and self-answered "✅ fine" items. One is
  enough — don't spend a CI cycle re-confirming it,
- **two consecutive** rounds are **nits only**: everything applied was cosmetic
  (wording, a comment, a rename, a test message), no blocker and nothing you
  would have wanted to know,
- **two consecutive** rounds are **dismissed only**: the review raised real
  claims and you declined or refuted every one, so applied nothing. Two, not
  one — you are grading your own dismissals, so a single such round is not
  evidence of anything. Restatements and "✅ fine" items are not real claims;
  a round of only those is empty, not this,
- the reviewer re-raises items already declined with reasons, or contradicts
  its own earlier feedback,
- everything remaining is out of scope for the PR (pre-existing behavior,
  product decisions) — collect those as follow-up suggestions instead.

A round where the review **never ran** — the action errored, timed out, was
cancelled, or finished without posting its report — is an infrastructure
failure, not an empty round. Re-run it once; if the re-run also fails, stop and
tell the user rather than merging. Such a round counts toward none of the rules
above and breaks any streak it interrupts. Never merge on the strength of a
review that did not happen.

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
