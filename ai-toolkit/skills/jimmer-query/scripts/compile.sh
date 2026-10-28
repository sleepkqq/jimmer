#!/usr/bin/env bash
set -euo pipefail
project="${1:-.}"
script_dir="$(cd "$(dirname "$0")" && pwd)"
cmd="$($script_dir/detect-build.sh "$project")"
cd "$project"
printf 'Running: %s\n' "$cmd" >&2
exec bash -lc "$cmd"
