#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

versions="$(bash "$ROOT/install.sh" --list-versions)"
[[ "$versions" == *0.9.111* && "$versions" == *0.12.2* ]] || fail "missing releases"

for version in bogus 0.9.999 ../0.9.111; do
    if HOME="$TEMP/invalid" bash "$ROOT/install.sh" --version "$version" >"$TEMP/error" 2>&1; then
        fail "accepted version $version"
    fi
done
if HOME="$TEMP/invalid" bash "$ROOT/install.sh" --version >"$TEMP/error" 2>&1; then
    fail "accepted missing version"
fi
[[ ! -e "$TEMP/invalid" ]] || fail "invalid selection modified home"

for tool in opencode claude qwen gigacode codex; do
    home="$TEMP/$tool"
    mkdir -p "$home"
    case "$tool" in
        opencode) target="$home/.config/opencode/skills" ;;
        codex) target="$home/.agents/skills" ;;
        *) target="$home/.$tool/skills" ;;
    esac
    mkdir -p "$target/unrelated"
    printf 'user skill\n' >"$target/unrelated/SKILL.md"

    # Upgrade/downgrade replaces whole skill directories, including old references.
    for version in 0.12.2 0.9.111 0.12.2; do
        HOME="$home" bash "$ROOT/install.sh" --tool "$tool" --version "$version" >"$TEMP/output"
        for skill in "$ROOT/versions/$version/skills"/*; do
            diff -qr "$skill" "$target/$(basename "$skill")" >/dev/null || fail "wrong copy: $tool/$version"
        done
        [[ -f "$target/unrelated/SKILL.md" ]] || fail "unrelated skill removed"
        HOME="$home" bash "$ROOT/install.sh" --tool "$tool" --version "$version" >"$TEMP/output"
        [[ "$(grep -c 'identical:' "$TEMP/output")" == 17 ]] || fail "copy not idempotent"
    done

    # Migrating from the old layout must repair dangling managed links.
    rm -rf "$target/jimmer-query"
    ln -s "$ROOT/skills/jimmer-query" "$target/jimmer-query"
    for version in 0.9.111 0.12.2; do
        HOME="$home" bash "$ROOT/install.sh" --tool "$tool" --version "$version" --symlink >"$TEMP/output"
        for skill in "$ROOT/versions/$version/skills"/*; do
            link="$target/$(basename "$skill")"
            [[ -L "$link" && "$(readlink "$link")" == "$skill" ]] || fail "wrong link: $link"
        done
        HOME="$home" bash "$ROOT/install.sh" --tool "$tool" --version "$version" --symlink >"$TEMP/output"
        [[ "$(grep -c 'already linked:' "$TEMP/output")" == 17 ]] || fail "links not idempotent"
    done

    HOME="$home" bash "$ROOT/install.sh" --tool "$tool" --version 0.12.2 >"$TEMP/output"
    [[ ! -L "$target/jimmer-query" ]] || fail "copy mode retained a symlink"
done

printf 'Version selection, switching, idempotence and legacy symlink migration passed for all 5 tools\n'
