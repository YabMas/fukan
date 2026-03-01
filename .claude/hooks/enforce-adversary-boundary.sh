#!/usr/bin/env bash
#
# PreToolUse hook: enforce adversary boundary for VSDD adversary agent.
#
# The adversary can:
#   Read/Glob/Grep: MODULE_PATH, its test path, test_support/, .vsdd/
#   Edit/Write: .vsdd/ only (report output)
#   Edit/Write DENIED: everything else
#
# Required env: MODULE_PATH, AGENT_ROLE=adversary, VSDD_RUN_DIR

set -euo pipefail

INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
if [[ -z "$TOOL_NAME" ]]; then
  exit 0
fi

# Only enforce for adversary role
if [[ "${AGENT_ROLE:-}" != "adversary" ]]; then
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
  deny "MODULE_PATH not set — adversary boundary cannot be enforced"
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
ABS_VSDD="$PROJECT_ROOT/.vsdd"

# --- Read/Glob/Grep: allow module, test, test_support, .vsdd ---
case "$TOOL_NAME" in
  Read|Glob|Grep)
    if [[ "$TARGET_PATH" == "$ABS_MODULE"* ]] ||
       [[ "$TARGET_PATH" == "$ABS_TEST"* ]] ||
       [[ "$TARGET_PATH" == "$ABS_TEST_SUPPORT"* ]] ||
       [[ "$TARGET_PATH" == "$ABS_VSDD"* ]]; then
      exit 0
    fi
    deny "Adversary $TOOL_NAME blocked: $TARGET_PATH is outside readable boundary"
    ;;
esac

# --- Edit/Write: only .vsdd/ ---
case "$TOOL_NAME" in
  Edit|Write)
    if [[ "$TARGET_PATH" == "$ABS_VSDD"* ]]; then
      exit 0
    fi
    deny "Adversary can only write to .vsdd/ (blocked: $TARGET_PATH)"
    ;;
esac
