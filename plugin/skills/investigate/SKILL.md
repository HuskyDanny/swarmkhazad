---
name: investigate
description: Use on a swarmkhazad investigation task — the task whose deliverable is a Root-Cause Analysis on a Linear ticket rather than a PR. Carries a labeled ticket from symptom to a confirmed root cause via a hypothesis tree, then comments the RCA on the ticket and sets the Evidence label. Fires at the start of the `investigate` role, or on "investigate this", "why did X happen", "find the root cause".
---

# investigate — symptom → hypothesis tree → confirmed root cause → RCA on the ticket

You are diagnosing, not fixing. This task opens no PR and lands no code. Your deliverable is **one
comment on the Linear ticket** plus **one label**, and the ticket is the only surface anything
downstream reads.

That single fact drives the whole protocol: an insight you found but did not write into the comment
is lost, and a root cause you assert without evidence becomes someone else's wasted PR.

## What you write, and where

Three artifacts, in this order. Nothing else you produce leaves this task.

| artifact | where | who reads it |
|---|---|---|
| the RCA | one comment on the Linear ticket | Allen, and the fix lane later |
| the `Evidence` label | the ticket, `Evidence: found` or `Evidence: none` | the loop session |
| `repro.md` | the task folder, next to goal.md | the `run` role, which executes it |

`goal.md` is the ticket's own text, fetched verbatim. Its Hints section carries the ticket URL.

## Why a hypothesis TREE, not a hunch

The failure mode of an autonomous investigation is *anchoring* — latching onto the first plausible
story, gathering only confirming evidence, and reporting it with confidence. The countermeasure is
structural: commit to N competing hypotheses BEFORE gathering evidence, then spend your effort trying
to KILL them. A hypothesis you never tried to refute is not evidence; it is a preference.

So: breadth first (enumerate), then depth on the survivors (refute), and only then conclude. A tree
where every branch but one is explicitly REBUTTED is a much stronger artifact than a single
CONFIRMED branch, because the reader can see what you ruled out and why.

## You do not hold the telemetry, and that is the design

`logfire`, `datadog` and `argocd` are DENIED to you and granted to the
`swarmkhazad:investigation-hypothesis-tester` subagent. This is deliberate and it is stronger than it looks: you
cannot go and fetch confirming evidence for the story you already like. Evidence from a telemetry
plane reaches your tree only through a fresh context that was instructed to refute.

So dispatching is not optional decoration on this protocol — for any hypothesis whose discriminating
observation lives in telemetry, dispatching is the ONLY way to test it.

## Protocol

1. **State the symptom precisely.** What was observed, where, when, and how it differs from expected.
   A vague symptom ("the job hung") makes every hypothesis unfalsifiable — sharpen it to something
   with an observable ("the row stayed `pending` for 2h with no `finished_at` and no error").

2. **Enumerate 3–5 COMPETING hypotheses** before touching evidence. They must be mutually
   distinguishable — if two would produce identical observations, they are one hypothesis. Include at
   least one that is *not* a code defect (a config/env drift, an upstream provider fault, a stale
   deploy, an operator action), because those are the ones a code-first reading never reaches.

3. **For each, name the evidence that would REFUTE it** — the discriminating observation, decided
   before you look. This is what keeps step 4 honest.

