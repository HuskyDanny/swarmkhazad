# Multi-repo, and a task that lives until its PRs merge

The plan of record. Agreed 2026-09-07 over a design interview; every decision
below was put as a question and answered, and the answers are kept verbatim in
the decision record at the end rather than paraphrased into prose.

Read the measurements first. Four of the decisions here reverse what the code
does today, and each reversal rests on something that was run, not reasoned.

---

## Why

A task's goal spans several repos. swarmkhazad can express exactly one.

Task `gobel` names three repos in its goal — superset, cirdan, gobel — and its
three roles were all bound to cirdan. superset and gobel were never cloned. Both
fixes were derived correctly and neither could be committed, so they became
escalation lines. Of that task's 22 escalations, roughly 17 are the tool rather
than the work:

| count | what | source |
|---|---|---|
| 4 | duplicate judge verdicts | the judge appends on every stop |
| 10 | verified findings with nowhere to go | no findings surface |
| 3 | repo scoping | one repo per role |
| 2 | bars unsatisfiable as written | task authoring |
| 3 | genuinely gated on an ArgoCD merge | real, not friction |

The portal even printed the diagnosis — `no role opens lothlorien, superset` —
and opened the task anyway.

---

## Measurements that decided this

Each of these was run against the live install, and each overturned an
assumption held in the code or in the design conversation.

**No merge has ever happened.** Every commit in every task worktree has one
parent. `merge_and_process.bb` calls `git merge-base --is-ancestor` first, and
because all roles share one clone and work in sequence, the sender's SHA is
always already an ancestor. It prints `MERGED:` and returns.

```
gobel/review     494e9a86 [5cbb7a8a]   ← review's own commit
                 5cbb7a8a [6af4cdd8]   ← implement's
gobel/implement  5cbb7a8a [6af4cdd8]   ← the same chain, one shorter
verify-1/run     52a5c7a  [32ebd65]    ← implement's commit, unchanged
```

The per-role branch has never diverged. The merge that justifies it has never
run.

**The judge flips on an unchanged tree.**

```
16:37:24  494e9a86 committed        ← last change to the tree
08:39     last evidence file written
08:43:47  judge: UNMET
08:46:59  judge: UNMET
08:49:32  judge: UNMET
08:51:44  judge: MET    "max blocks reached; goals met"
```

No commit and no evidence write between them. Same state, four gradings, three
unmet then one met — and the last one wins the verdict file that drives the goal
checkboxes. `exhausted` did not cause `met`; it only stopped the blocking. The
defect is that the judge grades the same state repeatedly at all.

**Disk is per-role working trees, not clones.**

```
fixture-guest-token/
  repos/       76M      3 clones
  worktrees/  6.4G      implement 2.2G · review 2.1G · run 2.1G
~/.swarmkhazad 8.8G, of which fixture-guest-token 6.5G and 3399 2.2G;
               every other task is 12M
```

Clone metadata is hardlinked, so it is nearly free — `du` of the source `.git`
and a task clone's `.git` **together** is 8.0M, of which the clone's incremental
share is 616K. Measured apart they each read ~7.4M. cirdan's pack file has 6
hard links right now.

The 6.4G is three working trees of the same repo, one per role, each filled with
build output by an agent. One branch per repo shared by all roles removes two
thirds of that by construction.

**A bare `worktree add` inherits the wrong base.** Two tasks, one source, in a
sandbox:

```
A. two worktrees off the source
   taskA base commit:  "someone else's work in progress"     ← the source's
                                                               CURRENT branch
   source branches now: feat/in-flight-work main sk/taskA sk/taskB
   source .git/worktrees: taskA-wt taskB-wt
   after rm -rf taskA-wt:
     /…/taskA-wt  8d557e6 [sk/taskA] prunable
   after git worktree prune:
     registration gone — orphan branches left behind: sk/taskA sk/taskB

B. two clones
   source branches after two clones: unchanged
   source .git/worktrees: (none)
   after rm -rf: source .git intact
   git gc --prune=now in SOURCE → clone: fsck clean, resolves HEAD, full history
```

This is `gobel`'s failure reproduced: its escalation says the superset checkouts
"sit on in-flight work (`feat/v6-message-performance` @ `c838405`), so editing
them would land the pin on top of someone else's branch."

