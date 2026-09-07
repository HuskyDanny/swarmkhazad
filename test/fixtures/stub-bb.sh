#!/bin/bash
# A `bb` that dies for one script and is the real thing for every other.
#
# The judge is a subprocess, so "the judge process itself crashed" — as opposed
# to "the model was unavailable", which goal_judge.bb handles and reports — is
# only reachable by breaking that subprocess. SWARMKHAZAD_STUB_BB_FAIL names a
# script to fail on.
set -u
fail="${SWARMKHAZAD_STUB_BB_FAIL:-}"
if [ -n "$fail" ]; then
  for arg in "$@"; do
    case "$arg" in
      *"$fail") echo "stub bb: refusing to run $fail" >&2; exit 1 ;;
    esac
  done
fi
exec "$SWARMKHAZAD_STUB_BB_REAL" "$@"
