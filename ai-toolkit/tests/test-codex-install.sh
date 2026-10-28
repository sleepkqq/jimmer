#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECTED=(
  jimmer-advanced-mappings
  jimmer-caching
  jimmer-config
  jimmer-debug
  jimmer-dml
  jimmer-dto
  jimmer-entity
  jimmer-fetchers
  jimmer-filters
  jimmer-inheritance
  jimmer-kotlin
  jimmer-migrations
  jimmer-performance
  jimmer-quarkus
  jimmer-query
  jimmer-repositories
  jimmer-save-modes
)
TEMP_DIRS=()

cleanup() {
  local directory
  for directory in "${TEMP_DIRS[@]}"; do
    rm -rf "$directory"
  done
}
trap cleanup EXIT

new_home() {
  NEW_HOME="$(mktemp -d)"
  TEMP_DIRS+=("$NEW_HOME")
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_contains() {
  case "$1" in
    *"$2"*) ;;
    *) fail "expected output to contain: $2" ;;
  esac
}

assert_skill_tree() {
  local root="$1" name count=0
  for name in "${EXPECTED[@]}"; do
    [[ -f "$root/$name/SKILL.md" ]] || fail "missing $root/$name/SKILL.md"
    count=$((count + 1))
  done
  local actual=0 path
  for path in "$root"/*; do
    [[ -d "$path" || -L "$path" ]] || continue
    actual=$((actual + 1))
  done
  [[ $actual -eq $count ]] || fail "expected $count skills, found $actual"
}

help_output="$(bash "$REPO_DIR/install.sh" --help)"
assert_contains "$help_output" "codex"

set +e
invalid_output="$(bash "$REPO_DIR/install.sh" --tool invalid 2>&1)"
invalid_status=$?
set -e
[[ $invalid_status -ne 0 ]] || fail "invalid tool unexpectedly succeeded"
assert_contains "$invalid_output" "codex"

new_home
copy_home="$NEW_HOME"
first_output="$(HOME="$copy_home" bash "$REPO_DIR/install.sh" --tool codex)"
assert_skill_tree "$copy_home/.agents/skills"
cmp "$REPO_DIR/versions/0.12.2/skills/jimmer-caching/references/quarkus.md" \
  "$copy_home/.agents/skills/jimmer-caching/references/quarkus.md"
cmp "$REPO_DIR/versions/0.12.2/skills/jimmer-dto/GUIDE.md" \
  "$copy_home/.agents/skills/jimmer-dto/GUIDE.md"
for name in "${EXPECTED[@]}"; do
  [[ ! -L "$copy_home/.agents/skills/$name" ]] || fail "copy mode created symlink for $name"
done
second_output="$(HOME="$copy_home" bash "$REPO_DIR/install.sh" --tool codex)"
identical_count=0
remaining="$second_output"
while [[ "$remaining" == *"identical:"* ]]; do
  remaining="${remaining#*identical:}"
  identical_count=$((identical_count + 1))
done
[[ $identical_count -eq ${#EXPECTED[@]} ]] || fail "repeat install skipped $identical_count of ${#EXPECTED[@]} skills"

new_home
symlink_home="$NEW_HOME"
HOME="$symlink_home" bash "$REPO_DIR/install.sh" --tool codex --symlink >/dev/null
assert_skill_tree "$symlink_home/.agents/skills"
for name in "${EXPECTED[@]}"; do
  link="$symlink_home/.agents/skills/$name"
  [[ -L "$link" ]] || fail "$link is not a symlink"
  [[ "$(readlink "$link")" == "$REPO_DIR/versions/0.12.2/skills/$name" ]] || fail "wrong target for $link"
done

new_home
mcp_home="$NEW_HOME"
stub_bin="$mcp_home/bin"
mkdir -p "$stub_bin"
cat > "$stub_bin/npm" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "$stub_bin/codex" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CODEX_CALLS"
if [[ "$*" == "mcp list --json" ]]; then
  if [[ "${CODEX_ALREADY_REGISTERED:-0}" == "1" ]]; then
    printf '[{"name":"jimmer-docs"}]\n'
  else
    printf '[]\n'
  fi
fi
EOF
chmod +x "$stub_bin/npm" "$stub_bin/codex"
calls="$mcp_home/codex-calls.log"
HOME="$mcp_home" PATH="$stub_bin:$PATH" CODEX_CALLS="$calls" \
  bash "$REPO_DIR/install.sh" --tool codex --mcp >/dev/null
assert_contains "$(cat "$calls")" "mcp list --json"
expected_calls="mcp list --json
mcp add jimmer-docs -- node $REPO_DIR/mcp/jimmer-docs-mcp/dist/bundle.js"
[[ "$(cat "$calls")" == "$expected_calls" ]] || fail "unexpected Codex MCP calls: $(cat "$calls")"
[[ ! -e "$mcp_home/.agents/settings.json" ]] || fail "Codex settings.json must not be created"

: > "$calls"
HOME="$mcp_home" PATH="$stub_bin:$PATH" CODEX_CALLS="$calls" CODEX_ALREADY_REGISTERED=1 \
  bash "$REPO_DIR/install.sh" --tool codex --mcp >/dev/null
assert_contains "$(cat "$calls")" "mcp list --json"
[[ "$(cat "$calls")" == "mcp list --json" ]] || fail "idempotent MCP calls were not exact"

printf 'Codex installer tests passed\n'