The guard against it is in swarmkhazad's code, not in git. `source-pin-sha`
(`scripts/task_lib.bb:372`) resolves `refs/remotes/origin/<branch>` — "the shared
upstream state, not the operator's local work". A worktree gets the same guard
only if an explicit start ref is passed, which is the whole reason the layout
below insists on one.

**`worktree add` writes into the source.** On this repo, right now:

```
/Users/allenpan/repos/swarmkhazad/.git/worktrees/portal-redesign/
/Users/allenpan/repos/swarmkhazad/.git/refs/heads/
    main  worktree-portal-projects  worktree-portal-redesign
```

Branches created by a worktree live in the source's ref store and survive
`git worktree prune`. That cost is accepted deliberately below, in exchange for
the branch being visible and pushable from the checkout you already work in.

---

## The shape

```
~/repos/mithra_ai/cirdan/          your checkout. Gains branch sk/gobel and a
                                   .git/worktrees entry. Nothing else.

~/.swarmkhazad/tasks/gobel/
  goal.md        - [ ] implement @superset — MCP tier boots …
  metrics.md     - the import resolves @superset — measure: `docker …`
  repos          three absolute paths, snapshotted from the project
  roles          implement claude task model=anthropic        (no repo field)
  finding.md     escalation.md  decision.md  gotcha.md        [repo]-tagged, via note.bb
  worktrees/     cirdan/  superset/  gobel/                   all on sk/gobel,
                                                              added from origin/<default>
  mail/          implement.cirdan/  implement.superset/  review.cirdan/  …
  state/         sessions.tsv
                 judge/implement.superset.json
                 pr/cirdan.json
```

There are no clones. `worktrees/` are linked worktrees off your own checkouts,
so `git log sk/gobel` and `git push origin sk/gobel` both work from `~/repos`.

---

## Lifecycle

1. **Project** owns the repo list, and is editable and deletable. The per-role
   repo dropdown is deleted. Its `<select>` listed `(sort picked)` with nothing
   preselected, so a browser picked the alphabetically first repo — three
   untouched dropdowns, three times `cirdan`.

2. **Task** snapshots the repo list into `repos`. `prepare` adds one worktree per
   repo on branch `sk/<task>`, **always with an explicit `origin/<default>` start
   ref**.

3. **Sessions** are (role, repo) pairs, derived from `@repo` goal lines — plus
   the last role, which always gets every repo because its work comes from
   `metrics.md`, plus one repo-less `run` session in the task folder for
   cluster-scoped bars. A role's sessions boot when it takes the turn and are
   torn down when it hands off. That is the turn lock: a role without the turn
   has no agent to enforce against.

4. Each session starts at its own repo root, so that repo's `CLAUDE.md`,
   `.claude/settings.json` hooks, agents and MCP load. cirdan's cluster-mutation
   guard is one of them, and it has already fired in the field.

5. **Roles are serialized; a role's repos run concurrently.** They cannot
   collide — different worktrees. The turn moves as one piece when every one of
   the role's sessions has handed off.

6. **The judge runs at the handoff boundary**, once per (role, repo), on the
   committed tree, against that repo's `@repo` goal lines. Unmet refuses the
   handoff; after `max-refusals` (3) it goes through with the verdict attached,
   as headers plus a path to the verdict file. The Stop hook survives only as a
   nudge — "you stopped without handing off" — and never blocks.

7. **`note.bb <kind> <text>`** is the only writer of the bullet files. It locks,
   and stamps the `[repo]` tag itself. Concurrent sessions in one role would
   otherwise race on a shared file through their Edit tools.

8. **`ship`** reads the summarizer's per-repo verdicts and its merge order,
   pushes each branch and opens a **draft** PR per repo. Identity comes from an
   owner→account map (`MithraAI → allen-mithra`, default `allen-mithra`),
   resolved from the origin URL and **printed in the confirmation** before
   anything leaves the machine. It stops at the first failure and is idempotent
   on re-run. It refuses to run without a summary, so nothing ships that nobody
   has read a verdict on.

