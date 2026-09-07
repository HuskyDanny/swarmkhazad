#!/usr/bin/env bash
# run-contract — the task-folder contract, enforced by the harness instead of
# remembered by the role. Ported from ~/.claude/hooks/run-contract.sh (the
# cc_auto lane's hook) and repointed at SWARMKHAZAD_TASK_DIR.
#
# Wired per role through <task>/hooks/<role>.settings.json (claude --settings),
# and dispatches on the hook event it was called for:
#
#   SessionStart  fires on startup, resume AND compact, so a role that has lost
#                 its context gets the contract back without being told to
#                 re-read anything. Applies the lock (chmod 444 on goal.md and
#                 metrics.md — idempotent), creates the three bullet files, and
#                 injects: the contract, goal.md and metrics.md verbatim, the
#                 bullets so far, and this role's own draft if it exists.
#
#   PreToolUse    denies Edit/Write/MultiEdit/NotebookEdit on goal.md and
#                 metrics.md, and any Bash command that names one of them (or
#                 the task folder itself) together with a mutating verb or as a
#                 redirect target. Reads pass. chmod 444 alone only stops a plain
#                 write; this stops the chmod.
#
# No SWARMKHAZAD_TASK_DIR means an ad-hoc launch: silent exit, nothing to enforce.
# Fails OPEN on malformed input — a role that cannot start because its contract
# hook broke is worse than a role without the hook. Denials are appended to
# <task>/state/denials.jsonl.
set -uo pipefail

task_dir="${SWARMKHAZAD_TASK_DIR:-}"
[ -n "$task_dir" ] || exit 0
[ -d "$task_dir" ] || exit 0
TASK_REAL=$(cd "$task_dir" 2>/dev/null && pwd -P) || exit 0
task_id="${SWARMKHAZAD_TASK_ID:-$(basename "$task_dir")}"
# The session, not the role: two sessions of one role differ by repo, and a
# shared draft-<role>.md would have them overwriting each other.
role="${SWARMKHAZAD_SESSION:-${SWARMFORGE_ROLE:-}}"

input=$(cat 2>/dev/null) || exit 0
event=$(printf '%s' "$input" | jq -r '.hook_event_name // empty' 2>/dev/null) || exit 0

TRUTH=(goal.md metrics.md)
MINE=(decision.md gotcha.md escalation.md)

