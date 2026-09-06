#!/usr/bin/env bash
# swarmkhazad harness shim — installed by `open` as <task>/bin/{claude,codex,grok,copilot}.
#
# A role's pane launches `<task>/bin/<harness>`; this file branches on the role
# in $SWARMFORGE_ROLE, applies that role's model configuration (one row of
# state/vendors.tsv — the cc_alt vendor table: base URL, keychain token, model
# ids, context window) plus the OTEL exporter tagged with task_id and role,
# then execs the real CLI recorded in state/harnesses.tsv at open time. Model
# configuration is a claude concern; the other shims only pin the real binary.
set -euo pipefail

harness="$(basename "$0")"
task_dir="${SWARMKHAZAD_TASK_DIR:?swarmkhazad shim: SWARMKHAZAD_TASK_DIR is not set}"
role="${SWARMFORGE_ROLE:?swarmkhazad shim: SWARMFORGE_ROLE is not set}"
task_id="${SWARMKHAZAD_TASK_ID:?swarmkhazad shim: SWARMKHAZAD_TASK_ID is not set}"

real="$(awk -F'\t' -v h="$harness" '$1==h {print $2}' "$task_dir/state/harnesses.tsv")"
if [ -z "$real" ] || [ ! -x "$real" ]; then
  echo "swarmkhazad shim: no executable for '$harness' in $task_dir/state/harnesses.tsv" >&2
  exit 127
fi
# roles.tsv columns are task-lib's roles-tsv-columns; :model is the sixth (pinned by shim_test).
vendor="$(awk -F'\t' -v r="$role" '$1==r {print $6}' "$task_dir/state/roles.tsv")"
if [ -z "$vendor" ]; then
  echo "swarmkhazad shim: role '$role' is not in $task_dir/state/roles.tsv" >&2
  exit 2
fi

extra=()
if [ "$harness" = "claude" ]; then
  if [ "$vendor" = "anthropic" ]; then
    # Anthropic direct means exactly that: a vendor routing inherited from the
    # operator's shell (a cc_alt session, a local model router) must not leak
    # into a role declared anthropic. The API key stays; an API-key user needs it.
    unset ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN \
          ANTHROPIC_DEFAULT_OPUS_MODEL ANTHROPIC_DEFAULT_SONNET_MODEL ANTHROPIC_DEFAULT_HAIKU_MODEL \
          CLAUDE_CODE_MAX_CONTEXT_TOKENS
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
    export ANTHROPIC_DEFAULT_OPUS_MODEL="$MODEL_MAIN"
    export ANTHROPIC_DEFAULT_SONNET_MODEL="$MODEL_MAIN"
    export ANTHROPIC_DEFAULT_HAIKU_MODEL="$MODEL_SMALL"
    [ -n "${CTX_TOKENS:-}" ] && export CLAUDE_CODE_MAX_CONTEXT_TOKENS="$CTX_TOKENS"
    export CLAUDE_CODE_AUTO_COMPACT_WINDOW="1000000"
    export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC="1"
    extra=(--model "$MODEL_MAIN")
  fi
  # Telemetry: every claude role reports tokens, cost and turns tagged with the
  # task and the role. The endpoint is a local VictoriaMetrics single-node
  # (OTLP at /opentelemetry/v1/metrics); an absent collector costs nothing.
  export CLAUDE_CODE_ENABLE_TELEMETRY="${CLAUDE_CODE_ENABLE_TELEMETRY:-1}"
  export OTEL_METRICS_EXPORTER="${OTEL_METRICS_EXPORTER:-otlp}"
  export OTEL_EXPORTER_OTLP_PROTOCOL="${OTEL_EXPORTER_OTLP_PROTOCOL:-http/protobuf}"
  export OTEL_EXPORTER_OTLP_ENDPOINT="${SWARMKHAZAD_OTLP_ENDPOINT:-http://127.0.0.1:8428/opentelemetry}"
  export OTEL_METRIC_EXPORT_INTERVAL="${OTEL_METRIC_EXPORT_INTERVAL:-10000}"
  export OTEL_RESOURCE_ATTRIBUTES="task_id=${task_id},role=${role}${OTEL_RESOURCE_ATTRIBUTES:+,$OTEL_RESOURCE_ATTRIBUTES}"
fi

exec "$real" "${extra[@]+"${extra[@]}"}" "$@"