4. **Gather evidence, read-only.** Use the tool that answers the question directly:
   - **Check whether a human already root-caused it — FIRST, before deriving anything.** Read the
     ticket's own comment history (`mcp__linear-server__list_comments`), the linked PR thread, any
     sibling ticket. A responder often wrote the cause down during the outage. Measured in khazad:
     one run found the decisive record on its 26th tool call, having spent the first 25 rediscovering
     the symptom. Finding it EARLY converts the run from discovery into verification — which is where
     a bounded run's budget is best spent. It does not end the investigation: a record is a report,
     so step 5's observed-vs-read discipline still applies in full.
   - `mcp__codegraph__codegraph_explore` — structure: what calls what, what a change would break,
     where a symbol lives. Reach for this BEFORE grep/Read (grep is for literal text or a file
     already open).
   - `git log`/`diff`/`show`/`blame` — when did this behavior change, and in which commit.
   - **An UNCLONED repo, when the trail leads there.** `gh repo clone MithraAI/<name> <task>/tmp/<name> -- --depth=1`;
     `gh search code --owner MithraAI '<symbol>'` finds a symbol's owner. Your worktree is a starting
     point, not a boundary. **A missing layer is a repo to CLONE, never a behaviour to infer** — a
     cross-repo contract is where two reasonable changes stop agreeing, invisible from either side.
     Clone under the task's `tmp/`, never into your worktree.
   - **The telemetry planes, via the subagent.** They cover DIFFERENT planes, so say which plane the
     symptom lives on when you dispatch: Logfire holds application runs (spans, turns, traces),
     Datadog holds everything around them (product RUM, APM, AWS infra monitors, incidents),
     ArgoCD holds what is actually deployed. A cross-plane root cause usually needs two agreeing.

5. **Mark every hypothesis REBUTTED or CONFIRMED with the specific evidence.** "Unclear" is a real,
   reportable outcome — say what would settle it. Never quietly drop a branch you failed to test:
   a silently-dropped hypothesis is exactly the anchoring failure this protocol exists to prevent.

   **Separate what you OBSERVED from what you READ.** A report that hands you the answer is your
   strongest HYPOTHESIS, not your conclusion — go get the primary evidence it predicts.

   - **Read** = ticket comment, triage note, commit message, PR description, dashboard prose. Each
     states what its author believed when they wrote it: stale sometimes, wrong sometimes.
   - **Observed** = the metric, log line, span, row, or reproduction you looked at yourself.
   - **Depth order**, settle disagreements at the deepest: wire payload / durable row › log line ›
     metric › alert or dashboard prose › a commit message's account of its own effect.
   - **Telemetry contradicts a human record?** Telemetry wins, and the contradiction is a finding.
   - **Couldn't reach the primary evidence?** Say so, and name the one observation that would settle it.

6. **Dispatch `swarmkhazad:investigation-hypothesis-tester`, one per branch you want tested independently.** Give
   it the hypothesis, the refuting evidence you expect, and which plane to look on; it returns
   REBUTTED/CONFIRMED/UNCLEAR with evidence. Scale to the question: one obvious root cause needs no
   fan-out, and a branch whose discriminating observation is in telemetry has no other route.

   **Record each returned verdict with `note.bb finding` as it lands**, one bullet per hypothesis:

   ```
   note.bb finding "H2 REBUTTED: the consumer never lagged" "logfire span queue_depth peaked at 4, not the 10k a backlog needs"
   ```

   The tester has no write tools and that stays true — you are its only route to the record. A verdict
   you did not note is a verdict that does not survive your own context window.

7. **Write `repro.md`** — see below. This is what makes `Repro:` decidable by anyone other than you.

8. **Comment the RCA on the ticket, then set the label.** In that order: the label without the comment
   is a state change nobody can read.

   ```
   mcp__linear-server__create_comment   body = the RCA below, verbatim
   mcp__linear-server__update_issue     labels = existing + `Evidence: found` (or `Evidence: none`) + `swarm-created`
   ```

   `Evidence: found` means a hypothesis survived WITH specific evidence. `Evidence: none` means the
   tree converged on nothing. Those are the only two values and they are mutually exclusive in
   Linear, so you cannot claim both. Setting NEITHER is what a crash looks like, which is how the loop
   tells a crashed lane from a negative result — so never leave the group unset on a run that finished.

9. **Hand off to `run`** with `swarm_handoff.bb`. Your pane closes at that point, by design: a ticket's
   laptop cost is bounded, and the reproduction runs in the cloud, not here.

## `repro.md` — the one thing the `run` role executes

You NAME the command. The runner RECORDS the outcome. Never write a `Repro:` label yourself, and
never state in the RCA that something reproduces unless you ran it — the whole separation exists
because a role that both names the test and reports its result can report anything.

`repro.md` uses metrics.md's own bar grammar, one line, and the `@cloud` marker sends it to the
self-hosted environment rather than the laptop:

