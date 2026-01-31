#!/usr/bin/env bash
#
# PreToolUse hook: restrict Bash to clj-nrepl-eval commands only.
#
# Used by the architect agent to ensure it explores the codebase
# exclusively through fukan's CLI gateway, not via direct file access.

set -euo pipefail

INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
if [[ -z "$TOOL_NAME" ]]; then
  exit 0
fi

# Only gate Bash commands
if [[ "$TOOL_NAME" != "Bash" ]]; then
  # Deny file-access tools entirely
  case "$TOOL_NAME" in
    Read|Edit|Write|Glob|Grep)
      cat <<EOF
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Architect agent cannot use $TOOL_NAME. Explore the codebase through the fukan CLI gateway: (fukan.cli.gateway/exec \"command\")"}}
EOF
      exit 0
      ;;
    *)
      exit 0
      ;;
  esac
fi

# Extract the bash command
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
if [[ -z "$COMMAND" ]]; then
  exit 0
fi

# Allow only clj-nrepl-eval commands
if echo "$COMMAND" | grep -q '^clj-nrepl-eval\b'; then
  exit 0
fi

cat <<EOF
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Architect agent Bash is restricted to clj-nrepl-eval commands only. Use: clj-nrepl-eval -p 7889 \"(fukan.cli.gateway/exec \\\"command\\\")\""}}
EOF
