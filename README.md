# swarmkhazad

A local, goal-driven swarm: SwarmForge's runtime, the `.claude` goal contract, and khazad's goal judge, in one Babashka repo.

One runtime dependency: [Babashka](https://babashka.org) (`bb`). `git` and `tmux` are assumed present.

## Quick start

```bash
brew install babashka tmux                    # bb is the only runtime dependency
brew services start victoriametrics           # optional, for telemetry — see the warning below
git clone https://github.com/HuskyDanny/swarmkhazad && cd swarmkhazad

./swarmkhazad new my-task --repo ~/repos/some-project
```

That scaffolds `~/.swarmkhazad/tasks/my-task/`. Three files there are yours to write before you open the swarm.

**`goal.md`** — what done means. Every checkbox names the role that owns it:

```markdown
## Goal
- [ ] implement — the guest-token route returns { uuid, schema }
- [ ] review — every Acceptance line is checked against the diff, in draft-review.md
- [ ] run — the repo's own test command exits 0
- [ ] the diff stays inside src/api/ and its README

## Not-goal
- Anything in the database layer — that is a separate ticket.

## Hints
- /abs/path/to/the/file.ts — where the change goes
```

The `<role> — ` prefix is load-bearing: the goal judge grades each role against **its own lines plus the lines naming no role**, so a line owned by someone else never blocks a role that cannot act on it. A checkbox with no role prefix belongs to everyone.

**`metrics.md`** — how done is measured. The `run` role executes every `measure:` command verbatim, from its worktree, and writes each result to `evidence/<bar>.txt`:

```markdown
## Quantitative
- resolveTenantUuid is gone — bar: no matches — measure: `! rg -q resolveTenantUuid src/api/ && echo gone`
- repo tests — bar: exits 0 — measure: `bun run test src/api/`

## Qualitative
- the diff stays inside src/api/ — judged by: Allen
```

**`roles`** — one line per agent, in pipeline order. A role names no checkout; it can work in any of the task's repos:

```
implement claude task --model sonnet
review    claude task --model sonnet
run       claude task --model haiku
```

**`repos`** — the checkouts this task works in, one per line:

```
~/repos/some-project
~/repos/other-project branch=feat/x
```

A goal line tags the repos it touches, and that is what decides which sessions
run. An untagged line belongs to every role and every repo, which is what a
one-repo task writes:

```
## Goal
- [ ] implement @some-project — the route returns 200
- [ ] implement @other-project — the client calls it
- [ ] review — read both
```

Then run it:

```bash
./swarmkhazad open my-task        # spawns the swarm and returns; no windows open
./swarmkhazad portal              # http://127.0.0.1:8765 — watch it work
./swarmkhazad telemetry my-task   # cost, tokens and sessions per role
./swarmkhazad close my-task       # archive the panes, stop the daemon
```

Attach to any role directly: `tmux -S /tmp/swarmkhazad-$USER/my-task.sock attach -t sk-implement`.

### Three things that bite

- **Start VictoriaMetrics before `open`.** An OTLP export to a closed port is dropped, not queued — that task simply has no telemetry, and nothing tells you so.
- **A `measure:` command must be the repo's own.** `bun test` is Bun's native runner, not your `test` script: pointed at a Vitest suite it fails every file, while `bun run test` passes. Detection gets this right; a hand-written bar can still get it wrong.
- **Roles run unattended under `bypassPermissions`.** They commit, run your test suite, and can read your other checkouts. Point them at a repo you are willing to have touched.

## Where things live

Runtime home is `~/.swarmkhazad/` (override with `SWARMKHAZAD_HOME`). Nothing is written under `~/repos/` or `~/.claude/`.

```
~/.swarmkhazad/tasks/<task-id>/
  goal.md  metrics.md          the truth: what done means and how it is measured (locked 444)
  roles                        the lineup, one line per role (grammar below)
  repos                        the checkouts, one per line
  decision.md gotcha.md finding.md escalation.md   one bullet per line, written by note.bb
  draft-<role>.md              a role's full write-up
  evidence/<bar>.txt           the run role's measurements
  worktrees/<repo>/            one worktree per repo, branch sk/<task-id>, added from the checkout itself
  mail/<session>/              outbox/ sent/ failed/ inbox/{new,in_process,completed}
  bin/                         per-role harness shims (claude, codex, grok)
  prompts/ hooks/              generated per launch
  state/                       sessions.tsv (the (role, repo) table), tmux socket, board/, daemon/, sessions/, judge/
  tmp/                         scratch; handoff drafts live here, never in the repo
```

Each stage creates the directories it fills; `prepare` creates `worktrees/ mail/ state/ tmp/` and the three bullet files.

### The `roles` declaration

```
<role> <harness> [task|batch] [model=<vendor>] [cli args...]
```

`role` is `[A-Za-z0-9][A-Za-z0-9-]*` — a path component, a refname segment, and half of a tmux session name, which is why a dot is refused: `tmux new-session -s sk-a.b` succeeds and every later `-t sk-a.b` fails `can't find pane: b`, because tmux reads a dot as `session.pane`. `harness` is `claude|codex|copilot|grok`. The optional tokens may appear in any order; anything else is passed to the harness CLI verbatim.

### The `repos` declaration

```
<abs-path> [branch=<name>]
```

Each path is a local git checkout — a linked worktree is fine, and so is a shallow one. `branch=<name>` starts the task branch from that branch instead of the checkout's default; the branch must already exist there, since the swarm never fetches. Two checkouts with the same basename cannot share a task: the basename names the worktree directory and tags goal lines, so a collision would make both ambiguous.

### Sessions

A task runs one session per (role, repo) pair, and that is the unit with a tmux pane, an inbox and a judge verdict. `state/sessions.tsv` is what `prepare` writes: columns `session role repo worktree-path harness receive-mode model extra-args`. A one-repo task names its sessions after its roles (`review`); past one repo the id says which (`review_gobel`). Nothing derives that rule twice — every reader looks the id up in the table.

Which repos a role gets comes from its goal lines: the `@repo` tags on the lines it owns, or every repo when it has none. A tag naming a repo the task does not hold is refused, because a typo that silently widened a role to every checkout would only be found by watching it open the wrong worktree.

### The worktrees

There is no clone. Each repo gets one worktree, added from the checkout itself, on branch `sk/<task-id>` — so roles sharing a repo share its worktree, and the handoff between them needs no merge. The start ref is explicit: the source's `origin/<default>` at open time (`origin/HEAD`'s target, else `main`, else the source's own branch). A bare `worktree add` would inherit whatever the source has checked out, which is how a task once started from someone else's work in progress. A ref naming a commit the checkout does not actually hold — a shallow clone's `origin/main` pointing past its own boundary — falls back to the branch tip it has.

The cost of not cloning is that `git worktree add` registers the worktree and the branch **in the source checkout**. Nothing else there is touched: the working tree stays clean and on its own branch.

`close` clears both. `swarmkhazad reap` is for the tasks whose close never ran — it prunes stale registrations and deletes every `sk/<task-id>` branch whose task folder is gone, across the checkouts under `SWARMKHAZAD_REPO_ROOTS` (default `~/repos`). A branch holding commits is listed with what deleting it would lose and left alone until `--force`, because an orphaned branch is also what an unmerged, unpushed day of work looks like. Live tasks and anything a checkout currently has checked out are never candidates.

## Commands

```
swarmkhazad new <task-id> [--repo <path>]... [--linear <KEY>]
                                               scaffold goal.md, metrics.md, roles
swarmkhazad prepare <task-id>                  layout, worktrees, mail dirs, sessions.tsv — no agents yet
swarmkhazad open <task-id>                     prepare, then spawn exactly the declared roles
swarmkhazad open --linear <KEY> [--repo <path>]...
                                               scaffold from a Linear issue, then open
swarmkhazad close <task-id>                    archive panes, stop the daemon, kill the tmux server
swarmkhazad summary <task-id>                  ask whether the work is ready to merge, for its goals
swarmkhazad ship <task-id> [--yes]             push each repo's branch and open a draft PR, in the summary's merge order
swarmkhazad reap [--force]                     prune stale worktrees, delete orphaned sk/* branches from your checkouts
swarmkhazad paths <task-id>                    print the path map
swarmkhazad portal [--port <n>]                serve the portal on 127.0.0.1 (default 8765)

note.bb <decision|gotcha|escalation|finding> '<claim>' '<why>'
                                               append one bullet, tagged with the session's repo
swarmkhazad telemetry <task-id>                cost, tokens and sessions per role
```

## The swarm

`open` starts one tmux server per task on `/tmp/swarmkhazad-<user>/<task-id>.sock`, one session per (role, repo) pair (`sk-<session>`), puts the board card in the first role's lane, queues a `(New Task)` note to the first session, starts `handoffd`, then launches each session's harness in its repo's worktree with `SWARMFORGE_ROLE`, `SWARMKHAZAD_SESSION`, `SWARMKHAZAD_REPO`, `SWARMKHAZAD_TASK_ID`, `SWARMKHAZAD_TASK_DIR` exported and `<task>/bin` plus this repo's `scripts/` on PATH. Each session gets `prompts/<session>.md` (folder rules, mail rules, a stage prompt from `prompts/<role>.prompt` — the stage is the role's, so every repo gets the same instructions and only the header differs) appended to its system prompt. No terminal windows open; attach with `tmux -S <socket> attach -t sk-<session>`.

### Harness shims

`open` installs `scripts/shim.sh` as `<task>/bin/<harness>` for every known harness and launches roles through them. A shim reads the session from `SWARMKHAZAD_SESSION`, its model vendor from `state/sessions.tsv`, the vendor's row from `state/vendors.tsv` (a copy of `scripts/vendors.tsv`: `vendor base_url keychain_service model_main model_small ctx_tokens` — the one table the shim and the smoke both read), and the real binary from `state/harnesses.tsv` (resolved at `open` from the operator's PATH), then execs the real CLI. For `claude` it applies the vendor's configuration first — `anthropic` (direct; any routing inherited from the operator's shell is dropped), or a `vendors.tsv` row (base URL, keychain token via `security`, model ids, context window, `--model` pin) — and the telemetry exporter: `CLAUDE_CODE_ENABLE_TELEMETRY=1`, OTLP metrics over `http/protobuf` to `SWARMKHAZAD_OTLP_ENDPOINT` (default `http://127.0.0.1:8428/opentelemetry`, a local VictoriaMetrics), `OTEL_RESOURCE_ATTRIBUTES=task_id=<id>,role=<role>,session=<session>,repo=<repo>`. The vendor's token sits in the role's process environment for the life of the session, where the agent can read it; that is the cc_alt arrangement, not a new exposure.

