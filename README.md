# swarmkhazad

A local, goal-driven swarm: SwarmForge's runtime, the `.claude` goal contract, and khazad's goal judge, in one Babashka repo.

One runtime dependency: [Babashka](https://babashka.org) (`bb`). `git` and `tmux` are assumed present.

`bb test` runs the suite, and `.github/workflows/test.yml` runs it on every push to `main` and every pull request. On **macOS**, not Linux, because that is the platform this targets and the suite says so: `stat -f '%Lp'` is BSD, and the tmux socket lives under `/tmp` because macOS caps the length of a unix socket path. Making the tests portable while the tool is not would buy a green run that describes a platform nobody uses.

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

**`metrics.md`** — how done is measured. The `run` role executes every `measure:` command verbatim, from its worktree, and writes each result to `evidence/<bar>.txt`. **`open` refuses** a task that declares `measure:` commands with no role to run them — a bar that never ran writes no evidence file, and neither does one that ran and passed, so the two are indistinguishable afterwards:

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
./swarmkhazad close my-task --reclaim   # ...and give the disk back
./swarmkhazad delete my-task      # ...and the folder, the branches and its telemetry
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
  draft-<session>.md           a session's full write-up (the role's own name at one repo)
  evidence/<bar>.txt           the run role's measurements
  worktrees/<repo>/            one worktree per repo, branch sk/<task-id>, added from the checkout itself
  mail/<session>/              outbox/ sent/ failed/ inbox/{new,in_process,completed}
  bin/                         per-role harness shims (claude, codex, grok)
  prompts/ hooks/              generated per launch
  plugin/                      a Claude Code plugin — .claude-plugin/ agents/ skills/; every CLAUDE role's argv names it with --plugin-dir
  state/                       sessions.tsv (the (role, repo) table), tmux socket, board/, daemon/, sessions/, judge/
  tmp/                         scratch; handoff drafts live here, never in the repo
```

Each stage creates the directories it fills; `prepare` creates `worktrees/ mail/ state/ tmp/` and the three bullet files.

### The `roles` declaration

```
<role> <harness> [task|batch] [model=<vendor>[:<model-id>]] [cli args...]
```

`role` is `[A-Za-z0-9][A-Za-z0-9-]*` — a path component, a refname segment, and half of a tmux session name, which is why a dot is refused: `tmux new-session -s sk-a.b` succeeds and every later `-t sk-a.b` fails `can't find pane: b`, because tmux reads a dot as `session.pane`. `harness` is `claude|codex|copilot|grok`, or one of the operator's own lane launchers (`cc_full|cc_auto|cc_control`), which a role inherits whole. The optional tokens may appear in any order; anything else is passed to the harness CLI verbatim.

`model=` reads two ways, and which one applies depends on the harness. On bare `claude` the VENDOR half selects the row in `scripts/vendors.tsv` and the shim applies all of it — base URL, keychain token, both model tiers, context window — so `model=kimi` is the whole declaration. On a lane the shim passes only the `<model-id>` half, because a lane starts its own router and that router keys on the model NAME; a lane therefore needs the full slug (`model=anthropic:moonshotai/kimi-k3:exacto@preset/cc-tools`) and a bare `model=kimi` reaches it as nothing at all, leaving the lane's own model in place. `swarmkhazad.bb smoke <id>` is what catches that: it compares the model the session reports against the vendor row and prints `used=` and `expected=` when they differ.

`cc_alt` is not a harness, though it is one of the operator's launchers. It takes its vendor as `--model <vendor>` in argv position 1 and starts no router, so a role's model id would arrive where a vendor name is expected and the launcher would exit 2. A role wanting a third-party model uses one of the two forms above.

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

