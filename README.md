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
swarmkhazad paths <task-id>                    print the path map
```

## Tests

```
bb test            # everything
bb test task       # namespaces matching a substring
```