`open` also pre-accepts Claude Code's folder-trust dialog for every claude role's working directory by adding `projects[<realpath>].hasTrustDialogAccepted: true` to `~/.claude.json` (`SWARMKHAZAD_CLAUDE_JSON` overrides the path); `close` removes exactly those entries. Nothing else in that file is touched. Without it a fresh worktree stops at the dialog and hooks never run.

### The contract, enforced

Every claude role loads `<task>/hooks/<role>.settings.json` (via `--settings`), which wires `scripts/hooks/run-contract.sh` on `SessionStart` and on every file or shell tool. At `open` the truth is locked (`chmod 444 goal.md metrics.md`). On session start, resume and compact the hook re-locks it and injects the contract, `goal.md` and `metrics.md` verbatim, the bullets so far and the role's own `draft-<role>.md`, so a role that has lost its context gets the truth back without being told to re-read anything. Before any Edit/Write on those two files, or any Bash command that names them (or the task folder itself) with a mutating verb or as a redirect target, the hook denies with a reason that says what to do instead; reads pass. Denials are appended to `state/denials.jsonl`.

Roles report by appending one-line bullets to `decision.md`, `gotcha.md`, `escalation.md` and by writing `draft-<role>.md`; a git handoff's `artifacts:` names that draft.

### The goal judge

