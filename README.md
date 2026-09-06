# swarmkhazad

A local, goal-driven swarm: SwarmForge's runtime, the `.claude` goal contract, and khazad's goal judge, in one Babashka repo.

One runtime dependency: [Babashka](https://babashka.org) (`bb`). `git` and `tmux` are assumed present.

## Where things live

Runtime home is `~/.swarmkhazad/` (override with `SWARMKHAZAD_HOME`). Nothing is written under `~/repos/` or `~/.claude/`.

```
~/.swarmkhazad/tasks/<task-id>/
  goal.md  metrics.md          the truth: what done means and how it is measured (locked 444)
  roles                        one line per role: <role> <harness> <repo-path|none> [task|batch] [model=<vendor>] [cli args...]
  decision.md gotcha.md escalation.md   roles append one bullet per line
  draft-<role>.md              a role's full write-up
  evidence/<bar>.txt           the run role's measurements
  repos/<name>/                clone of the target repo, objects hardlinked from the local checkout
  worktrees/<role>/            one worktree per role, branch sk/<task-id>/<role>, off repos/<name>
  mail/<role>/                 outbox/ sent/ failed/ inbox/{new,in_process,completed}
  bin/                         per-role harness shims (claude, codex, grok)
  prompts/ hooks/              generated per launch
  state/                       roles.tsv, tmux-socket, board/, daemon/, sessions/, judge/
  tmp/                         scratch; handoff drafts live here, never in the repo
```

The clone is pinned to the source checkout's `origin/main` at open time, its `origin` is repointed at the source's upstream URL, and every other ref is dropped. After that the source is never read again.

## Commands

```
swarmkhazad new <task-id> [--repo <path>]...   scaffold goal.md, metrics.md, roles
swarmkhazad prepare <task-id>                  layout, clones, worktrees, mail dirs, roles.tsv — no agents yet
swarmkhazad paths <task-id>                    print the path map
swarmkhazad tasks                              list task ids
```

## Tests

```
bb test            # everything
bb test task       # namespaces matching a substring
```
