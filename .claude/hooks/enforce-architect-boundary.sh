#!/usr/bin/env bash
#
# PreToolUse hook: restrict architect agent to reading spec files only.
#
# Allowed: Read (*.allium, */contract.edn, */CLAUDE.md), Glob, Grep
# Denied: Edit, Write, Bash, and Read of non-spec files

set -euo pipefail

INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
if [[ -z "$TOOL_NAME" ]]; then
  exit 0
fi

deny() {
  cat <<EOF
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"$1"}}
EOF
  exit 0
}

case "$TOOL_NAME" in
  Bash)
    # Allow jj commands only
    COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
    case "$COMMAND" in
      jj\ *|jj)
        exit 0
        ;;
      *)
        deny "Architect agent can only run jj commands via Bash. Denied: $COMMAND"
        ;;
    esac
    ;;
  Edit|Write)
  Glob|Grep)
    # Allowed — architect can search freely
    exit 0
    ;;
  Read)
    # Only allow spec files: *.allium, */contract.edn, */CLAUDE.md
    FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
    if [[ -z "$FILE_PATH" ]]; then
      exit 0
    fi
    case "$FILE_PATH" in
      *.allium|*/contract.edn|*/CLAUDE.md)
        exit 0
        ;;
      *)
        deny "Architect agent can only Read *.allium, contract.edn, and CLAUDE.md files. Cannot read: $FILE_PATH"
        ;;
    esac
    ;;
  *)
    exit 0
    ;;
esac