# ------------------------------------------------------------------ SessionStart
session_start() {
  local source f missing=() ctx draft

  source=$(printf '%s' "$input" | jq -r '.source // "startup"' 2>/dev/null) || source="startup"

  for f in "${MINE[@]}"; do [ -e "$task_dir/$f" ] || : >"$task_dir/$f" 2>/dev/null; done
  for f in "${TRUTH[@]}"; do [ -e "$task_dir/$f" ] && chmod 444 "$task_dir/$f" 2>/dev/null; done
  for f in "${TRUTH[@]}"; do [ -s "$task_dir/$f" ] || missing+=("$f"); done

  ctx="TASK CONTRACT · $task_id · $task_dir${role:+ · role $role}
goal.md · metrics.md                      the truth, read-only (chmod 444). Goal = one checkbox per outcome; Not-goal = settled, do not reopen; Hints = paths. metrics.md = the bars: quantitative with its measure command, qualitative with its judge.
decision.md · gotcha.md · escalation.md   yours to append, one bullet per line: \`- **<claim>** — <why>\`. Big forks, things that cost time, blocks only a human can clear.
draft-${role:-<role>}.md                       your full write-up, in the task folder, before you hand off; the handoff names it.
Done is judged by whoever set the bar, never by you: you never tick a box in goal.md. A bar you cannot meet is an escalation.md line, never an edit to the bar.
Nothing in the folder restates the commit or the diff; detail lives there."

  [ "$source" = "compact" ] && ctx="$ctx
(Context was compacted. The contract and files below are authoritative; what you remember of them is not.)"

  if [ "${#missing[@]}" -gt 0 ]; then
    ctx="$ctx

TASK $task_id HAS NO CONTRACT — missing: ${missing[*]}. Do not start work. Write one escalation.md line naming this, then stop."
  else
    for f in "${TRUTH[@]}"; do
      ctx="$ctx

## $f
$(cat "$task_dir/$f" 2>/dev/null)"
    done
  fi

  for f in "${MINE[@]}"; do
    if [ -s "$task_dir/$f" ]; then
      ctx="$ctx

## $f
$(cat "$task_dir/$f" 2>/dev/null)"
    fi
  done

  if [ -n "$role" ]; then
    draft="$task_dir/draft-$role.md"
    if [ -s "$draft" ]; then
      ctx="$ctx

## draft-$role.md (yours so far)
$(cat "$draft" 2>/dev/null)"
    fi
  fi

  jq -nc --arg c "$ctx" '{hookSpecificOutput:{hookEventName:"SessionStart", additionalContext:$c}}'
  exit 0
}

# ------------------------------------------------------------------- PreToolUse
deny() {
  local reason="$1" tool="$2" detail="$3" log="$task_dir/state/denials.jsonl"
  mkdir -p "$task_dir/state" 2>/dev/null
  jq -nc --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg role "$role" --arg tool "$tool" \
         --arg detail "${detail:0:500}" --arg reason "$reason" \
         '{ts:$ts,role:$role,tool:$tool,detail:$detail,reason:$reason}' >>"$log" 2>/dev/null
  jq -nc --arg reason "$reason" \
     '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$reason}}'
  exit 0
}

# truth_path <path> <cwd>: 0 when <path> is goal.md/metrics.md inside the task
# dir, or the task dir itself. Relative paths resolve against the tool call's
# cwd; a leading ~ and a literal $SWARMKHAZAD_TASK_DIR are expanded first.
truth_path() {
  local p="$1" cwd="$2" base dir real
  p="${p#\"}"; p="${p%\"}"; p="${p#\'}"; p="${p%\'}"
  p="${p//\$\{SWARMKHAZAD_TASK_DIR\}/$task_dir}"; p="${p//\$SWARMKHAZAD_TASK_DIR/$task_dir}"
  case "$p" in "~"|"~/"*) p="$HOME${p#\~}" ;; esac
  [ -n "$p" ] || return 1
  case "$p" in /*) ;; *) p="$cwd/$p" ;; esac
  real=$(cd "$p" 2>/dev/null && pwd -P) && { [ "$real" = "$TASK_REAL" ]; return; }
  base=$(basename "$p")
  case "$base" in goal.md|metrics.md) ;; *) return 1 ;; esac
  dir=$(dirname "$p")
  real=$(cd "$dir" 2>/dev/null && pwd -P) || return 1
  [ "$real" = "$TASK_REAL" ]
}

pre_tool_use() {
  local tool cwd fp cmd w prev matched=0 mutating=0
  tool=$(printf '%s' "$input" | jq -r '.tool_name // empty' 2>/dev/null) || exit 0
  cwd=$(printf '%s' "$input" | jq -r '.cwd // empty' 2>/dev/null) || cwd=""
  [ -n "$cwd" ] || cwd="$PWD"

  case "$tool" in
    Edit|Write|MultiEdit|NotebookEdit)
      fp=$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' 2>/dev/null) || exit 0
      [ -n "$fp" ] || exit 0
      if truth_path "$fp" "$cwd"; then
        deny "$(basename "$fp") is the task's truth (chmod 444). A role never edits goal.md or metrics.md — a bar you cannot meet is an escalation.md line, never an edit to the bar." "$tool" "$fp"
      fi
      exit 0 ;;
    Bash)
      cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null) || exit 0
      [ -n "$cmd" ] || exit 0
      # Cheap pre-filter: nothing here can be about the truth files.
      case "$cmd" in
        *goal.md*|*metrics.md*|*"$task_dir"*|*SWARMKHAZAD_TASK_DIR*) ;;
        *) exit 0 ;;
      esac
      prev=""
      for w in $cmd; do
        case "$w" in
          ">>"*|">|"*|">"*)
            if truth_path "${w#>>}" "$cwd" || truth_path "${w#>|}" "$cwd" || truth_path "${w#>}" "$cwd"; then
              matched=1; mutating=1
            fi ;;
          *)
            if truth_path "$w" "$cwd"; then
              matched=1
              case "$prev" in ">"|">>"|">|") mutating=1 ;; esac
            fi ;;
        esac
        prev="$w"
      done
      # A path embedded in a larger word — python -c "open('.../goal.md','w')",
      # a --file=goal.md flag — is not a whole word above. Pull out every
      # path-shaped run up to goal.md/metrics.md and test those too.
      if [ "$matched" -eq 0 ]; then
        while IFS= read -r w; do
          [ -n "$w" ] || continue
          if truth_path "$w" "$cwd"; then matched=1; break; fi
        done < <(printf '%s' "$cmd" | grep -oE "[^[:space:]'\"()=,;|&<>]*(goal|metrics)\.md")
      fi
      [ "$matched" -eq 1 ] || exit 0
      if printf '%s' "$cmd" | grep -qE '(^|[^[:alnum:]_])(chmod|chown|chflags|mv|rm|cp|tee|truncate|install|ln|dd|python3?|perl|ruby|node)([^[:alnum:]_]|$)'; then
        mutating=1
      fi
      if printf '%s' "$cmd" | grep -qE '(^|[^[:alnum:]_])(sed|perl)[[:space:]]+(-[[:alnum:]]*i|--in-place)'; then
        mutating=1
      fi
      [ "$mutating" -eq 1 ] || exit 0
      deny "goal.md and metrics.md are the task's truth (chmod 444). A role never edits, moves, or unlocks them — a bar you cannot meet is an escalation.md line, never an edit to the bar." "Bash" "$cmd"
      ;;
    *) exit 0 ;;
  esac
}

case "$event" in
  SessionStart) session_start ;;
  PreToolUse)   pre_tool_use ;;
  *)            exit 0 ;;
esac
