#!/bin/bash
# A stand-in for the `claude` binary: records how it was launched, then plays
# one role's part of a two-role pipeline through the real mail helpers.
#
#   a  waits for the New Task note, completes it, commits a file, git_handoff → b
#   b  waits for a's git_handoff (ready_for_next merges it), commits, git_handoff → a
#      (b is the last role, so that is the terminal broadcast)
set -u
T="$SWARMKHAZAD_TASK_DIR"
R="$SWARMFORGE_ROLE"
printf '%s\n' "$@" > "$T/tmp/launch-$R.argv"
env | grep -E '^(ANTHROPIC_|OTEL_|CLAUDE_CODE_|API_TIMEOUT)' | sort > "$T/tmp/launch-$R.env"

# `-p` = the smoke: behave like a print-mode session that read goal.md and sent
# the note the prompt asked for, then report a JSON result naming a model.
if printf ' %s ' "$@" | grep -q ' -p '; then
  to=$(printf '%s\n' "$@" | sed -n 's/^to: //p' | head -1)
  printf 'type: note\nto: %s\npriority: 50\nmessage: smoke from %s\n' "$to" "$R" > "$T/tmp/smoke-$R.txt"
  swarm_handoff.bb "$T/tmp/smoke-$R.txt" > "$T/tmp/smoke-$R.out" 2>&1 || { cat "$T/tmp/smoke-$R.out" >&2; exit 1; }
  model="${SWARMKHAZAD_STUB_MODEL:-${ANTHROPIC_DEFAULT_OPUS_MODEL:-claude-stub}}"
  printf '{"type":"result","is_error":false,"num_turns":3,"total_cost_usd":0.01,"result":"HANDOFF_OK","modelUsage":{"%s":{}}}\n' "$model"
  exit 0
fi

wait_task() {
  for _ in $(seq 90); do
    out=$(ready_for_next.bb 2>&1)
    if printf '%s' "$out" | grep -q '^TASK:'; then printf '%s\n' "$out"; return 0; fi
    sleep 1
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
    printf 'type: git_handoff\nto: b\npriority: 50\n' > "$T/tmp/a-draft.txt"
    swarm_handoff.bb "$T/tmp/a-draft.txt" > "$T/tmp/a-handoff.txt" 2>&1 || { cat "$T/tmp/a-handoff.txt" >&2; exit 1; }
    ;;
  b)
    wait_task > "$T/tmp/b-inbound.txt" || exit 1
    [ -f a.txt ] || { echo "a.txt not merged into b" >&2; exit 1; }
    echo "from b" > b.txt && git add b.txt && git commit -q -m "b: add b.txt

By b." || exit 1
    printf 'type: git_handoff\nto: a\npriority: 50\n' > "$T/tmp/b-draft.txt"
    swarm_handoff.bb "$T/tmp/b-draft.txt" > "$T/tmp/b-handoff.txt" 2>&1 || { cat "$T/tmp/b-handoff.txt" >&2; exit 1; }
    ;;
esac
echo "STUB DONE $R" > "$T/tmp/done-$R"