That start ref is then **written down**, to `state/base.tsv` as `<repo>\t<sha>`, and every later reader of "what did this task change" uses it instead of resolving a ref of its own. A worktree shares its source's whole `.git`: one object store, and one copy of every branch, tag and remote-tracking ref. So `origin/main` is not the task's — it is the operator's, and a `git fetch` in `~/repos/<repo>` re-points it under a running task. Worse, `refs/remotes/origin/HEAD` is a symref `git clone` writes and other setups never do, so in some checkouts it is simply absent: a summary once rendered an empty `diff — superset` for a worktree holding the task's only commit, and reported `NOT READY — both diffs are empty` beside a repo that had correctly changed nothing. The pin cannot move and cannot be missing. `open` re-runs to resume a task, so an existing entry is never rewritten — re-pinning on resume would silently advance the base to whatever the remote had become.

Nothing else may resolve a base by guessing. Where the pin is absent (a task opened before it existed) the fallback tries every remote-tracking ref before any local branch — `origin/HEAD`, `origin/main`, `origin/master`, then `main`, `master` — because a local branch is the operator's and can sit behind its remote; gobel's `main` was five commits behind `origin/main`, and counting from it would have reported five unrelated commits as this task's diff. A wrong base fabricates a diff as readily as it loses one. When nothing resolves, the diff is **not computed and says so** — `BASE UNRESOLVED`, never `(none)`, because an empty section and an uncomputed one are the same three characters otherwise.

`close --reclaim` clears both: it cleans each worktree (`git clean -xdf`), removes it, and deletes `sk/<task-id>` from the source. That is where the space is — a worktree that has built anything is mostly untracked output, 6.4G across three of them in the task this design came from, none of it in git. A repo whose branch is not on `origin` is kept and said so, because that is also what an unpushed day of work looks like; `--force` takes it anyway. Plain `close` removes nothing: it has always meant *stop the swarm*, and the task folder — notes, evidence, PR records — outlives its checkouts either way.

`swarmkhazad delete <task-id>` is the end of the line, and the only command that leaves nothing behind. It does what `close --reclaim` does, then drops the task's series from VictoriaMetrics and removes the task folder. The telemetry is part of it rather than a step to remember, because the dashboard groups by `task_id`: a task whose folder is gone but whose series are not keeps drawing a line with nothing behind it to open — six of the twelve ids on the first machine this ran against had no folder left at all, and `delete` on one of those forgets the series and says so. It refuses, before touching anything, while any repo holds commits `origin` has never seen: `close` can keep the branch because the task folder still names it, and removing the folder is exactly what makes those commits unfindable. `--force` is reclaim's override and means the same thing. The portal has the same button on a task page, behind a tick box, and shows the refusal with the override beside it.

`swarmkhazad reap` is for the tasks whose close never ran — it prunes stale registrations and deletes every `sk/<task-id>` branch whose task folder is gone, across the checkouts under `SWARMKHAZAD_REPO_ROOTS` (default `~/repos`). A branch holding commits is listed with what deleting it would lose and left alone until `--force`, because an orphaned branch is also what an unmerged, unpushed day of work looks like. Live tasks, and any branch a worktree still holds — this checkout's or another's, which git reports directly rather than being inferred from the source's own HEAD — are never candidates.

## Commands

```
swarmkhazad new <task-id> [--repo <path>]... [--linear <KEY>] [--investigate]
                                               scaffold goal.md, metrics.md, roles
swarmkhazad prepare <task-id>                  layout, worktrees, mail dirs, sessions.tsv — no agents yet
swarmkhazad open <task-id>                     prepare, then spawn exactly the declared roles
swarmkhazad open --linear <KEY> [--repo <path>]... [--investigate]
                                               scaffold from a Linear issue, then open; --investigate
                                               uses the investigate → run lineup instead of implement
swarmkhazad mcp                                serve `add_task` as one MCP tool on stdio
swarmkhazad close <task-id> [--reclaim] [--force]
                                               archive panes, stop the daemon, kill the tmux server;
                                               --reclaim also cleans and removes the worktrees and
                                               deletes the task branch (kept if not on origin)
swarmkhazad delete <task-id> [--force]         everything close --reclaim does, then the task's series in
                                               VictoriaMetrics and the task folder itself. Refuses while a
                                               repo holds commits origin has never seen
swarmkhazad summary <task-id>                  ask whether the work is ready to merge, for its goals
swarmkhazad ship <task-id> [--yes]             push each repo's branch and open a draft PR, in the summary's merge order
swarmkhazad reap [--force]                     prune stale worktrees, delete orphaned sk/* branches from your checkouts
swarmkhazad paths <task-id>                    print the path map
swarmkhazad portal [--port <n>]                serve the portal on 127.0.0.1 (default 8765)

note.bb <decision|gotcha|escalation|finding> '<claim>' '<why>'
note.bb resolved '<match>' '<how>'      an ask you have since met yourself
note.bb retract  '<match>' '<why>'      an ask that should not have been made
                                               append one bullet, tagged with the session's repo
swarmkhazad telemetry <task-id>                cost, tokens and sessions per role
```

