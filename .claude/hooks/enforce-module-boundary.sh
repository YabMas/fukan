#!/usr/bin/env bash
#
# PreToolUse hook: enforce module-owner boundary for VSDD module-owner agent.
#
# The module-owner can:
#   Read/Glob/Grep: MODULE_PATH, its test path, test_support/
#   Edit/Write: .clj files under MODULE_PATH,
#               test path, test_support/invariants/, test_support/generators.clj,
#               test_support/registry.clj
#   Edit/Write DENIED: *.allium, *.md, anything outside boundary
#
# Required env: MODULE_PATH, AGENT_ROLE=module-owner

set -euo pipefail

INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
if [[ -z "$TOOL_NAME" ]]; then
  exit 0
fi

# Only enforce for module-owner role
if [[ "${AGENT_ROLE:-}" != "module-owner" ]]; then
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

# If no path extracted, allow (some tools have optional path params)
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
  deny "MODULE_PATH not set — module-owner boundary cannot be enforced"
fi

# Build absolute paths
if [[ "$MODULE_PATH" != /* ]]; then
  ABS_MODULE="$PROJECT_ROOT/$MODULE_PATH"
else
  ABS_MODULE="$MODULE_PATH"
fi
ABS_MODULE="${ABS_MODULE%/}"

# Derive test path: src/fukan/foo/ -> test/fukan/foo/
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
    deny "Module-owner $TOOL_NAME blocked: $TARGET_PATH is outside boundary ($MODULE_PATH)"
    ;;
esac

# --- Edit/Write: more restrictive ---
case "$TOOL_NAME" in
  Edit|Write)
    # Block *.allium and *.md everywhere
    if [[ "$TARGET_PATH" == *.allium ]]; then
      deny "Module-owner cannot modify .allium files"
    fi
    if [[ "$TARGET_PATH" == *.md ]]; then
      deny "Module-owner cannot modify .md files"
    fi

    # Allow .clj files under module src path
    if [[ "$TARGET_PATH" == "$ABS_MODULE"* ]]; then
      if [[ "$TARGET_PATH" == *.clj ]]; then
        exit 0
      fi
      deny "Module-owner can only write .clj files under module path"
    fi

    # Allow test path (test files)
    if [[ "$TARGET_PATH" == "$ABS_TEST"* ]]; then
      exit 0
    fi

    # Allow test_support/invariants/ and specific test_support files
    if [[ "$TARGET_PATH" == "$ABS_TEST_SUPPORT/invariants/"* ]]; then
      exit 0
    fi
    if [[ "$TARGET_PATH" == "$ABS_TEST_SUPPORT/generators.clj" ]]; then
      exit 0
    fi
    if [[ "$TARGET_PATH" == "$ABS_TEST_SUPPORT/registry.clj" ]]; then
      exit 0
    fi

    deny "Module-owner $TOOL_NAME blocked: $TARGET_PATH is outside writable boundary"
    ;;
esac
