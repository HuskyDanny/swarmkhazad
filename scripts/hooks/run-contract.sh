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
# The ceiling, so nobody reads this as airtight: it parses a shell command
# WITHOUT a shell, so every expansion bash performs and this does not is a door.
# Name and inode matching closes aliasing — a second name for the same file.
# It does not close indirection: `eval`, a heredoc written out and then run, a
# path assembled across several commands. Roles are held there by the prompt and
# by having no reason to try, not by this file.
#
# Nor does it close TOCTOU. Roles sharing a repo share its worktree, so a second
# session can re-point a name between this check and the write it approved. A
# PreToolUse hook decides before the tool runs and has no hold on the filesystem
# in between; closing that needs the truth to be immutable somewhere this cannot
# reach, not a better check here.
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
MINE=(decision.md gotcha.md finding.md escalation.md)

# ------------------------------------------------------------------ SessionStart
session_start() {
  local source f missing=() ctx draft

  source=$(printf '%s' "$input" | jq -r '.source // "startup"' 2>/dev/null) || source="startup"

  for f in "${MINE[@]}"; do [ -e "$task_dir/$f" ] || : >"$task_dir/$f" 2>/dev/null; done
  for f in "${TRUTH[@]}"; do [ -e "$task_dir/$f" ] && chmod 444 "$task_dir/$f" 2>/dev/null; done
  for f in "${TRUTH[@]}"; do [ -s "$task_dir/$f" ] || missing+=("$f"); done

  ctx="TASK CONTRACT · $task_id · $task_dir${role:+ · role $role}
goal.md · metrics.md                      the truth, read-only (chmod 444). Goal = one checkbox per outcome; Not-goal = settled, do not reopen; Hints = paths. metrics.md = the bars: quantitative with its measure command, qualitative with its judge.
decision.md · gotcha.md · finding.md · escalation.md   yours, written by \`note.bb <kind> '<claim>' '<why>'\` — decision (a fork you resolved), gotcha (what cost you time), finding (what you established; NOT an ask), escalation (what only a human can clear). Editing them directly is denied.
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

# task_file <path> <cwd> <names>: 0 when <path> names one of the space-separated
# <names> inside the task dir, or — for the truth set — the task dir itself.
# Relative paths resolve against the tool call's cwd; a leading ~ and a literal
# $SWARMKHAZAD_TASK_DIR are expanded first.
#
# <names> is matched by an explicit loop, not by `case "$base" in $want)`. A `|`
# inside a variable is a LITERAL in a case pattern, not an alternation, so that
# form matches nothing and the guard silently stops denying. Caught by the
# suite, which is what it is for.
# same_file <a> <b>: 0 when both paths are the same file on disk.
#
# A hard link is the reach `follow_link` cannot see: not a symlink, and named
# whatever its maker chose. `stat` tells them apart, and it follows symlinks, so
# this covers both kinds of alias. RAN: goal.md and a hard link to it both
# report 16777233:64804583, and a Write to the link rewrote goal.md.
#
# Not on the hot path, which is why the cost objection I first made to this was
# wrong: task_file only runs on words of a command that already named a truth
# file or the task folder.
same_file() {
  local a b
  a=$(stat -f '%d:%i' "$1" 2>/dev/null || stat -c '%d:%i' "$1" 2>/dev/null) || return 1
  b=$(stat -f '%d:%i' "$2" 2>/dev/null || stat -c '%d:%i' "$2" 2>/dev/null) || return 1
  [ -n "$a" ] && [ "$a" = "$b" ]
}

# follow_link <path>: <path> with a symlinked FINAL component resolved, up to
# ten hops. Bounded rather than while-true: a symlink loop must not hang a hook
# that every tool call waits on. `readlink -f` is not portable enough to rely on
# here — the BSD one only grew it recently.
follow_link() {
  local p="$1" t n=0
  while [ -L "$p" ] && [ "$n" -lt 10 ]; do
    t=$(readlink "$p") || break
    case "$t" in /*) p="$t" ;; *) p="$(dirname "$p")/$t" ;; esac
    n=$((n + 1))
  done
  printf '%s' "$p"
}

task_file() {
  local p="$1" cwd="$2" want="$3" base dir real f hit=1
  # Every quote, not just the outer pair. `chmod 644 go""al.md` is one word to
  # the shell and the file it names is goal.md; matching on the raw word saw
  # `go""al.md`, matched nothing, and allowed the chmod. RAN.
  p="${p//\"/}"; p="${p//\'/}"
  # Every `${SWARMKHAZAD_TASK_DIR...}` form, not just the bare pair: the shell
  # expands `${SWARMKHAZAD_TASK_DIR:-}` and `${SWARMKHAZAD_TASK_DIR:?}` to the
  # same directory, and matching only `${...}` and `$...` let those through. RAN.
  p=$(printf '%s' "$p" | sed -E "s|\\\$\\{SWARMKHAZAD_TASK_DIR[^}]*\\}|$task_dir|g")
  p="${p//\$SWARMKHAZAD_TASK_DIR/$task_dir}"
  case "$p" in "~"|"~/"*) p="$HOME${p#\~}" ;; esac
  [ -n "$p" ] || return 1
  case "$p" in /*) ;; *) p="$cwd/$p" ;; esac
  # The task dir itself counts as truth: `chmod -R`, `rm -rf` on it reach both.
  if [ "$want" = "$TRUTH_FILES" ]; then
    real=$(cd "$p" 2>/dev/null && pwd -P) && { [ "$real" = "$TASK_REAL" ]; return; }
  fi
  # Resolve the final component before asking what it is called. Only the
  # DIRECTORY was canonicalized, so `notes.md -> goal.md` beside goal.md was a
  # name the guard had never heard of, in a directory it trusted: three allowed
  # operations — chmod the link, write the link — rewrote the acceptance
  # criteria the whole contract exists to keep still. RAN.
  p=$(follow_link "$p")
  # Identity first, name second. The name check is still the fallback and still
  # does the work: a Write creating a file that does not exist yet has no inode
  # to compare, and cannot be an alias for anything either.
  for f in $want; do same_file "$p" "$TASK_REAL/$f" && return 0; done
  base=$(basename "$p")
  for f in $want; do [ "$base" = "$f" ] && { hit=0; break; }; done
  [ "$hit" -eq 0 ] || return 1
  dir=$(dirname "$p")
  real=$(cd "$dir" 2>/dev/null && pwd -P) || return 1
  [ "$real" = "$TASK_REAL" ]
}

TRUTH_FILES='goal.md metrics.md'
NOTE_FILES='decision.md gotcha.md finding.md escalation.md'

truth_path() { task_file "$1" "$2" "$TRUTH_FILES"; }
note_path()  { task_file "$1" "$2" "$NOTE_FILES"; }

# Which set a word belongs to, or nothing. Sets `hit_kind` as a side effect so
# the caller can deny with the right message.
hit_kind=""
classify() {
  if truth_path "$1" "$2"; then hit_kind="truth"; return 0; fi
  if note_path  "$1" "$2"; then hit_kind="note";  return 0; fi
  return 1
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
      if note_path "$fp" "$cwd"; then
        deny "$(basename "$fp") is append-only and written by note.bb, which stamps the repo tag and the bullet format every reader parses. Run: note.bb <decision|gotcha|escalation|finding> '<claim>' '<why>'" "$tool" "$fp"
      fi
      exit 0 ;;
    Bash)
      cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null) || exit 0
      [ -n "$cmd" ] || exit 0
      # Cheap pre-filter: nothing here can be about the truth files. Tested on
      # the command with its quotes removed, because that is the name the shell
      # will open — `go""al.md` passed this untouched.
      bare=${cmd//\"/}; bare=${bare//\'/}
      # Standing in the task folder, every bare filename is a candidate and the
      # names below prove nothing — `printf x > hard.md` is goal.md when
      # hard.md is a link to it. Roles work in a worktree, so this is the rare
      # case and skipping the cheap screen costs nothing in the common one.
      cwd_real=$(cd "$cwd" 2>/dev/null && pwd -P) || cwd_real=""
      case "$bare" in
        *goal.md*|*metrics.md*|*decision.md*|*gotcha.md*|*finding.md*|*escalation.md*|\
        *"$task_dir"*|*SWARMKHAZAD_TASK_DIR*) ;;
        *) [ "$cwd_real" = "$TASK_REAL" ] || exit 0 ;;
      esac
      # A `cd` changes what every relative path after it means, and the hook
      # resolves relative paths against the cwd the tool call reported. When the
      # cd target is a literal that resolves, this is harmless; when it is
      # `$SWARMKHAZAD_HOME/tasks/$SWARMKHAZAD_TASK_ID` or a variable, the hook
      # cannot follow it and `chmod 644 goal.md` looked like a file somewhere
      # else entirely. RAN, allowed.
      #
      # So with a cd present, the name alone is enough to count as naming the
      # truth. `mutating` still gates the denial, so `cd <task> && cat goal.md`
      # keeps passing; only a mutating verb changes outcome. The cost is a repo
      # that has its own goal.md: editing it after a cd is denied, and the
      # message says which rule fired.
      case " $bare " in
        *" cd "*)
          cd_prev=""
          for w in $cmd; do
            cd_word=${w//[\"\']/}
            case "$cd_word" in ">>"*|">|"*|">"*) cd_redirect=${cd_word#>}; cd_redirect=${cd_redirect#>}; cd_redirect=${cd_redirect#|} ;; *) cd_redirect="" ;; esac
            for cd_cand in "$cd_word" "$cd_redirect"; do
              [ -n "$cd_cand" ] || continue
              case "$(basename "$cd_cand")" in
                goal.md|metrics.md) hit_kind="truth"; matched=1 ;;
                decision.md|gotcha.md|finding.md|escalation.md) hit_kind="note"; matched=1 ;;
                *) continue ;;
              esac
              # A redirect at it is a write, whatever verb the line uses. The
              # main loop's redirect check only fires when the path resolves,
              # and after a cd this one does not — that is the whole point.
              case "$cd_prev" in ">"|">>"|">|") mutating=1 ;; esac
              [ -n "$cd_redirect" ] && mutating=1
            done
            cd_prev="$w"
          done ;;
      esac
      prev=""
      for w in $cmd; do
        case "$w" in
          ">>"*|">|"*|">"*)
            if classify "${w#>>}" "$cwd" || classify "${w#>|}" "$cwd" || classify "${w#>}" "$cwd"; then
              matched=1; mutating=1
            fi ;;
          *)
            if classify "$w" "$cwd"; then
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
          if classify "$w" "$cwd"; then matched=1; break; fi
        done < <(printf '%s' "$cmd" | grep -oE "[^[:space:]'\"()=,;|&<>]*(goal|metrics|decision|gotcha|finding|escalation)\.md")
      fi
      # A path assembled at runtime — `chmod 644 $(echo "$d")/goal.md` — is not
      # a word this can resolve: the substitution has not run yet, and the word
      # scan sees `/goal.md`, which is not in the task dir. RAN, allowed. So a
      # command that reaches into the task folder and builds a path with `$(` or
      # a backtick is treated as naming the truth, and falls to the verb and
      # allowlist checks below like any other. `rm -rf $SWARMKHAZAD_TASK_DIR/tmp/x`
      # keeps working — it names the folder but builds nothing.
      if [ "$matched" -eq 0 ]; then
        case "$bare" in
          *'$('*|*'`'*)
            case "$bare" in
              *goal.md*|*metrics.md*|*decision.md*|*gotcha.md*|*finding.md*|\
              *escalation.md*|*"$task_dir"*|*SWARMKHAZAD_TASK_DIR*)
                matched=1; hit_kind="truth" ;;
            esac ;;
        esac
      fi
      [ "$matched" -eq 1 ] || exit 0
      if printf '%s' "$cmd" | grep -qE '(^|[^[:alnum:]_])(chmod|chown|chflags|mv|rm|cp|tee|truncate|install|ln|dd|python3?|perl|ruby|node)([^[:alnum:]_]|$)'; then
        mutating=1
      fi
      # And anything else. The list above is a denylist, so every tool nobody
      # thought of walked past it: `patch goal.md < p.diff` and
      # `printf '1d\nw\n' | ed -s goal.md` both rewrite the file and neither is
      # named there. RAN. A command that NAMES the truth is held to an ALLOWLIST
      # of readers instead, so a tool this file has never heard of denies rather
      # than passes.
      #
      # The head of each pipeline segment only. Every word is not a command:
      # tested that way, `grep -n 'bar:' metrics.md` denied on the pattern and
      # `sed -n '1,5p' goal.md` on the range — reads the contract explicitly
      # allows, and the kind of over-denial that gets a guard switched off.
      # `|| [ -n "$seg" ]`: printf writes no trailing newline, so a command with
      # no pipe is one unterminated line — read fills seg and returns 1, and a
      # bare `while read` drops it. The loop then examined nothing at all and
      # every unknown tool passed, which is the bug this block exists to fix.
      while IFS= read -r seg || [ -n "$seg" ]; do
        read -r -a segw <<< "$seg"
        [ "${#segw[@]}" -gt 0 ] || continue
        head_word=""
        for hw in "${segw[@]}"; do
          case "$hw" in
            *=*) continue ;;                          # VAR=x prefixes
            sudo|command|nohup|time|env|builtin) continue ;;
            # Not commands: the split is on `|;&`, so `foo 2>&1` arrives as two
            # segments and the second one's first word is the file descriptor
            # `1`. Read as a command name it is on no allowlist, so every
            # ordinary `… 2>&1` denied — including `ready_for_next.bb 2>&1`,
            # the mail loop every role runs constantly. Found in a live run.
            [0-9]|[0-9][0-9]) continue ;;
            "<"*|">"*|"&"*) continue ;;
            *) head_word=$(basename "$hw"); break ;;
          esac
        done
        [ -n "$head_word" ] || continue
        case "$head_word" in
          cat|head|tail|grep|egrep|fgrep|rg|less|more|wc|diff|ls|stat|file|\
          awk|sed|cut|sort|uniq|tr|jq|echo|printf|test|basename|dirname|realpath|\
          readlink|md5|shasum|true|false|nl|column|cd|pushd|popd|\
          note.bb|ready_for_next.bb|done_with_current.bb|swarm_handoff.bb|\
          run_evidence.bb|goal_judge.bb) ;;
          *) mutating=1; break ;;
        esac
      done < <(printf '%s' "$bare" | tr '|;&\n' '\n\n\n\n')
      if printf '%s' "$cmd" | grep -qE '(^|[^[:alnum:]_])(sed|perl)[[:space:]]+(-[[:alnum:]]*i|--in-place)'; then
        mutating=1
      fi
      [ "$mutating" -eq 1 ] || exit 0
      if [ "$hit_kind" = "note" ]; then
        deny "decision.md, gotcha.md, finding.md and escalation.md are append-only and written by note.bb, which stamps the repo tag and the bullet format every reader parses. Run: note.bb <decision|gotcha|escalation|finding> '<claim>' '<why>'" "Bash" "$cmd"
      fi
      deny "goal.md and metrics.md are the task's truth (chmod 444). A role never edits, moves, or unlocks them — a bar you cannot meet is an escalation.md line, never an edit to the bar." "Bash" "$cmd"
      ;;
    Read|Grep|Glob|LS|NotebookRead)
      # Reads pass, and these are the reads. Named explicitly so the catch-all
      # below can be strict without denying the one thing the contract promises
      # a role can always do: read goal.md and metrics.md.
      exit 0 ;;
    *)
      # Any tool this file has never heard of — an MCP server (roles load the
      # operator's whole ~/.claude.json set), a harness tool added next month.
      # Enumerating the writers above and waving everything else through is the
      # same denylist mistake as the verb list, one level up: RAN, a call with
      # tool_name `mcp__fs__write` and `tool_input.edits[0].path` at goal.md was
      # allowed without the path being looked at once.
      #
      # So instead of guessing which field holds a path, every string anywhere
      # in tool_input is asked the same question the file tools ask, through the
      # same task_file — no new matcher, no schema to keep up to date.
      while IFS= read -r v; do
        [ -n "$v" ] || continue
        truth_path "$v" "$cwd" && deny "$(basename "$v") is the task's truth (chmod 444). A role never edits goal.md or metrics.md — a bar you cannot meet is an escalation.md line, never an edit to the bar. To READ it, use Read." "$tool" "$v"
        note_path "$v" "$cwd" && deny "$(basename "$v") is append-only and written by note.bb, which stamps the repo tag and the bullet format every reader parses. Run: note.bb <decision|gotcha|escalation|finding> '<claim>' '<why>'" "$tool" "$v"
      done < <(printf '%s' "$input" | jq -r '[.tool_input | .. | strings] | .[]' 2>/dev/null)
      exit 0 ;;
  esac
}

case "$event" in
  SessionStart) session_start ;;
  PreToolUse)   pre_tool_use ;;
  *)            exit 0 ;;
esac