## The swarm

`open` starts one tmux server per task on `/tmp/swarmkhazad-<user>/<task-id>.sock`, one session per (role, repo) pair (`sk-<session>`), puts the board card in the first role's lane, queues a `(New Task)` note to the first session, starts `handoffd`, then launches each session's harness in its repo's worktree with `SWARMFORGE_ROLE`, `SWARMKHAZAD_SESSION`, `SWARMKHAZAD_REPO`, `SWARMKHAZAD_TASK_ID`, `SWARMKHAZAD_TASK_DIR` exported and `<task>/bin` plus this repo's `scripts/` on PATH. Each session gets `prompts/<session>.md` (folder rules, mail rules, a stage prompt from `prompts/<role>.prompt` — the stage is the role's, so every repo gets the same instructions and only the header differs) appended to its system prompt. No terminal windows open; attach with `tmux -S <socket> attach -t sk-<session>`.

### Harness shims

**After a reboot, press Resume.** A reboot takes every tmux server and `handoffd` with it — the sockets live under `/tmp`, which macOS clears at boot — while the task folder survives whole, mail queue included: `ready_for_next.bb` re-presents a message already in `inbox/in_process/` rather than treating it as consumed, so a handoff claimed the instant before the power cut is handed back. `open` is the resume, and it is idempotent: same worktrees, same branch, roles rebuilt from `sessions.tsv`, and the board card and opening note skipped when a lane already exists. The task page offers it as a button when nothing is running. What does not come back is a role's half-finished turn — an agent's context lives nowhere on disk, so it restarts from `goal.md`, its inbox and its own draft. Resume is deliberately not a login item: `open` spawns agents, and nothing should start spending money at boot with nobody watching.

A command that NAMES `goal.md` or `metrics.md` is held to an allowlist of readers, so a tool the hook has never heard of is refused rather than assumed safe. `find`, `du`, `tree`, `dig`, `host`, `nslookup` and `date` are on it; `git` is deliberately not, because `git checkout -- goal.md` restores the file from the index and is a write by another name, and neither is `curl` (`-o` writes) or `env` (already skipped as a command prefix, so listing it would launder whatever followed). The refusal names the command it did not recognise — it used to describe an edit attempt whatever you had run, so a role that typed `find .` was told it must not edit goal.md, probed the hook to find out why, and left the confusion in append-only escalation.md.

`open` also records which copy of swarmkhazad opened the task, to `state/runtime.tsv` — script dir, branch, commit, whether that checkout was dirty. Hooks and the daemon are launched with the absolute path of whatever checkout ran `open`, and that is often a development worktree; the daemon then keeps the code it booted with while hooks re-read theirs on every call, so one task can execute two versions. Measured: `handoffd.bb` was rewritten at 07:59, inside a daemon lifetime of 07:26–08:11. This does not pin the code, but it makes the question answerable instead of archaeological — and `open` says so on stderr when the checkout is dirty.

`open` installs `scripts/shim.sh` as `<task>/bin/<harness>` for every known harness and launches roles through them. A shim reads the session from `SWARMKHAZAD_SESSION`, its model vendor from `state/sessions.tsv`, the vendor's row from `state/vendors.tsv` (a copy of `scripts/vendors.tsv`: `vendor base_url keychain_service model_main model_small ctx_tokens` — the one table the shim and the smoke both read), and the real binary from `state/harnesses.tsv` (resolved at `open` from the operator's PATH), then execs the real CLI. For `claude` it applies the vendor's configuration first — `anthropic` (direct; any routing inherited from the operator's shell is dropped), or a `vendors.tsv` row (base URL, keychain token via `security`, model ids, context window, `--model` pin). The OpenRouter rows' model ids carry `@preset/cc-tools`, which pins `provider.ignore: [Z.AI, Novita]` — without it those providers answer a tool-heavy request with a valid EMPTY 200. The preset is a routing directive, not part of the model's identity: the reply comes back labelled with the bare slug, which is why `task-lib/base-model` strips it before the smoke compares — and the telemetry exporter: `CLAUDE_CODE_ENABLE_TELEMETRY=1`, OTLP metrics over `http/protobuf` to `SWARMKHAZAD_OTLP_ENDPOINT` (default `http://127.0.0.1:8428/opentelemetry`, a local VictoriaMetrics), `OTEL_RESOURCE_ATTRIBUTES=task_id=<id>,role=<role>,session=<session>,repo=<repo>`. The vendor's token sits in the role's process environment for the life of the session, where the agent can read it; that is the cc_alt arrangement, not a new exposure.

