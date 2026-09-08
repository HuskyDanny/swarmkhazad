#!/usr/bin/env bash
# swarmkhazad harness shim — installed by `open` as <task>/bin/{claude,codex,grok,copilot}.
#
# A session's pane launches `<task>/bin/<harness>`; this file branches on it
# in $SWARMKHAZAD_SESSION, applies that session's model configuration (one row of
# state/vendors.tsv — the cc_alt vendor table: base URL, keychain token, model
# ids, context window) plus the OTEL exporter tagged with task_id and role,
# then execs the real CLI recorded in state/harnesses.tsv at open time. Model
# configuration is a claude concern; the other shims only pin the real binary.
set -euo pipefail

harness="$(basename "$0")"
task_dir="${SWARMKHAZAD_TASK_DIR:?swarmkhazad shim: SWARMKHAZAD_TASK_DIR is not set}"
session="${SWARMKHAZAD_SESSION:?swarmkhazad shim: SWARMKHAZAD_SESSION is not set}"
role="${SWARMFORGE_ROLE:-$session}"
task_id="${SWARMKHAZAD_TASK_ID:?swarmkhazad shim: SWARMKHAZAD_TASK_ID is not set}"

real="$(awk -F'\t' -v h="$harness" '$1==h {print $2}' "$task_dir/state/harnesses.tsv")"
if [ -z "$real" ] || [ ! -x "$real" ]; then
  echo "swarmkhazad shim: no executable for '$harness' in $task_dir/state/harnesses.tsv" >&2
  exit 127
fi
# sessions.tsv columns are task-lib's sessions-tsv-columns; :model is the
# seventh and the session id is the first (both pinned by shim_test).
declared="$(awk -F'\t' -v r="$session" '$1==r {print $7}' "$task_dir/state/sessions.tsv")"
if [ -z "$declared" ]; then
  echo "swarmkhazad shim: session '$session' is not in $task_dir/state/sessions.tsv" >&2
  exit 2
fi
# `<vendor>[:<model-id>]`. The vendor half picks the endpoint and the keychain
# service; the optional suffix names the exact model, because a vendors.tsv row
# pins one pair for everyone who picks that vendor. Split on the FIRST colon —
# a model id carries colons of its own (`moonshotai/kimi-k3:exacto`), and
# splitting on the last would leave the vendor half unresolvable.
case "$declared" in
  *:*) vendor="${declared%%:*}"; model_override="${declared#*:}" ;;
  *)   vendor="$declared";       model_override="" ;;
esac

# A lane harness (cc_full/cc_auto/cc_control/cc_alt) is one of the operator's
# launcher scripts. It calls `lane_router_env`, which exports its own
# ANTHROPIC_BASE_URL pointing at the local model router — and it runs AFTER this
# shim execs it, so anything we export here would be overwritten anyway. The
# router keys on the model NAME (claude-* to Anthropic, vendor slugs to
# OpenRouter), so `model=<vendor>:<id>` needs only its id half here; the vendor
# half is inert for a lane, the way it already is for codex and grok.
case "$harness" in
  cc_*) lane=1 ;;
  *)    lane="" ;;
esac

extra=()
if [ -n "$lane" ]; then
  [ -n "$model_override" ] && extra=(--model "$model_override")
elif [ "$harness" = "claude" ]; then
  if [ "$vendor" = "anthropic" ]; then
    # Anthropic direct means exactly that: a vendor routing inherited from the
    # operator's shell (a cc_alt session, a local model router) must not leak
    # into a role declared anthropic. The API key stays; an API-key user needs it.
    unset ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN \
          ANTHROPIC_DEFAULT_OPUS_MODEL ANTHROPIC_DEFAULT_SONNET_MODEL ANTHROPIC_DEFAULT_HAIKU_MODEL \
          CLAUDE_CODE_MAX_CONTEXT_TOKENS
    # `anthropic:claude-opus-5[1m]` — same login, a named model rather than
    # whatever settings.json defaults to.
    [ -n "$model_override" ] && extra=(--model "$model_override")
  else
    # vendors.tsv: vendor base_url keychain_service model_main model_small ctx_tokens
    IFS=$'\t' read -r _ BASE_URL KEYCHAIN_SVC MODEL_MAIN MODEL_SMALL CTX_TOKENS \
      < <(awk -F'\t' -v v="$vendor" '$1==v' "$task_dir/state/vendors.tsv") || true
    if [ -z "${MODEL_MAIN:-}" ]; then
      echo "swarmkhazad shim: unknown model vendor '$vendor' for role '$role' (not in state/vendors.tsv)" >&2
      exit 2
    fi
    TOKEN="$(security find-generic-password -s "$KEYCHAIN_SVC" -w 2>/dev/null)" || TOKEN=""
    if [ -z "$TOKEN" ]; then
      echo "swarmkhazad shim: no keychain token for service '$KEYCHAIN_SVC' (role $role, vendor $vendor)" >&2
      exit 1
    fi
    export ANTHROPIC_BASE_URL="$BASE_URL"
    export ANTHROPIC_AUTH_TOKEN="$TOKEN"
    # Empty-but-set: unset, Claude Code falls back to its own Anthropic auth and bills api.anthropic.com.
    export ANTHROPIC_API_KEY=""
    export API_TIMEOUT_MS="3000000"
    [ -n "$model_override" ] && MODEL_MAIN="$model_override"
    export ANTHROPIC_DEFAULT_OPUS_MODEL="$MODEL_MAIN"
    export ANTHROPIC_DEFAULT_SONNET_MODEL="$MODEL_MAIN"
    export ANTHROPIC_DEFAULT_HAIKU_MODEL="$MODEL_SMALL"
    [ -n "${CTX_TOKENS:-}" ] && export CLAUDE_CODE_MAX_CONTEXT_TOKENS="$CTX_TOKENS"
    export CLAUDE_CODE_AUTO_COMPACT_WINDOW="1000000"
    export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC="1"
    extra=(--model "$MODEL_MAIN")
  fi
fi

if [ -n "$lane" ] || [ "$harness" = "claude" ]; then
  # Telemetry: every claude role reports tokens, cost and turns tagged with the
  # task and the role. The endpoint is a local VictoriaMetrics single-node
  # (OTLP at /opentelemetry/v1/metrics); an absent collector costs nothing.
  export CLAUDE_CODE_ENABLE_TELEMETRY="${CLAUDE_CODE_ENABLE_TELEMETRY:-1}"
  export OTEL_METRICS_EXPORTER="${OTEL_METRICS_EXPORTER:-otlp}"
  export OTEL_EXPORTER_OTLP_PROTOCOL="${OTEL_EXPORTER_OTLP_PROTOCOL:-http/protobuf}"
  export OTEL_EXPORTER_OTLP_ENDPOINT="${SWARMKHAZAD_OTLP_ENDPOINT:-http://127.0.0.1:8428/opentelemetry}"
  export OTEL_METRIC_EXPORT_INTERVAL="${OTEL_METRIC_EXPORT_INTERVAL:-10000}"
  export OTEL_RESOURCE_ATTRIBUTES="task_id=${task_id},role=${role},session=${session},repo=${SWARMKHAZAD_REPO:-}${OTEL_RESOURCE_ATTRIBUTES:+,$OTEL_RESOURCE_ATTRIBUTES}"
fi

exec "$real" "${extra[@]+"${extra[@]}"}" "$@"
