#!/usr/bin/env bash
set -euo pipefail
project="${1:-.}"
cd "$project"
printf 'Migration candidates in %s\n' "$PWD"
rg --files | rg 'db/(changelog|migration)|liquibase|flyway|migration|changelog' || true
printf '\nLatest migration-like files:\n'
rg --files | rg '(^|/)(V[0-9].*__.*\.sql|.*changelog.*\.(ya?ml|xml|json)|[0-9].*\.(sql|ya?ml|xml))$' | sort | tail -20 || true
printf '\nNext filename must follow project convention above. Do not invent format if unclear.\n'