9. **The card moves to `in-review`**, its own lane. handoffd polls each PR every
   60s, and a portal button forces a check. Comments and failing checks become
   handoffs — `type: pr_comment`, `type: pr_check` — to the role that last
   committed in that repo, which wakes, fixes, pushes, replies and resolves.

   It never resolves a thread it disagreed with: it replies with the reason and
   leaves it open. A check that fails twice on the same commit stops waking
   anyone and escalates.

10. **Close** when every PR is merged or closed: `git clean -xdf` each worktree
    (that is where the 6.4G was — untracked build output, not git), remove the
    worktrees, delete the `sk/<task>` branches from the sources.
    `swarmkhazad reap` catches orphans from tasks whose close never ran.

---

## What this deletes

| gone | why |
|---|---|
| `merge_and_process.bb` | no merge commit exists in any task |
| per-role branches `sk/<task>/<role>` | one branch per repo |
| clones under `repos/` | worktrees off your checkouts |
| `repo-of:<stage>` dropdown | the defect's origin |
| `max-blocks` and the Stop block loop | the judge runs once per turn |
| `escalate!`'s broken dedupe | nothing left to duplicate |

Three of the four defects found in `gobel` are fixed by removing code.

---

## Order of work

1. Merge PR #12. Open and merge a PR for the commits on
   `worktree-portal-projects`. A days-long change stacked on two unreviewed
   branches is a conflict met at the worst moment, and `goal_judge.bb` — which
   this rewrites — is untouched by either stack.
2. New branch off main. This file first, then:
   grammar and layout (`repos` file, `@repo` tags, worktrees with a start ref)
   → sessions and mail → the judge move → `note.bb` and `finding.md` → portal
   → `ship` → the PR loop.
3. Old tasks migrate on read: a `state/roles.tsv` in the old shape is a one-repo
   task, and keeps its old worktree paths.

---

## Done means

- `gobel` re-scoped runs on the new shape: three worktrees on `sk/gobel`,
  sessions only for the (role, repo) pairs its goal lines name, each session's
  own `CLAUDE.md` and hooks loaded, implement's sessions concurrent, the turn
  moving to review only when all have handed off, no duplicate judge lines, and
  a single-digit Attention count.
- **The swarm itself commits the two fixes it already derived** — superset's
  `Dockerfile:30` pin `fastmcp>=3.1.0,<3.2` and gobel's `DEFAULT_UPSTREAMS`
  repoint — in their own repos, which it could not do before. This is the only
  part that proves the point.
- The 55 existing tests still pass.

---

## Risks

- **`sk/*` branches accumulating in your checkouts** when close does not run —
  the probe shows a deleted task leaves both a `prunable` registration and a
  branch that outlives it. `reap` exists for this and needs to be run, or it is
  the thing that makes you regret worktrees over clones.
- **`ship` is the first outward-facing action this tool has ever taken.** Draft
  PRs on a namespaced branch, confirmation before push, identity printed, never
  main, stop at the first failure.
- **The PR loop keeps a task alive for days.** A task that used to end now waits.
  `in-review` exists so that state is visible rather than looking like a stall.

---

## Decision record

Kept verbatim. The reasoning matters more than the conclusion when one of these
turns out wrong.

**Repos and layout**

- *Where does the repo set come from?* The project, snapshotted into the task —
  not derived from what roles happen to name. A repo nobody writes in is still
  worth reading: gobel's roles needed to read superset's Dockerfile and gobel's
  `upstreams.py` to derive the fixes, and could not.
- *Worktree or clone?* Worktree off the source. Rejected the clone (616K, zero
  footprint in `~/repos`) because active commit history, pushing, PRs and review
  comments all want the branch in the checkout you already work in. The cost is
  accepted knowingly: refs and worktree registrations land in your repos, and
  orphans need reaping.
- *One worktree per role, or per repo?* Per repo, shared by all roles, branch
  `sk/<task>`. Git forbids two worktrees on one branch, and the per-role branch
  has never diverged in any task on disk.
- *Nesting?* Role-major was considered and dropped with per-role worktrees. What
  remains is one worktree per repo.

**Sessions**

- *Session dimension?* (role, repo). This is what makes each session start at a
  repo root, which is what makes that repo's `CLAUDE.md` and hooks load — the
  reason a shared parent cwd was rejected.