`scripts/goal_judge.bb` is khazad's `GoalJudgeModel`, and it runs **at the handoff boundary**. When `swarm_handoff.bb` sends a `git_handoff` from a claude session it calls `goal_judge.bb --grade <session>`, which summarises that session's committed state (git status, commits and diff since the base, `draft-<session>.md`, every file under `evidence/`) and grades it against `goal.md` with a cheap model through the session's own shim environment: `claude -p --model haiku --json-schema {met, unmet}`, thinking off, output bounded, no tools, no MCP.

A session is graded on **its own goal lines only**: `- [ ] <role> @<repo> — <outcome>` decides ownership, a line naming neither belongs to everyone, and the rest go to the model under a heading saying they are not this session's to grade. Graded whole instead, every role of a multi-role task is unmet until the last one finishes — and a role spanning three repos gets one "partially met" verdict for work the judge can only see a third of.

The verdict lands in `state/judge/<session>.json`. Unmet refuses the handoff and names the items; after `max-refusals` (3) the handoff goes through carrying `unmet:` in its headers, because refusing forever wedges every later role on one that cannot meet its bar. A successful handoff clears the count. A judge that fails — process error, timeout, no JSON, or a crash in the judge process itself — yields `met=false, unmet=[judge_unavailable]` and the handoff is refused like any other gap: an infra fault delays the work, it never passes it. The previous verdict on disk is never reused as a fallback; it was a grading of different work. Every distinct unmet verdict appends one line to `escalation.md`.

