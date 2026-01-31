#!/usr/bin/env bash
#
# PreToolUse hook: enforce module boundary for module-owner agent.
#
# Blocks file-accessing tools (Edit, Write, Read, Glob, Grep) when the
# target path falls outside the assigned module.
#
# MODULE_PATH env var defines the boundary (e.g., "src/fukan/projection/").
# TEST_PATH is derived automatically (src/ -> test/).
#
# Without MODULE_PATH, falls back to blocking writes outside src/fukan/.

set -euo pipefail

# Read the tool invocation JSON from stdin
INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
if [[ -z "$TOOL_NAME" ]]; then
  exit 0
fi

# Extract the relevant path parameter based on tool type
case "$TOOL_NAME" in
  Edit|Write|Read)
    TARGET_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
    ;;
  Glob)
    TARGET_PATH=$(echo "$INPUT" | jq -r '.tool_input.path // empty')
    ;;
  Grep)
    TARGET_PATH=$(echo "$INPUT" | jq -r '.tool_input.path // empty')
    ;;
  *)
    # Not a file-accessing tool we monitor
    exit 0
    ;;
esac

# If no path extracted, allow (some tools have optional path params)
if [[ -z "$TARGET_PATH" ]]; then
  exit 0
fi

# Resolve to absolute path for comparison
if [[ "$TARGET_PATH" != /* ]]; then
  TARGET_PATH="$(pwd)/$TARGET_PATH"
fi
# Normalize (remove trailing slash, resolve ..)
TARGET_PATH=$(cd "$(dirname "$TARGET_PATH")" 2>/dev/null && echo "$(pwd)/$(basename "$TARGET_PATH")" || echo "$TARGET_PATH")

PROJECT_ROOT=$(pwd)

deny() {
  local reason="$1"
  cat <<EOF
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"$reason"}}
EOF
  exit 0
}

if [[ -n "${MODULE_PATH:-}" ]]; then
  # Strict mode: only allow access within module and its test path

  # Build absolute module path
  if [[ "$MODULE_PATH" != /* ]]; then
    ABS_MODULE="$PROJECT_ROOT/$MODULE_PATH"
  else
    ABS_MODULE="$MODULE_PATH"
  fi
  # Normalize (strip trailing slash)
  ABS_MODULE="${ABS_MODULE%/}"

  # Derive test path: src/fukan/foo/ -> test/fukan/foo/
  ABS_TEST="${ABS_MODULE/src\//test/}"

  # Check if target is within module or test boundary
  if [[ "$TARGET_PATH" == "$ABS_MODULE"* ]] || [[ "$TARGET_PATH" == "$ABS_TEST"* ]]; then
    exit 0
  fi

  # Block access
  deny "$TOOL_NAME targets $TARGET_PATH which is outside module boundary ($MODULE_PATH)"

else
  # Fallback: block writes outside src/fukan/
  case "$TOOL_NAME" in
    Edit|Write)
      ABS_SRC="$PROJECT_ROOT/src/fukan"
      if [[ "$TARGET_PATH" == "$ABS_SRC"* ]]; then
        exit 0
      fi
      deny "$TOOL_NAME targets $TARGET_PATH which is outside src/fukan/ (no MODULE_PATH set)"
      ;;
    *)
      # Allow reads/searches in fallback mode
      exit 0
      ;;
  esac
fi