- *Which pairs exist?* Derived from `@repo` goal lines, plus the last role gets
  every repo, plus a repo-less `run` session. A session that boots only to find
  it has no goal line is the `NO_TASK` read that measured $0.80.
- *Boot policy?* Lazy, per turn. A task that closes at review never pays for run.
- *Turn lock?* Lazy boot is the lock. A hook that can only fire when teardown has
  already failed is a second mechanism for a case the first one owns.

**The judge**

- *When does it run?* At the handoff boundary, not on Stop. This removes the
  duplicate escalations, the flipping grader and `max-blocks` at once, by
  removing the repeated grading rather than fixing it.
- *Before or after the commit?* After. The judge grades what is actually handed
  over; gobel's judge already produced a wrong "not committed" verdict once by
  looking at something else.
- *What is left of the Stop hook?* A nudge for the one case that is not about
  goals — a role that finishes, stops, and forgets to hand off, leaving the board
  frozen with nobody watching.
- *What stops a retry loop?* A refusal count per (role, repo). After N the
  handoff goes through with the unmet verdict attached — today's `exhausted`
  semantics, moved to the right boundary.
- *Verdict granularity?* Per (role, repo), against that repo's goal lines. The
  direct fix for gobel's "partially met", which spanned three repos the judge
  could see one of.

**Handoffs**

- *Still a merge?* No. `merge_and_process.bb` is deleted. Same-role sessions
  never share a worktree, so nothing can diverge.
- *Where does a handoff go when the recipient has no session for that repo?* To
  every session the recipient does have, marked with its origin repo. A review
  with no superset goal line still needs to know superset changed.
- *Who detects the join?* handoffd. It is already the only always-running process
  per task and already fans out.

**Files and surfaces**

- *Repo dimension — split files or tags?* Tags. `escalation_<repo>.md` was the
  original proposal and was dropped: it multiplies the places to look and leaves
  a cross-repo finding — gobel had several — with no home.
- *Findings?* A new `finding.md`, tagged, its own portal section, **not** counted
  in Attention. Ten of gobel's 22 escalation lines were verified findings, not
  asks.
- *Bars?* Optional `@repo`. Untagged bars are cluster-scoped and run in the
  repo-less `run` session. `repo tests` becomes N bars, because
  `detect-test-command` picking one worktree out of N is a silent wrong answer.
- *Board card?* Lane plus a fraction, `implement 2/3`. Three repo names do not
  fit in a 190px card.
- *Session paths?* `state/sessions.tsv` maps session → role, repo, path. Not for
  disk — because the path is currently derived in five places, which is how
  `:worktree-path` drifted from the truth.

**Shipping and review**

- *Summarizer with N repos?* One verdict per repo plus a merge order. gobel's
  review role worked that order out on its own and had to file it as an
  escalation because there was nowhere else.
- *Gate on `ship`?* Per-run confirmation showing exactly what will be pushed and
  as whom, and draft PRs only — never a merge, never main.
- *Order source?* `ship` requires the summary and reads its order. Two things
  inferring merge order will disagree at the worst moment; requiring the summary
  also means nothing ships unread.
- *Partial failure?* Stop at the failure, report, re-run to continue. A pushed
  branch with a draft PR is not damage needing a rollback, and continuing past a
  failed superset push would open a cirdan PR that depends on it.
- *PR events?* handoffd polls, plus a manual button. No public endpoint, so no
  webhooks.
- *What do events become?* Handoffs. A comment is work arriving, and the mail
  protocol already does claim-once, retry and failure.
- *Who wakes?* The role that last committed in that repo — the one whose session
  archive, verdict and evidence are all about that diff.
- *What is resolve?* Commit, push, reply, resolve. Never resolve a thread the
  role disagreed with: reply with the reason and leave it open.
- *CI failures?* They wake a role too. A red check is the most agent-suited
  feedback there is. A check failing twice on the same commit escalates instead,
  so a broken pipeline cannot spin an agent.
- *Identity?* Owner→account map from the origin URL, default `allen-mithra`,
  printed in the confirmation.
- *Lanes after done?* `in-review` as its own lane. A shipped task and a finished
  task are different states.