`open` also pre-accepts Claude Code's folder-trust dialog for every claude role's working directory by adding `projects[<realpath>].hasTrustDialogAccepted: true` to `~/.claude.json` (`SWARMKHAZAD_CLAUDE_JSON` overrides the path); `close` removes exactly those entries. Nothing else in that file is touched. Without it a fresh worktree stops at the dialog and hooks never run.

### The contract, enforced

Every claude role loads `<task>/hooks/<role>.settings.json` (via `--settings`), which wires `scripts/hooks/run-contract.sh` on `SessionStart` and on every file or shell tool. At `open` the truth is locked (`chmod 444 goal.md metrics.md`). On session start, resume and compact the hook re-locks it and injects the contract, `goal.md` and `metrics.md` verbatim, the bullets so far and the role's own `draft-<role>.md`, so a role that has lost its context gets the truth back without being told to re-read anything. Before any Edit/Write on those two files, or any Bash command that names them (or the task folder itself) with a mutating verb or as a redirect target, the hook denies with a reason that says what to do instead; reads pass. Denials are appended to `state/denials.jsonl`.

Roles report by appending one-line bullets to `decision.md`, `gotcha.md`, `escalation.md` and by writing `draft-<session>.md` (the prompts name it for them); a git handoff's `artifacts:` names that draft. Every bullet goes through `note.bb` — the hook denies a role writing those four files directly, and the tool's own writers (the goal judge, the PR poll) call it too rather than appending themselves, so the `[repo]` tag and the bullet format are one implementation and not a convention each writer has to remember. It appends with `O_APPEND` and takes no lock: one short line is atomic, and a lock here would be ceremony.

Those files are append-only and the roles own them, so a bullet that turns out to be wrong can only be followed by another one withdrawing it — one escalation.md opened with `- [superset] **probe** — probe`, then an entry whose whole content was *ignore the line above*, then two more retracting a third. `note.bb resolved '<match>' '<how>'` and `note.bb retract '<match>' '<why>'` record that instead: same cross-off store the portal's tick writes, the why landing in `finding.md` for a met ask and `decision.md` for a withdrawn one. **The summary reads that store too**, and marks a crossed-off bullet rather than weighing it as an open ask — nothing is deleted, because a retraction is itself a fact about the run.

### The goal judge

