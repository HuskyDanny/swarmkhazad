#!/bin/bash
# A stand-in `claude` for the Linear intake: records the argv it was called with,
# then answers with whatever issue JSON the test asked for.
#
#   SWARMKHAZAD_STUB_ISSUE   the structured_output to return (JSON)
#   SWARMKHAZAD_STUB_MODE    "" = answer, "fail" = exit 1, "garbage" = not JSON,
#                            "hang" = sleep past the caller's timeout
#   SWARMKHAZAD_STUB_ARGV    file to record argv in
set -u
[ -n "${SWARMKHAZAD_STUB_ARGV:-}" ] && printf '%s\n' "$@" > "$SWARMKHAZAD_STUB_ARGV"

case "${SWARMKHAZAD_STUB_MODE:-}" in
  fail) echo "stub: linear is down" >&2; exit 1 ;;
  garbage) echo "not json"; exit 0 ;;
  hang) sleep 30; exit 0 ;;
esac

issue="${SWARMKHAZAD_STUB_ISSUE:-{\"found\":true,\"identifier\":\"ENG-1\",\"title\":\"stub title\",\"description\":\"\",\"url\":\"https://linear.app/x/issue/ENG-1\",\"acceptance\":[]}}"
printf '{"type":"result","is_error":false,"num_turns":2,"total_cost_usd":0.01,"result":"done","structured_output":%s}\n' "$issue"
