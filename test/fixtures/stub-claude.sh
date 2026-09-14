#!/bin/bash
# A stand-in for the `claude` binary: records how it was launched, then plays
# one role's part of a two-role pipeline through the real mail helpers.
#
#   a  waits for the New Task note, completes it, commits a file, git_handoff → b
#   b  waits for a's git_handoff, commits, git_handoff → a
#      (b is the last session, so that is the terminal broadcast)
set -u
T="$SWARMKHAZAD_TASK_DIR"
# The session, not the role: past one repo a role has several sessions, and
# keying these files by role would have two of them writing the same argv file.
# In a one-repo task the two names are the same string.
R="${SWARMKHAZAD_SESSION:-${SWARMFORGE_ROLE:-}}"
[ -n "$R" ] || { echo "stub-claude: neither SWARMKHAZAD_SESSION nor SWARMFORGE_ROLE is set" >&2; exit 1; }

# `--json-schema` = the goal judge asking for a verdict (a nested call from inside
# the role's own session, so it must not overwrite the launch record below).
# SWARMKHAZAD_STUB_VERDICT is the JSON to return as structured_output; `down`
# makes the judge fail.
if printf ' %s ' "$@" | grep -q ' --json-schema '; then
  case "${SWARMKHAZAD_STUB_VERDICT:-}" in
    down) echo "stub judge is down" >&2; exit 1 ;;
    garbage) echo "not json at all"; exit 0 ;;
    "") verdict='{"met":true,"unmet":[]}' ;;
    *) verdict="$SWARMKHAZAD_STUB_VERDICT" ;;
  esac
  printf '%s\n' "$@" > "$T/tmp/judge-$R.argv"
  printf '{"type":"result","is_error":false,"num_turns":1,"total_cost_usd":0.02,"structured_output":%s,"modelUsage":{"claude-haiku-stub":{}}}\n' "$verdict"
  exit 0
fi

printf '%s\n' "$@" > "$T/tmp/launch-$R.argv"

# The Parseemailfix shape: a harness that records its launch and then dies,
# leaving a live tmux session around a shell prompt. Every surface reported
# health for eight of these.
if [ -n "${SWARMKHAZAD_STUB_DIE:-}" ]; then
  echo "stub-claude: dying at launch, as a shim exit 127 does" >&2
  exit 127
fi
env | grep -E '^(ANTHROPIC_|OTEL_|CLAUDE_CODE_|API_TIMEOUT)' | sort > "$T/tmp/launch-$R.env"

# `-p` = the smoke: behave like a print-mode session that read goal.md and sent
# the note the prompt asked for, then report a JSON result naming a model.
if printf ' %s ' "$@" | grep -q ' -p '; then
  to=$(printf '%s\n' "$@" | sed -n 's/^to: //p' | head -1)
  printf 'type: note\nto: %s\npriority: 50\nmessage: smoke from %s\n' "$to" "$R" > "$T/tmp/smoke-$R.txt"
  swarm_handoff.bb "$T/tmp/smoke-$R.txt" > "$T/tmp/smoke-$R.out" 2>&1 || { cat "$T/tmp/smoke-$R.out" >&2; exit 1; }
  model="${SWARMKHAZAD_STUB_MODEL:-${ANTHROPIC_DEFAULT_OPUS_MODEL:-claude-stub}}"
  # OpenRouter answers a `<slug>@preset/<name>` request labelled with the SLUG
  # alone (RAN 2026-09-09), so a real session's modelUsage key never carries the
  # preset. Echoing the env var verbatim made the fixture the only place in the
  # world where it does, which hid a smoke that would have failed every vendor
  # role.
  model="${model%%@preset/*}"
  printf '{"type":"result","is_error":false,"num_turns":3,"total_cost_usd":0.01,"result":"HANDOFF_OK","modelUsage":{"%s":{}}}\n' "$model"
  exit 0
fi

# A real harness owns its terminal for as long as it runs and reads what is
# typed into it; that is the whole point of the wake-up. This stub used to
# ignore stdin, so a nudge typed into its pane sat unread in the tty buffer
# until the script exited, and bash then ran it as a command — the fixture
# reproduced the bug it stands in for, and a passing run left `command not
# found` in the pane just like a failing one.
#
# Read in the FOREGROUND. A `( while read ... ) &` drainer looks tidier and does
# nothing at all: a background process group reading the terminal takes SIGTTIN
# and stops, so the file stays empty and the text still falls through to bash
# (RAN — that was the first attempt at this).
#
# Twice, because this role usually finds its mail on the first poll and the
# nudge lands after that: once inside the wait loop, and once at the end, so a
# line typed during the role's own work is still consumed by the process rather
# than inherited by the shell. What it reads goes to a file, the only artifact
# that can say WHO took the line — pane scrollback shows the text either way.
: > "$T/tmp/wake-$R.txt"
drain() {
  while read -r -t "${1:-1}" typed; do
    printf '%s\n' "$typed" >> "$T/tmp/wake-$R.txt"
  done
  return 0
}

wait_task() {
  for _ in $(seq 90); do
    out=$(ready_for_next.bb 2>&1)
    if printf '%s' "$out" | grep -q '^TASK:'; then printf '%s\n' "$out"; return 0; fi
    drain 1
  done
  echo "NO TASK ARRIVED for $R: $out" >&2
  return 1
}

case "$R" in
  a)
    wait_task > "$T/tmp/a-inbound.txt" || exit 1
    done_with_current.bb > "$T/tmp/a-done.txt" 2>&1 || exit 1
    echo "from a" > a.txt && git add a.txt && git commit -q -m "a: add a.txt

By a." || exit 1
    # No turn to end first: swarm_handoff.bb grades the committed tree itself.
    printf 'type: git_handoff\nto: b\npriority: 50\n' > "$T/tmp/a-draft.txt"
    swarm_handoff.bb "$T/tmp/a-draft.txt" > "$T/tmp/a-handoff.txt" 2>&1 || { cat "$T/tmp/a-handoff.txt" >&2; exit 1; }
    ;;
  b)
    wait_task > "$T/tmp/b-inbound.txt" || exit 1
    # Sharing the repo's worktree with a, so a's commit is simply on the branch.
    [ -f a.txt ] || { echo "a.txt is not in the shared worktree" >&2; exit 1; }
    echo "from b" > b.txt && git add b.txt && git commit -q -m "b: add b.txt

By b." || exit 1
    printf 'type: git_handoff\nto: a\npriority: 50\n' > "$T/tmp/b-draft.txt"
    swarm_handoff.bb "$T/tmp/b-draft.txt" > "$T/tmp/b-handoff.txt" 2>&1 || { cat "$T/tmp/b-handoff.txt" >&2; exit 1; }
    ;;
esac
# The wake usually arrives while the role is doing its work above, not while it
# waits. Consume it before exiting, or the shell that regains the pane runs it.
drain 3
echo "STUB DONE $R" > "$T/tmp/done-$R"