`scripts/goal_judge.bb` is khazad's `GoalJudgeModel`, and it runs **at the handoff boundary**. When `swarm_handoff.bb` sends a `git_handoff` from a claude session it calls `goal_judge.bb --grade <session>`, which summarises that session's committed state (git status, commits and diff since the base, `draft-<session>.md`, every file under `evidence/`) and grades it against `goal.md` with a cheap model through the session's own shim environment: `claude -p --model haiku --json-schema {met, unmet}`, thinking off, output bounded, no tools, no MCP.

A session is graded on **its own goal lines only**: `- [ ] <role> @<repo> — <outcome>` decides ownership, a line naming neither belongs to everyone, and the rest go to the model under a heading saying they are not this session's to grade. Graded whole instead, every role of a multi-role task is unmet until the last one finishes — and a role spanning three repos gets one "partially met" verdict for work the judge can only see a third of.

That heading is not enough on its own, so the partition is also **enforced on the way out**. A cheap model does grade the lines it was told not to: `review_superset` came back unmet on `implement @gobel — … URL repoint to .com …`, a line describing a change its own goal line calls a send-back and which it could not make from its own repo. So an `unmet` item that opens with another role's name is dropped, recorded on the verdict as `dropped-unmet`, and `met` becomes true only when *every* reason given belonged to someone else. Only that unambiguous case moves — a paraphrase naming no role survives, because dropping a real unmet item would turn a block into a pass, and the escalation line the verdict writes is append-only.

The verdict lands in `state/judge/<session>.json`. Unmet refuses the handoff and names the items; after `max-refusals` (3) the handoff goes through carrying `unmet:` in its headers, because refusing forever wedges every later role on one that cannot meet its bar. A successful handoff clears the count. A judge that fails — process error, timeout, no JSON, or a crash in the judge process itself — yields `met=false, unmet=[judge_unavailable]` and the handoff is refused like any other gap: an infra fault delays the work, it never passes it. The previous verdict on disk is never reused as a fallback; it was a grading of different work. Every distinct unmet verdict appends one line to `escalation.md`.

Grading used to run on every `Stop`. Measured on a real task: the same role graded four times in one turn, three escalation lines saying the same thing, and a verdict that flipped UNMET → MET on a tree with no commit and no evidence write in between. What is left on `Stop` is a **nudge**, and nothing about goals: if a session has committed work and no `git_handoff` for that HEAD, the turn is held open (at most twice per turn) to ask for one. That is the single failure a stopping role cannot see about itself — it finishes, stops, and the board freezes with nobody watching. A worktree git cannot answer for counts as unsent work: a spurious nudge costs a line in a pane, a missed one costs the task.

### Run-stage evidence

The `run` role does not judge; it measures. `run_evidence.bb`, run from its worktree, executes the repo's own test command (detected from the worktree: `package.json` → `npm test`, or the lockfile's manager running that same script — `pnpm`/`yarn`/`bun run test`, never `bun test`, which is Bun's own runner and fails a Vitest suite outright; `bb.edn` → `bb test`, `pyproject.toml` → `pytest`, `go.mod`, `Cargo.toml`, a `Makefile` `test:` target) and every `measure:` command in `metrics.md`'s Quantitative section (`- <name> — bar: <threshold> — measure: \`<command>\``; `<id>` and `<task-id>` in a command become the task id, `<task-dir>` the task folder). Each lands as `evidence/<bar>.txt` with the command, cwd, threshold, start time, duration, exit code and the interleaved output (clipped at 64 KiB; a command past the 20-minute timeout records exit 124). The goal judge reads every file in `evidence/` when it grades, so a bar is met when its file says so. (`SWARMKHAZAD_RUN_REMOTE` was the seam for a later remote runner and used to refuse to run while set. The runner exists, a bar opts in per line, and the variable now only prints a notice saying so.)