Grading used to run on every `Stop`. Measured on a real task: the same role graded four times in one turn, three escalation lines saying the same thing, and a verdict that flipped UNMET → MET on a tree with no commit and no evidence write in between. What is left on `Stop` is a **nudge**, and nothing about goals: if a session has committed work and no `git_handoff` for that HEAD, the turn is held open (at most twice per turn) to ask for one. That is the single failure a stopping role cannot see about itself — it finishes, stops, and the board freezes with nobody watching. A worktree git cannot answer for counts as unsent work: a spurious nudge costs a line in a pane, a missed one costs the task.

### Run-stage evidence

The `run` role does not judge; it measures. `run_evidence.bb`, run from its worktree, executes the repo's own test command (detected from the worktree: `package.json` → `npm test`, or the lockfile's manager running that same script — `pnpm`/`yarn`/`bun run test`, never `bun test`, which is Bun's own runner and fails a Vitest suite outright; `bb.edn` → `bb test`, `pyproject.toml` → `pytest`, `go.mod`, `Cargo.toml`, a `Makefile` `test:` target) and every `measure:` command in `metrics.md`'s Quantitative section (`- <name> — bar: <threshold> — measure: \`<command>\``; `<id>` and `<task-id>` in a command become the task id, `<task-dir>` the task folder). Each lands as `evidence/<bar>.txt` with the command, cwd, threshold, start time, duration, exit code and the interleaved output (clipped at 64 KiB; a command past the 20-minute timeout records exit 124). The goal judge reads every file in `evidence/` when it grades, so a bar is met when its file says so. Local only: `SWARMKHAZAD_RUN_REMOTE` is the seam for a later remote runner and refuses to run while set.

