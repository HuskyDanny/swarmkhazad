#!/bin/bash
# A `bb` that dies for one script, records the argv of another, and is the real
# thing for every other.
#
# The judge is a subprocess, so "the judge process itself crashed" — as opposed
# to "the model was unavailable", which goal_judge.bb handles and reports — is
# only reachable by breaking that subprocess. SWARMKHAZAD_STUB_BB_FAIL names a
# script to fail on.
#
# SWARMKHAZAD_STUB_BB_STOP names a script to record instead of run, writing its
# argv one line per element to SWARMKHAZAD_STUB_BB_ARGV. It is how a test reads
# the command a server decided to issue without paying for what that command
# does — `open` spawns a tmux server and a session per role.
set -u
fail="${SWARMKHAZAD_STUB_BB_FAIL:-}"
if [ -n "$fail" ]; then
  for arg in "$@"; do
    case "$arg" in
      *"$fail") echo "stub bb: refusing to run $fail" >&2; exit 1 ;;
    esac
  done
fi
stop="${SWARMKHAZAD_STUB_BB_STOP:-}"
if [ -n "$stop" ]; then
  for arg in "$@"; do
    case "$arg" in
      *"$stop")
        [ -n "${SWARMKHAZAD_STUB_BB_ARGV:-}" ] && printf '%s\n' "$@" > "$SWARMKHAZAD_STUB_BB_ARGV"
        echo "stub bb: recorded $stop"
        exit 0 ;;
    esac
  done
fi
exec "$SWARMKHAZAD_STUB_BB_REAL" "$@"