There are **two bar sources**, both in the same grammar. `metrics.md` is the task's contract and is `chmod 444` from the moment the swarm opens, so a role cannot author a bar for its own task — which is the point. `repro.md`, when it exists, is the one bar a role IS allowed to write, and it exists for the investigation lane: naming the command that reproduces a bug is the investigator's deliverable and cannot be known when the task is written. Names are uniqued across both files, so a repro bar sharing a name with a metrics bar gets `<bar>-2.txt` rather than overwriting the other's evidence.

A bar whose `measure:` opens with **`@cloud`** is service-level and does not run on the laptop. `run_evidence.bb` dispatches it to the self-hosted environment the project names (`SWARMKHAZAD_CLOUD_ENV` overrides it once), and records `exit: pending` — never 0, because a bar is not met by having been sent somewhere. The dispatch is one-way: there is no read-back from the CLI, and `--teleport` migrates the session onto this machine, which is the one thing a cloud tier exists to prevent. So the runner reports by **commenting**, and the bar says where: `— ticket: <KEY>` sends it to a Linear ticket, and with no ticket it needs an open PR for the branch. The PR was never a precondition for execution — it is the return channel — which is exactly why a ticket can stand in for it. A bar may also name its own `— origin:` and `— branch:`, which win over the worktree's; the investigation lane reproduces on `main` from a task branch that carries no code, and deriving the target from the checkout would verify the one branch certain not to have the bug. A dispatch with no environment, an origin outside `MithraAI`, or no return channel at all records `exit: blocked` and says which.

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

**Only people with write access wake a role.** A queued handoff is delivered into a session's pane and read as its instructions, and that session runs with permissions bypassed in your own checkout with `gh` authenticated — so a PR comment is a prompt, and on a public repo anyone can write one. GitHub already answers the question that settles it: only `OWNER`, `MEMBER` and `COLLABORATOR` comments become work. Everything else becomes one line in `escalation.md` naming the author and the PR, never the body, and a human reads it there. What does get through is wrapped in `BEGIN UNTRUSTED TEXT` / `END UNTRUSTED TEXT` with those markers stripped from the body first, so a comment cannot close its own fence, and the constitution tells every role that anything between them is data.

Three things it refuses to do. A check that has failed on two commits in a row — the role pushed a fix and it failed again — stops waking anyone and becomes an escalation instead, because a pipeline broken for a reason nobody in the task can fix would otherwise spin a role for as long as it stays broken. A poll that cannot reach GitHub does nothing at all rather than reading silence as "no comments", which would mark every one of them handled and lose them. And it never settles a task on one repo's PR: the same poll records each PR's `state`, and only when the last one is `MERGED` or `CLOSED` does the card leave `in-review` for `done`.

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

## The investigation lane

A Linear ticket with no `Evidence` label becomes a two-pane task: one pane thinks, one pane dispatches a reproduction. The ticket is the only shared surface, and two mutually-exclusive Linear label groups carry the state.

```
loop session       investigate (opus)      run (haiku)      cloud runner     Linear
     │                     │                    │                 │            │
     │─ poll: no Evidence group ─────────────────────────────────────────────▶ │
     │◀ ticket + repo: label ───────────────────────────────────────────────── │
     │─ MCP add_task{key} ─▶  open: both panes spawn                           │
     │                     │ 3–5 hypotheses, telemetry MCPs DENIED             │
     │                     │─ testers (sonnet) ─▶ REBUTTED / CONFIRMED         │
     │                     │─ note.bb ─▶ finding.md                            │
     │                     │─ RCA comment + Evidence: found│none ────────────▶ │
     │                     │─ repro.md + handoff ─▶                            │
     │                       Evidence: none  ─▶ │─ Repro: no (reason) ───────▶ │
     │                       Evidence: found ─▶ │─ dispatch @cloud ─▶│         │
     │                                          │  exit: pending, task closes  │
     │                                                               │─ comment ─▶
     │◀ poll: runner comment ───────────────────────────────────────────────── │
     │─ Repro: yes│no ──────────────────────────────────────────────────────▶ │
```

