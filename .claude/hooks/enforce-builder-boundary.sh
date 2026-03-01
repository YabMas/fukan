#!/usr/bin/env bash
#
# PreToolUse hook: enforce builder boundary for VSDD builder agent.
#
# The builder can:
#   Read/Glob/Grep: MODULE_PATH, its test path, test_support/
#   Edit/Write: .clj files under MODULE_PATH (src side only)
#   Edit/Write DENIED: test files, contract.edn, *.allium, *.md, anything outside
#
# Required env: MODULE_PATH, AGENT_ROLE=builder

set -euo pipefail

INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
if [[ -z "$TOOL_NAME" ]]; then
  exit 0
fi

# Only enforce for builder role
if [[ "${AGENT_ROLE:-}" != "builder" ]]; then
  exit 0
fi

# Extract path parameter based on tool type
case "$TOOL_NAME" in
  Edit|Write|Read)
    TARGET_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
    ;;
  Glob|Grep)
    TARGET_PATH=$(echo "$INPUT" | jq -r '.tool_input.path // empty')
    ;;
  *)
    exit 0
    ;;
esac

if [[ -z "$TARGET_PATH" ]]; then
  exit 0
fi

# Resolve to absolute path
if [[ "$TARGET_PATH" != /* ]]; then
  TARGET_PATH="$(pwd)/$TARGET_PATH"
fi
TARGET_PATH=$(cd "$(dirname "$TARGET_PATH")" 2>/dev/null && echo "$(pwd)/$(basename "$TARGET_PATH")" || echo "$TARGET_PATH")

PROJECT_ROOT=$(pwd)

deny() {
  local reason="$1"
  cat <<EOF
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"$reason"}}
EOF
  exit 0
}

if [[ -z "${MODULE_PATH:-}" ]]; then
  deny "MODULE_PATH not set — builder boundary cannot be enforced"
fi

# Build absolute paths
if [[ "$MODULE_PATH" != /* ]]; then
  ABS_MODULE="$PROJECT_ROOT/$MODULE_PATH"
else
  ABS_MODULE="$MODULE_PATH"
fi
ABS_MODULE="${ABS_MODULE%/}"

ABS_TEST="${ABS_MODULE/src\//test/}"
ABS_TEST_SUPPORT="$PROJECT_ROOT/test/fukan/test_support"

# --- Read/Glob/Grep: allow within module, test, and test_support ---
case "$TOOL_NAME" in
  Read|Glob|Grep)
    if [[ "$TARGET_PATH" == "$ABS_MODULE"* ]] ||
       [[ "$TARGET_PATH" == "$ABS_TEST"* ]] ||
       [[ "$TARGET_PATH" == "$ABS_TEST_SUPPORT"* ]]; then
      exit 0
    fi
    deny "Builder $TOOL_NAME blocked: $TARGET_PATH is outside module boundary ($MODULE_PATH), test path, and test_support/"
    ;;
esac

# --- Edit/Write: only .clj under module src path ---
case "$TOOL_NAME" in
  Edit|Write)
    # Block everything outside module src
    if [[ "$TARGET_PATH" == "$ABS_MODULE"* ]]; then
      # Only allow .clj files (not contract.edn, not .allium, not .md)
      if [[ "$TARGET_PATH" == *.clj ]]; then
        exit 0
      fi
      deny "Builder can only write .clj files under module src path (blocked: $(basename "$TARGET_PATH"))"
    fi

    deny "Builder $TOOL_NAME blocked: $TARGET_PATH is outside module src boundary ($MODULE_PATH)"
    ;;
esac
