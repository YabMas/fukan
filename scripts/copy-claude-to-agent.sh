#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

find "$ROOT_DIR" -name 'CLAUDE.md' -print0 | while IFS= read -r -d '' src; do
  dst="${src%/CLAUDE.md}/AGENTS.md"
  cp "$src" "$dst"
  echo "Copied: $src -> $dst"
done