Linear enforces exclusivity inside a label group, so a role **cannot** set both outcomes of a phase. Absence of a group means that phase never ran, which is how a crashed lane is told apart from a negative result — and it is why groups were chosen over flat labels.

| group | labels | question answered |
|---|---|---|
| `Evidence` | `Evidence: found` / `Evidence: none` | did a hypothesis survive with evidence |
| `Repro` | `Repro: yes` / `Repro: no` | did the named command reproduce it |

| Evidence | Repro | means | the loop does |
|---|---|---|---|
| — | — | not investigated | open a lane |
| found | — | dispatched, waiting | read the runner's comment |
| found | yes | real, reproducible | leave it — the fix task does not exist yet, so this state IS the queue |
| found | no | real, the swarm cannot verify it | surface to Allen |
| none | no | no cause found | terminal |

**Two properties the whole design turns on.** Reproduction is the anti-flake gate — a ticket is worth acting on when the behaviour it describes can be made to happen again. And **every label comes from a runner-produced artifact, never a role's claim**: the investigator NAMES the repro command, the runner RECORDS the outcome, and a third party (the loop session) writes the label. No role sets both halves of that, in any code path.

**The roles.** `--investigate` writes this lineup:

```
investigate claude task --model opus --disallowedTools mcp__logfire__*,mcp__datadog-mcp__*,mcp__argocd__*
run claude task --model haiku
```

The model goes on the argv because `model=` names a VENDOR — a base URL and a keychain service — so `model=opus` fails at prepare with `unknown model vendor opus`. And the tool list carries **no quotes**: a `roles` line is whitespace-split and each token becomes one argv slot, so `--disallowedTools "a,b"` reaches the CLI as the literal token `"a,b"` and denies nothing.

Denying the parent its telemetry is deliberate and is stronger than khazad, which grants the parent everything. The investigator then cannot gather confirming evidence for its own favourite story — it has to dispatch the `swarmkhazad:investigation-hypothesis-tester` subagent, which holds `logfire`/`datadog`/`argocd` and was instructed to refute. Partial, and knowingly so: `gh` is a CLI, so `Bash` routes around any MCP denial. The tester has no write tools at all, so the parent calling `note.bb` per returned verdict is the only route by which a clue reaches `finding.md`.

**The skill and the subagent are generated per task as a plugin, and NAMED rather than copied.** `plugin/` in this repo is a Claude Code plugin — a `.claude-plugin/plugin.json` manifest beside `agents/` and `skills/` — and `prepare` copies it to `<task>/plugin/`. Every claude role's argv then carries `--plugin-dir <task>/plugin`, and the two load under the plugin's name: `swarmkhazad:investigate` and `swarmkhazad:investigation-hypothesis-tester`.

Only the `claude` harness and the lanes get the flag. codex, copilot and grok have no equivalent — and lose nothing, because `.claude/agents/` and `.claude/skills/` were never load paths they read either.

It gets its own directory rather than sharing the task folder's, because a plugin root is executable surface read at names the task folder already uses: `hooks/hooks.json` runs shell commands on every tool event (and a manifest's own hooks field is read *in addition to* that path, so declaring one cannot suppress it), `.mcp.json` registers servers under a `plugin:<name>:<server>` id that a role's `--disallowedTools` patterns do not match, and `commands/` is a third. `<task>/hooks/` is already where each session's `--settings` file is written. No collision was reachable through the generated files — that suffix is appended literally, so a session settings file can never be named `hooks.json`, and the loader takes that path by name rather than scanning — but a generated directory and a reserved plugin directory being the same directory is the problem, not the filename. For the same reason `install-plugin!` DELETES the directory before copying: `copy-tree` overwrites what it ships and removes nothing, so re-preparing restored a tampered `SKILL.md` while leaving anything else added beside it untouched.

Naming rather than copying is the whole point. A worktree lives inside the target repo, so the copy that used to go there wrote into lothlorien, minas-tirith and istari — and then needed `info/exclude` entries in those repos so a role running `git add -A` could not commit the scaffolding. Now swarmkhazad writes nothing into them: the only path it still excludes in a checkout it does not own is the codegraph index. Per task, not centralised — `--plugin-dir` is per-session and repeatable, so one task can carry a different skill from the one beside it.

