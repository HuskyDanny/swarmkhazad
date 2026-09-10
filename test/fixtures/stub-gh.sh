#!/bin/bash
# A stand-in for `gh`, recording every call with the repo it was made in, so a
# test can assert the ORDER ship worked in and not only that it called out.
#
#   GH_STUB_LOG        every call, as `<repo> <args>`
#   GH_STUB_EXISTING   `pr list` answers with an open PR at this URL
#   GH_STUB_PR_FAILS   `pr create` fails in the repo of this name
#   GH_STUB_GRAPHQL    `api graphql` answers with this file's contents; the
#                      per-repo file `<it>.<repo>` wins when it exists
#   GH_STUB_API_FAILS  `api graphql` fails, the way an outage looks
set -u
here=$(basename "$PWD")
printf '%s %s\n' "$here" "$*" >> "${GH_STUB_LOG:-/dev/null}"

case "$1 $2" in
  "auth token")
    echo "ghs_stubtoken_for_${4:-unknown}" ;;
  "pr list")
    if [ -n "${GH_STUB_EXISTING:-}" ]; then
      printf '[{"number":7,"url":"%s"}]\n' "$GH_STUB_EXISTING"
    else
      echo '[]'
    fi ;;
  "pr create")
    if [ "${GH_STUB_PR_FAILS:-}" = "$here" ]; then
      echo "stub gh: pull request create failed: no upstream configured" >&2
      exit 1
    fi
    # The body is a temp file ship deletes; keep a copy so a test can read it.
    for a in "$@"; do
      [ -n "${want_body:-}" ] && { cp "$a" "${GH_STUB_LOG:-/dev/null}.body-$here"; unset want_body; }
      [ "$a" = "--body-file" ] && want_body=1
    done
    # What a real gh prints on success: the URL, nothing else.
    echo "https://github.com/acme/$here/pull/1" ;;
  "pr edit")
    # Same body capture as create: the PR a role's handoff opened is the one
    # ship later rewrites with the verdict, so a test reads both from one file.
    for a in "$@"; do
      [ -n "${want_body:-}" ] && { cp "$a" "${GH_STUB_LOG:-/dev/null}.body-$here"; unset want_body; }
      [ "$a" = "--body-file" ] && want_body=1
    done
    echo "https://github.com/acme/$here/pull/${3:-1}" ;;
  "api graphql")
    if [ -n "${GH_STUB_API_FAILS:-}" ]; then
      echo "stub gh: could not reach api.github.com" >&2
      exit 1
    fi
    f="${GH_STUB_GRAPHQL:-}"
    [ -f "$f.$here" ] && f="$f.$here"
    if [ -f "$f" ]; then cat "$f"; else echo '{"data":{"repository":null}}'; fi ;;
  *)
    echo "stub gh: unhandled: $*" >&2; exit 1 ;;
esac