```markdown
# repro

## Quantitative
- reproduces the stuck row — bar: exit 0 and the row stays `pending` — measure: @cloud `bb -e '...'` — ticket: MITH-1234 — origin: https://github.com/MithraAI/istari — branch: main
```

| field | what it means | when to omit |
|---|---|---|
| `measure: @cloud <cmd>` | the command, dispatched to the runner | never |
| `ticket:` | the Linear ticket the runner comments on | never — without it the runner demands a PR |
| `origin:` | the repo to clone, if not this worktree's | when the worktree's origin is right |
| `branch:` | the branch to reproduce on, if not this worktree's | when the worktree's branch is right |

**The command must be able to FAIL.** `echo`, `true`, `ls`, a bare `git log` — anything that exits 0
whatever the system does — is not a reproduction, it is a formality, and `run` refuses it. Write the
command so that a healthy system exits non-zero and the bug exits zero, or the reverse, and say
plainly in the `bar:` which way round it is.

**`Evidence: none` needs no `repro.md`.** There is nothing to reproduce, and `run` short-circuits
straight to `Repro: no`. Write the file only when you set `Evidence: found`.

## Read-only discipline

You have no route by which a code change reaches a repository: this task opens no PR and the `run`
role after you measures rather than merges. Do not spend turns trying — no `git commit`/`push`, no
`gh pr create`, no `sed -i` standing in for a fix. Your recommended fix belongs in the RCA as **prose
a human or a later fix task can act on**, not as a diff.

Writes you DO own: the ticket comment, the `Evidence` label, `repro.md`, and `note.bb`'s bullets.

## The RCA schema (the ticket comment, verbatim)

Keep it tight — a reader should reach the root cause within the first screen. Omit a section only
when it genuinely does not apply, and say why rather than leaving it blank.

**Writing style (mandatory — the first live RCA shipped 8,700 chars and the reader asked for half):**
- **Big idea first, every section.** Open each section with ONE plain-language sentence naming what
  is true at design altitude ("the alert paged on a bad model name someone typo'd into the registry",
  not a walk through the query that found it). Mechanism and evidence come after, for readers who
  drill in.
- **Terse like a senior engineer's incident note.** Drop filler, hedging, and narration of your own
  process ("I then queried..."). Fragments are fine. Keep technical terms, ids, and error strings
  EXACT — compression never touches evidence.