`swarmkhazad smoke <task-id>` proves each declared role callable without a swarm: in parallel, it runs each role's shim in print mode with the role's prompt, asking it to read `goal.md`, write a note draft addressed to itself and run `swarm_handoff.bb`; it reports per role the exit code, whether the note reached the outbox, the model the run reported (checked against the vendor's pin — another model is a collision, not a pass), cost and turns. The smoke notes are removed afterwards.

Files are the transport, tmux carries only the wake-up. A role writes a four-line draft under `<task>/tmp/` and runs `swarm_handoff.bb <draft>`; the helper fills the commit (worktree HEAD) and artifacts, installs the handoff in the sender's `mail/<session>/outbox/`; `handoffd` copies it into each recipient's `inbox/new/` and types a wake-up into the recipient's pane. Recipients run `ready_for_next.bb` and `done_with_current.bb`. Nothing is merged: sessions in one repo share that repo's worktree, so the commit is already on the branch, and a commit from another repo is not in this one's object store at all. The last session's git_handoff is the terminal broadcast: marked `non-forwarding`, recipients stop, the card goes to `done`.

`to:` names a **role**, not a session. `to: review` reaches every session review has, so the sender never has to know how many repos the recipient holds; a session id still works, and `to: all` is every other session. A git handoff past one repo carries `origin_repo`, because a recipient with no session in that repo still has to be told which tree moved — that is the reviewer with no superset goal line who nonetheless needs to know superset changed.

### The turn moves as a role

A role with three repos hands off three times. `handoffd` records each one in the board card's `handed` column and moves the card only when the set covers every session that role has. A review that started on the first handoff would be reading two trees that are still moving. The lane is therefore always a role name — one swimlane column per role, whatever the repo count — and the portal shows the progress inside it as a fraction.

## Shipping

`swarmkhazad ship <task-id>` is the only thing here that leaves the machine, and every guard on it is about that.

It **requires the summary** — `swarmkhazad summary <id>`, or the portal's "Ready to merge?" button. That is where a person reads a verdict, so requiring it means nothing ships unread. The **merge order comes from that summary's `## Merge order` section**, never from a second inference: two things working an order out will disagree at the worst moment, and the summarizer is the one that has read every diff. An order that skips a repo with commits is a refusal, not a guess.

Then it prints the plan — repo, branch, base, the GitHub repo it resolved, the **account it will push as**, and the commit count — and waits for `yes`. Nothing before that point touches the keychain. `--yes` skips the prompt and prints the same plan.

The account comes from an owner→account map read off `remote.origin.url` (`MithraAI → allen-mithra`, default `allen-mithra`, `SWARMKHAZAD_GH_ACCOUNT` overrides). `config --get`, not `git remote get-url`, because get-url applies `url.<x>.insteadOf` and a checkout that mirrors GitHub would report no owner at all and silently take the default. The token is fetched per command with `gh auth token -u <account>`; `gh auth switch` is never called, because rewriting the operator's global gh state to push one branch is not a thing a tool should do.

Per repo, in order: push `sk/<task-id>` with an explicit refspec (checked against the source's default branch first — never main), then open a **draft** PR whose body carries the summary's verdict and that repo's own goal lines. What was opened is recorded in `state/pr/<repo>.json`, and the card moves to its own `in-review` lane: a shipped task and a finished task are different states.

### What comes back

A task used to end at the handoff; now it waits in review, and what arrives there is work in the shape the swarm already handles. `handoffd` asks each shipped PR every 60 s — the portal's **Check now** button skips the wait — and turns each new unresolved review thread, each new comment and each failing check into a handoff (`type: pr_comment`, `type: pr_check`) addressed to **the session that last handed off in that repo**: the one whose archive, verdict and evidence are all about the diff under review. From there it is ordinary mail — claim-once, retry, the `failed/` path, the tmux wake-up — because it goes out through the same outbox everything else does.

The handoff body carries the exact `gh` commands with the thread id already in them. It never replies and never resolves on the role's behalf: resolving means the role agreed, and a thread it disagrees with gets a reply with the reason and stays open.

Two things it refuses to do. A check that has failed twice on the same commit stops waking anyone and becomes an escalation instead — a pipeline broken for a reason nobody in the task can fix would otherwise spin a role for as long as it stays broken. And a poll that cannot reach GitHub does nothing at all rather than reading silence as "no comments", which would mark every one of them handled and lose them.

**It stops at the first failure.** Re-running is how you continue — a branch already at the remote is not pushed again, and a repo that already has an open PR for this head prints it instead of opening a second. A pushed branch with a draft PR is not damage needing a rollback; continuing past a failed push would open a PR on work nobody has.

## The portal

`swarmkhazad portal` serves a local, server-rendered page over `~/.swarmkhazad` (http-kit and hiccup ship inside `bb`; `SWARMKHAZAD_PORTAL_PORT` or `--port` picks the port; it binds 127.0.0.1 only). It is the one always-on process besides a task's own daemon, and it is read-mostly: nothing it shows is stored anywhere but the task folder.

- `/` — the projects, each as a swimlane with one column per role and its tasks as cards. A card carries its attention count and, when its role holds more than one repo, how far through that role it is (`1/2`) — three repo names do not fit in a 190px card, and the fraction is the part a reader is asking for. Below them, the tasks belonging to no project, and the new-project composer.
- `/projects/<name>/edit` — the same form, filled in: tick a checkout on or off, add or drop a role, change a role's vendor. The name is fixed, because a task points at its project by name. **Delete** removes the lineup only: its tasks snapshotted `repos` and `roles` at scaffold time, keep running, and reappear under "Tasks outside a project".
- `/tasks/<id>` — refreshes every 5 s. Past one repo the Goal list is grouped under the repo each line's `@repo` tag names, with the untagged lines last under `every repo`. **Attention** first: escalation lines, failed mail, contract denials, a down judge, a dead daemon. `finding.md` is deliberately not counted — a finding is something the swarm worked out, not something waiting on anyone, and counting it as an ask is how one task's badge read 22 when ten of those lines needed nobody. Then the board lane, `goal.md`'s Goal boxes with a live status per line — `unmet` when a role's latest verdict names it, `met` when every verdict is met, `pending` otherwise, `ticked` when control has ticked it in the file; the portal never edits the file — the metrics bars with each one's latest `evidence/<bar>.txt` (exit, time, last lines), the role cards (harness, vendor, verdict, mail counts, the pane's last line), the four bullet files and the drafts.
- `/tasks/<id>` also grows an **In review** section once a task has shipped: one line per PR, and a **Check now** button that polls them immediately instead of waiting for handoffd's next minute.
- `/tasks/<id>/roles/<role>` — the role's pane, polled every 2 s from `/tasks/<id>/roles/<role>/pane`: the live tmux capture while the task's server is up, the archived `state/sessions/<role>/pane.txt` after `close`.
- `/tasks/<id>/doc?path=<rel>` — any regular file inside the task folder (`doc-file`: canonical path under the task folder, never under `worktrees/`, never through a symlink that leaves it).

## Intake from Linear

`swarmkhazad open --linear MITH-3437` (or `new --linear`) writes the task from the issue: the title becomes the heading, each acceptance or "Done when" line becomes one unticked `Goal` checkbox, the description is kept whole under `## From the issue`, the issue URL is the first Hint, and `roles` is one `implement` role (one per `--repo`, else `none`). `Not-goal` is left for the operator — intake never invents one. With no task id given, the id is the issue key lowercased.

The fetch is a headless `claude -p` with exactly one MCP server and exactly one tool (`mcp__linear-server__get_issue`), schema-forced to `{found, identifier, title, description, url, state, acceptance}`. There is no Linear API key of our own and no second auth path: the operator's own Linear MCP config is the credential. A fetch that does not return the issue — missing, forbidden, the wrong issue, a dead server — fails before the task folder is created, so a retry is not blocked by a half-scaffolded task. An existing task folder is opened as it stands; intake never overwrites a goal someone has edited.

## Telemetry

Claude Code's own OpenTelemetry export is the source; the shim turns it on for every claude role and tags it (`OTEL_RESOURCE_ATTRIBUTES=task_id=<id>,role=<role>,session=<session>,repo=<repo>`), pointing OTLP at `SWARMKHAZAD_OTLP_ENDPOINT` (default `http://127.0.0.1:8428/opentelemetry`, a local VictoriaMetrics single-node). Start the store before `open` — an export to a closed port is dropped, not queued:

```
brew install victoriametrics && brew services start victoriametrics
```

Four metrics arrive, dotted, with `task_id` and `role` promoted to labels (VictoriaMetrics promotes OTLP resource attributes by default): `claude_code.cost.usage` (USD), `claude_code.token.usage` (by `type`: input, output, cacheRead, cacheCreation), `claude_code.session.count`, `claude_code.active_time.total`. MetricsQL takes the dotted names verbatim. They are **delta** counters — each sample is the increment since the previous export, so a live series rises and falls — which means a task's total is a sum over a window, never an instant read:

```
sum(sum_over_time(claude_code.cost.usage{task_id="<id>"}[7d]))
```

Measured on a three-role task: that form gave $3.01 against the three sessions' own reported $3.03, while an instant `sum(...)` gave $0.40 — one export interval's increment. The window does a second job too: an instant query stops seeing a series five minutes after its last sample, so a finished task read back later reports nothing at all.

Read them back three ways: `swarmkhazad telemetry <task-id>` prints cost, tokens and sessions per role; the portal's task page carries the same numbers in a **Telemetry** section with a link into vmui; and `dashboards/swarmkhazad.json` is a vmui dashboard (spend, tokens, turns and time, each per task and per role) — point vmui at it with `victoria-metrics -vmui.customDashboardsPath=<repo>/dashboards`, then open **Dashboards** at `http://127.0.0.1:8428/vmui/`.

## Tests

```
bb test            # everything
bb test task       # namespaces matching a substring
```
