# swarmkhazad

A local, goal-driven swarm: SwarmForge's runtime, the `.claude` goal contract, and khazad's goal judge, in one Babashka repo.

One runtime dependency: [Babashka](https://babashka.org) (`bb`). `git` and `tmux` are assumed present.

## Where things live

Runtime home is `~/.swarmkhazad/` (override with `SWARMKHAZAD_HOME`). Nothing is written under `~/repos/` or `~/.claude/`.

```
~/.swarmkhazad/tasks/<task-id>/
  goal.md  metrics.md          the truth: what done means and how it is measured (locked 444)
  roles                        the declaration, one line per role (grammar below)
  decision.md gotcha.md escalation.md   roles append one bullet per line
  draft-<role>.md              a role's full write-up
  evidence/<bar>.txt           the run role's measurements
  repos/<name>/                clone of the target repo, objects hardlinked from the local checkout
  worktrees/<role>/            one worktree per role, branch sk/<task-id>/<role>, off repos/<name>
  mail/<role>/                 outbox/ sent/ failed/ inbox/{new,in_process,completed}
  bin/                         per-role harness shims (claude, codex, grok)
  prompts/ hooks/              generated per launch
  state/                       roles.tsv (spawn-time snapshot of `roles`), tmux socket, board/, daemon/, sessions/, judge/
  tmp/                         scratch; handoff drafts live here, never in the repo
```

Each stage creates the directories it fills; `prepare` creates `repos/ worktrees/ mail/ state/ tmp/` and the three bullet files.

### The `roles` declaration

```
<role> <harness> <repo-path|none> [task|batch] [model=<vendor>] [cli args...]
```

`role` is `[A-Za-z0-9][A-Za-z0-9.-]*` (it is a path component and a refname segment). `harness` is `claude|codex|copilot|grok`. `repo-path` is a local git checkout (a linked worktree is fine, a shallow clone is not); `none` means the role works in the task folder. The two optional tokens may appear in either order; anything else is passed to the harness CLI verbatim. Two checkouts with the same basename cannot share a task — they would share one clone.

`state/roles.tsv` is the snapshot `prepare` writes, one row per role, columns `role harness repo worktree-path receive-mode model extra-args`; a repo-less role says `none`.

### The clone

The clone is pinned to the source checkout's `origin/<default>` at open time (`origin/HEAD`'s target, else `main`, else the source's own branch), its `origin` is repointed at the source's upstream URL — or removed when the source has none — and every other ref and local branch is dropped. After that the source is never read again.

## Commands

```
swarmkhazad new <task-id> [--repo <path>]...   scaffold goal.md, metrics.md, roles
swarmkhazad prepare <task-id>                  layout, clones, worktrees, mail dirs, roles.tsv — no agents yet
swarmkhazad open <task-id>                     prepare, then spawn exactly the declared roles
swarmkhazad close <task-id>                    archive panes, stop the daemon, kill the tmux server
swarmkhazad paths <task-id>                    print the path map
```

## The swarm

`open` starts one tmux server per task on `/tmp/swarmkhazad-<user>/<task-id>.sock`, one session per role (`sk-<role>`), puts the board card in the first role's lane, queues a `(New Task)` note to that role, starts `handoffd`, then launches each role's harness in its worktree with `SWARMFORGE_ROLE`, `SWARMKHAZAD_TASK_ID`, `SWARMKHAZAD_TASK_DIR` exported and `<task>/bin` plus this repo's `scripts/` on PATH. Each role gets `prompts/<role>.md` (folder rules, mail rules, a stage prompt from `prompts/<role>.prompt`) appended to its system prompt. No terminal windows open; attach with `tmux -S <socket> attach -t sk-<role>`.

### Harness shims

`open` installs `scripts/shim.sh` as `<task>/bin/<harness>` for every known harness and launches roles through them. A shim reads the role from `SWARMFORGE_ROLE`, the role's model vendor from `state/roles.tsv`, the vendor's row from `state/vendors.tsv` (a copy of `scripts/vendors.tsv`: `vendor base_url keychain_service model_main model_small ctx_tokens` — the one table the shim and the smoke both read), and the real binary from `state/harnesses.tsv` (resolved at `open` from the operator's PATH), then execs the real CLI. For `claude` it applies the vendor's configuration first — `anthropic` (direct; any routing inherited from the operator's shell is dropped), or a `vendors.tsv` row (base URL, keychain token via `security`, model ids, context window, `--model` pin) — and the telemetry exporter: `CLAUDE_CODE_ENABLE_TELEMETRY=1`, OTLP metrics over `http/protobuf` to `SWARMKHAZAD_OTLP_ENDPOINT` (default `http://127.0.0.1:8428/opentelemetry`, a local VictoriaMetrics), `OTEL_RESOURCE_ATTRIBUTES=task_id=<id>,role=<role>`. The vendor's token sits in the role's process environment for the life of the session, where the agent can read it; that is the cc_alt arrangement, not a new exposure.

`open` also pre-accepts Claude Code's folder-trust dialog for every claude role's working directory by adding `projects[<realpath>].hasTrustDialogAccepted: true` to `~/.claude.json` (`SWARMKHAZAD_CLAUDE_JSON` overrides the path); `close` removes exactly those entries. Nothing else in that file is touched. Without it a fresh worktree stops at the dialog and hooks never run.

### The contract, enforced

Every claude role loads `<task>/hooks/<role>.settings.json` (via `--settings`), which wires `scripts/hooks/run-contract.sh` on `SessionStart` and on every file or shell tool. At `open` the truth is locked (`chmod 444 goal.md metrics.md`). On session start, resume and compact the hook re-locks it and injects the contract, `goal.md` and `metrics.md` verbatim, the bullets so far and the role's own `draft-<role>.md`, so a role that has lost its context gets the truth back without being told to re-read anything. Before any Edit/Write on those two files, or any Bash command that names them (or the task folder itself) with a mutating verb or as a redirect target, the hook denies with a reason that says what to do instead; reads pass. Denials are appended to `state/denials.jsonl`.

Roles report by appending one-line bullets to `decision.md`, `gotcha.md`, `escalation.md` and by writing `draft-<role>.md`; a git handoff's `artifacts:` names that draft.

### The goal judge

The same settings file wires `scripts/goal_judge.bb` on `Stop` — khazad's `GoalJudgeModel` as a hook. When a claude role ends a turn, the hook summarises the role's working state (git status, commits and diff since the pinned base, `draft-<role>.md`, every file under `evidence/`, the last message) and grades it against `goal.md` with a cheap model through the role's own shim environment: `claude -p --model haiku --json-schema {met, unmet}`, thinking off, output bounded, no tools, no MCP. The verdict lands in `state/judge/<role>.json` and drives khazad's `decide_stop`: unmet blocks the stop with the items named (three blocks per session, then the stop is allowed but the verdict stands); met without a git_handoff for the current HEAD blocks once more to ask for it; met and handed off allows; a terminal broadcast in the inbox allows. A judge that fails — process error, timeout, no JSON — yields `met=false, unmet=[judge_unavailable]`: the stop is allowed so an infra fault cannot wedge the role, but nothing passes. `swarm_handoff.bb` refuses a `git_handoff` from a claude role unless the latest verdict says met. Every distinct unmet verdict appends one line to `escalation.md`.

### Run-stage evidence

The `run` role does not judge; it measures. `run_evidence.bb`, run from its worktree, executes the repo's own test command (detected from the worktree: `package.json` → `npm`/`pnpm`/`yarn`/`bun test`, `bb.edn` → `bb test`, `pyproject.toml` → `pytest`, `go.mod`, `Cargo.toml`, a `Makefile` `test:` target) and every `measure:` command in `metrics.md`'s Quantitative section (`- <name> — bar: <threshold> — measure: \`<command>\``; `<id>` and `<task-id>` in a command become the task id, `<task-dir>` the task folder). Each lands as `evidence/<bar>.txt` with the command, cwd, threshold, start time, duration, exit code and the interleaved output (clipped at 64 KiB; a command past the 20-minute timeout records exit 124). The goal judge reads every file in `evidence/` at each Stop, so a bar is met when its file says so. Local only: `SWARMKHAZAD_RUN_REMOTE` is the seam for a later remote runner and refuses to run while set.

`swarmkhazad smoke <task-id>` proves each declared role callable without a swarm: in parallel, it runs each role's shim in print mode with the role's prompt, asking it to read `goal.md`, write a note draft addressed to itself and run `swarm_handoff.bb`; it reports per role the exit code, whether the note reached the outbox, the model the run reported (checked against the vendor's pin — another model is a collision, not a pass), cost and turns. The smoke notes are removed afterwards.

Files are the transport, tmux carries only the wake-up. A role writes a four-line draft under `<task>/tmp/` and runs `swarm_handoff.bb <draft>`; the helper fills the commit (worktree HEAD) and artifacts, installs the handoff in the role's `mail/<role>/outbox/`; `handoffd` copies it into each recipient's `inbox/new/`, moves the card, and types a wake-up into the recipient's pane. Recipients run `ready_for_next.bb` (which merges a git_handoff's commit by bare SHA — every worktree shares the clone's object store) and `done_with_current.bb`. The last role's git_handoff is the terminal broadcast: marked `non-forwarding`, recipients merge and stop, the card goes to `done`.

## Tests

```
bb test            # everything
bb test task       # namespaces matching a substring
```