- **Budgets:** headline ≤ 80 chars (it becomes the ticket title and the Slack reply). Whole RCA
  ≤ ~4,000 chars — the Problem section counts against this, so keep it to 3-4 lines; if the budget
  binds, take the space from `Reproduction` (frequently just "named, not yet run — the runner
  decides"). Quote only the DECISIVE line of a log/error, never the block around it. Evidence is
  bullets, not paragraphs.
- The verdict word must be plain: "backwards", "never fires", "counts the wrong thing" — not
  "inverted", "orthogonal", "vacuous".

```markdown
## RCA: <one-line symptom, ≤80 chars>

**Problem:** <3-4 lines. What was reported / observed, in the reporter's terms (not the mechanism).
Who is affected and how badly (so it can be ranked). What this ticket wants: fix needed / already
fixed / FYI only / needs a decision — and if partly fixed, WHICH part remains.>

**Root cause:** <one or two sentences — the mechanism, concretely. Name the file:line, the config
key, or the external fault. If unconfirmed, say "UNCONFIRMED —" and state what would settle it.>

**Confidence:** confirmed (observed) | confirmed-by-report | unconfirmed
<!-- (observed) = you saw it. confirmed-by-report = you corroborated someone else's conclusion
     without reaching the primary evidence. Different claims; never collapse the second into the
     first. There is no "confirmed (reproduced)" here — reproduction is the runner's verdict, not
     yours, and it lands as the `Repro` label after your pane has closed. -->

### Symptom
What was observed, where, when, and how it differs from expected — with the observable.

### Hypotheses

| # | Hypothesis | Verdict | Evidence |
|---|---|---|---|
| 1 | … | REBUTTED | the specific observation that killed it |
| 2 | … | CONFIRMED | the specific observation that confirmed it |
| 3 | … | UNCLEAR | what was checked, and what would settle it |

### Timeline
<Mermaid timeline of the event sequence — format rules below the schema. Include whenever the
cause is a sequence of events over time (an incident, a job's lifecycle, a state progression);
omit for a static config/code state, and say why.>

### Reproduction
The command named in `repro.md` and what it is expected to show. The OUTCOME is not yours to state:
the `run` role dispatches it and comments the result, and the `Repro` label follows from that
comment. Or: "not reproducible — <why>", when you set `Evidence: none`.

### Recommended fix
Prose, scoped enough for a fix task to act on: which file/function/config changes, what the new
behavior is, and how to prove it (the test or check that would fail before and pass after).

### Residual risks / follow-ups
What this analysis does NOT cover, and any adjacent problem found but out of scope.
```

### Timeline format — mermaid with byte-to-byte evidence

Wrap in a ```` ```mermaid ```` fence — Linear renders it in the published comment. Every entry
carries the verbatim evidence, its info-type flag, and — for the deciding entries — the hypothesis
it proves or disproves:

```mermaid
timeline
    title RCA Timeline — <ticket ref>: <short title>

    section <YYYY-MM-DD> (UTC)
        HH.MM.SS : [ℹ️ INFO] <event title> : <SOURCE><br>EVIDENCE:<br>"<verbatim bytes, ≤3 lines>"
        HH.MM.SS : [⭐ KEY — PROVES H2] <event title> : <SOURCE><br>EVIDENCE:<br>"<verbatim bytes>"<br>H2 "<hypothesis>" PROVEN
        HH.MM.SS : [⭐ KEY — DISPROVES H1] <event title> : <SOURCE><br>EVIDENCE:<br>"<verbatim bytes>"<br>H1 "<hypothesis>" DISPROVEN
```

- **Evidence is byte-to-byte** — exact text copied from the named surface (`LINEAR-COMMENT #n`,
  `GH-COMMENT #n`, `LOGFIRE-SPAN`, `DD-INCIDENT`, `PG-ROW`, `SLACK-MSG`, …), quoted, ≤3 `<br>`
  lines; trim with `…`, never reword. A paraphrased "evidence" line is how a stale belief re-enters
  the report dressed as data.
- **No `:` or `#` inside evidence text** — mermaid timeline splits fields on `:`. Replace with `;`
  or `-`. If the renderer chokes on `"`, swap for `«»`.
- **Flags**: ℹ️ INFO routine event · ⚠️ WARNING anomalous signal · 🚨 ERROR failure · 🔍 REVIEW
  investigation step · ⭐ KEY hypothesis-deciding evidence. Only KEY entries carry `PROVES Hn` /
  `DISPROVES Hn`, naming the hypothesis verbatim — the Hn numbers are the rows of the Hypotheses
  table above, so the labels resolve.
- **Times are the event's own UTC timestamp** from its surface — never synthesized, never rounded.
- The timeline counts against the RCA's ~4,000-char budget — keep to the deciding events (typically
  ≤8), the sequence-establishing ones included; it is the evidence spine, not a full comment dump.

## Honesty bars

- **Never write a `Repro` label, and never call a root cause "reproduced".** You do not run the
  reproduction; the `run` role does, in the cloud, after your pane has closed. Claiming its verdict is
  the one failure this lane's whole shape exists to prevent.
- A root cause you READ rather than SAW is `confirmed-by-report`, however authoritative the source
  looked — that surface is the one most likely to hand you a confident answer you never verified.
- A tool you could not reach (no token, no permission) is a **stated gap**, not a silent omission —
  the reader needs to know which evidence class was unavailable. The telemetry planes are denied to
  you BY DESIGN, so "I could not query Logfire" is never a gap: it is a subagent you did not dispatch.
- If the evidence genuinely does not converge, comment the tree with no CONFIRMED row, set
  `Evidence: none`, and name the discriminating observation someone with more access should make.
  That is a useful result; a confident wrong root cause is worse than an honest inconclusive one.
- Surviving is not the same as being proven. Do not upgrade the last story standing to CONFIRMED
  because its rivals died.
