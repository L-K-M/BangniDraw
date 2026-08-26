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

### Declare steady-state: the stop rules, and reviewer failure
`docs/EXECUTION.md` is the source of truth for this list and for the full
round-scoring rules; this is a summary. Change both in the same commit — and if
they ever disagree anyway, EXECUTION.md wins *and the drift is a bug to fix
before the next round is scored* — not a standing arrangement.

Kept deliberately short. This file is auto-loaded and EXECUTION.md is not, so
the rules have to be *here* in some form; but every detail duplicated here is a
detail that can drift, and four separate review rounds caught exactly that. So
this lists what ends the loop, plus the streak semantics those rules cannot be
applied without — the round scores, the rest of the scoring procedure, and the
reasoning all live in EXECUTION.md.

- **One** round is **empty**: a coherent review arrived and raised nothing —
  no findings, or only restatements and self-answered "✅ fine" items. One is
  enough — don't spend a CI cycle re-confirming it. A restatement repeats
  something already applied or resolved — check that against the code before
  calling a re-raise one, since "I already fixed that" is self-graded like a
  dismissal and buys the *fastest* exit; a partial fix is a live finding.
  **A re-raise of something you declined, refuted or deferred is never a restatement**:
  it is a live finding, and the round is a **dismissed-only** round only if you
  decline it *again* — useful feedback if you apply it, as PR #7 eventually did
  with both. Otherwise "that's just a restatement" becomes a one-round exit
  from any finding you don't like,
- **one** round is **nits only**: you applied something and every applied
  finding was cosmetic (wording, a comment, a rename, a test message). **Only
  what you accepted scores the round** — a finding you declined, refuted or
  deferred goes in `REVIEW.md` under its own `R-NNN` id, and does not change the
  score,
- **one** round is **dismissed only** (you applied nothing the review raised),
- feedback integration failed in two consecutive rounds (you applied something
  — of any weight, cosmetic included — and could not get CI green with one
  follow-up fix, or had to revert it),
- **one** round where everything remaining is out of scope for the PR
  (pre-existing behavior, product decisions) — collect those as follow-up
  suggestions instead,
- a round found no usable review and its re-run failed too — merge unreviewed,
  per the paragraph below. Listed here because merging is otherwise gated on
  steady state, and a broken reviewer must not stall the pipeline,
- **fifteen** rounds have run. A backstop, not a target: every other rule here
  needs the reviewer to slow down, and one whose real, in-scope findings you keep
  applying and landing green satisfies none of them — neither stuck nor wrong —
  so without a cap that case has no exit at all. Counts scored rounds: a round
  and its re-run are one. The scorecard and merge commit say the cap fired and
  what was still open.

**These thresholds were two consecutive rounds until 2026-08-26**, when the user
asked for a shorter cycle: merge after one round without blocking feedback.
EXECUTION.md records what that trades away — you grade your own dismissals, and
across #12-#14 a fix pushed in one round introduced a defect caught in the next
three times over. Rule 4 (integration failure) deliberately still needs two.

The security escalation below is now **load-bearing rather than a backstop**:
PR #7 declined a path-traversal finding in round 2 and only applied it in round
8, so under a one-round exit that PR merges at round 2 and the escalation is the
only thing that puts the hole in front of a person. Do not treat it as
paperwork. And note what it does *not* save: PR #7's second traversal was not
raised until round 5, so a round-2 exit merges it unfound — an unraised finding
cannot be escalated, and no rule here pretends otherwise.

**Declining is recorded, not scored — but three things still follow from it.**
It goes in `REVIEW.md` under an `R-NNN` id with its reason (and its evidence, if
you refuted it), which is what a later round reads before re-applying something
an earlier one declined — the score no longer carries that signal, so the record
has to be written rather than inferred. Re-read the decline against the code
*before* you make it, since under one round there may be no second round in
which to reconsider. And an out-of-scope deferral still owes the follow-up list
even when the round scores nits only. All three key on the finding, never on the
round's score — the same correction the escalation below needed.

**Whatever rule ends the loop**, a security-relevant finding you did not apply
— path traversal, injection, authz, secrets, data loss — goes to the user for a
decision before the merge. Not scoped to one rule: the same finding reaches a
merge through the integration-failure rule, the out-of-scope rule and the cap,
and whether a declined hole reaches a person must not depend on which exit
happened to fire. It keys on the **finding's substance, never on the round's
score**, so filing one as "pre-existing behavior" does not exempt it — otherwise
the cheapest way out of a security finding is to relabel it a scope call.

An unreviewed round **pauses** a streak rather than resetting it. Since the
one-round change only the integration-failure bullet still counts consecutive
rounds, so it is the only one that depends on this; the semantics are in the
infrastructure paragraph below. Kept because that rule is the one a flaky
reviewer could otherwise pardon indefinitely.

**When the reviewer contradicts itself** — asks for a change, then for its
revert, on code you have not touched — re-check both positions against the code
once, then score the round on its merits and let the dismissed-only rule end it.
Not its own stop rule: that reading is yours, and a reviewer that genuinely
changed its mind on new evidence looks the same from outside.

**A re-raise is a prompt to re-read, not a reason to stop.** There is
deliberately no rule here for "the reviewer repeated something you declined".
Re-check the decline against the code instead; the repetition is never evidence
you were right. This matters more under the one-round threshold, not less: a
decline now ends the loop the first time you make it, so the re-read has to
happen *before* you decline, because there may be no second round in which to
reconsider. PR #7 declined R-001 three times before applying it, and R-005
three times; both were path traversal through an unvalidated layer id, and both
would have merged open. The round-by-round account is in EXECUTION.md.

A round where **no usable review arrived** — the action errored, timed out, was
cancelled, finished without posting its report, or posted a blank, truncated or
error-shaped one — is an infrastructure failure, not an empty round. A
malfunctioning reviewer that says nothing looks exactly like a clean bill of
health, so never score it as one. This reviewer's reports open with
"Actionable suggestions identified: N" and end with a structurally closed
template tail. Raw Markdown also carries an HTML marker, but GitHub MCP may
strip comments, so the marker is corroboration rather than a requirement (if
the format changes, this line, EXECUTION.md and the matcher move in one commit —
otherwise the pipeline quietly converts to unreviewed merges). A report missing
the opening line or cut off mid-structure was truncated. Re-run it
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
merge unreviewed, check one thing before blaming the reviewer: if the reports
look complete but no longer carry the expected opening line or a structurally closed tail,
its *template* changed and the matcher needs updating — a one-line fix, not a
broken pipeline. Otherwise say plainly that the reviewer is broken rather than
flaky, and keep merging: they decide whether to fix it before the next step.

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