RAN, and it is why naming suffices where a parent directory's `.claude/` did not: a spec at `<task>/.claude/agents/x.md` with cwd `<task>/worktrees/foo` was absent from the session's subagent list, while from a cwd holding no `.claude` at all, `claude -p --plugin-dir <task-dir>` listed both `swarmkhazad:investigation-hypothesis-tester` and `swarmkhazad:investigate`.

**The loop session.** One long-lived `cc_loop` session, its prompt in `prompts/investigation-loop.prompt` — poll query, the `repo:` label → checkout mapping, the attempt cap, and how `Repro:` is read off the runner's comment. It reaches swarmkhazad through one MCP tool:

```
swarmkhazad mcp        # JSON-RPC 2.0 on stdio, one tool: add_task{issue_key, repo, investigate}
```

One tool, not five. `close`, `paths` and the rest are reachable from the terminal and the portal already, and every one added there is another thing an unattended session can do at 3am. `add_task` does no work of its own — it runs `swarmkhazad open --linear <KEY>`, the same path a human uses, so the ticket arrives through the verbatim fetch above and `goal.md` is provably the issue's own text rather than a session's paraphrase. It validates the issue key before that string becomes an argv element and then a task id.

`repo` is required in practice. A repo-less task is refused (RAN: an empty `repos` file exits 1 with `repos declaration is empty`; no `repos` file exits 1 with `task is missing repos`), because every role's session is opened in a worktree — so the ticket's `repo:` label has to map to a real checkout.

**Attempt cap: two, then `Blocked`.** A crash leaves NO `Evidence` label, which is exactly the state the poll selects for, so without the cap a ticket whose investigate pane dies on launch is re-opened forever. The loop counts its own one-line attempt comments on the ticket, and reuses the workspace's existing `swarm-created` label rather than minting a parallel marker.

Not in scope, deliberately: the **fix-task scaffolding** — `Evidence: found` + `Repro: yes` terminates in a queue rather than an action — and **creating the two label groups**, which is a manual step in the Linear UI.

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

Two gates sit beside the suite, because a green suite makes a weaker claim than it looks like it makes.

**`bb mutate`** breaks one guard at a time and requires the named test to notice. The table is `test/mutants.edn` — one entry per guard, each carrying the exact code that implements it and the `deftest` that failed when it was taken away. That last field is the point: "3/3 killed" hides the mutant nobody wrote, and a mutant that dies in a *different* test proves that test rather than this one, so the runner reports `wrong-test` separately from `survived`.

```
bb mutate                        # the whole table (~15 min; every mutant runs its namespace)
bb mutate contract               # one namespace
bb mutate --changed origin/main  # only mutants the branch could have affected
bb mutate --list                 # the table, run nothing
```

An entry whose `:old` no longer matches fails as `anchor-gone` rather than being skipped — a guard that moved is exactly when you want to be told. The runner edits tracked files and restores each in a `finally`; a hard kill can strand a mutant, so `git diff` is the check, and CI asserts a clean tree after.

**`bb test schema`** is the brittleness gate, and it is a count rather than a coverage number: how many file formats are written down more than once. Every TSV here is written by one function and parsed by another, and the column order is the contract between them — so it is a named def (`sessions-tsv-columns`, `tasks-tsv-columns`) that both ends map over, never a literal in both places. `board_lib` had the same five keys spelled out twice six lines apart, and `zipmap` fails silently: it drops a column the writer added and pads a dropped one with nil, so a card reads back with the wrong lane and nothing errors. The count is at zero.

There is no coverage number and no CRAP score. Both need the coverage half, which needs a JVM Clojure build — this repo is babashka only, with no `deps.edn` and no classpath, and buying a build system to compute a number nobody would act on is the wrong trade.
