#!/bin/bash
set -euo pipefail

TOOLKIT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOL="opencode"
JIMMER_VERSION="0.12.2"
USE_SYMLINK=false
INSTALL_MCP=false

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

log_header()  { echo -e "\n${BOLD}${BLUE}$1${NC}"; }
log_install() { echo -e "  ${GREEN}+${NC} $1"; }
log_update()  { echo -e "  ${YELLOW}~${NC} $1"; }
log_skip()    { echo -e "  ${DIM}-${NC} ${DIM}$1${NC}"; }
log_error()   { echo -e "  ${RED}!${NC} $1"; }
log_info()    { echo -e "  ${CYAN}i${NC} $1"; }

print_usage() {
    echo -e "${BOLD}Jimmer AI Toolkit Installer${NC}"
    echo ""
    echo -e "Usage: ${CYAN}./install.sh${NC} [OPTIONS]"
    echo ""
    echo "Installs Jimmer skills into the user config so all projects can use them. Safe to run repeatedly."
    echo ""
    echo "Options:"
    echo -e "  ${CYAN}--tool${NC} opencode|claude|qwen|gigacode|codex Target CLI tool (default: opencode)"
    echo -e "  ${CYAN}--version${NC} VERSION                          Jimmer release (default: 0.12.2)"
    echo -e "  ${CYAN}--list-versions${NC}                            List bundled Jimmer releases"
    echo -e "  ${CYAN}--symlink${NC}                                Use symlinks instead of copies"
    echo -e "  ${CYAN}--mcp${NC}                                    Install MCP server config"
    echo ""
    echo "Examples:"
    echo -e "  ${DIM}./install.sh${NC}"
    echo -e "  ${DIM}./install.sh --mcp${NC}"
    echo -e "  ${DIM}./install.sh --tool claude${NC}"
    echo -e "  ${DIM}./install.sh --tool gigacode${NC}"
    echo -e "  ${DIM}./install.sh --tool codex${NC}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tool)
            [[ $# -ge 2 ]] || { log_error "--tool requires a value"; exit 1; }
            TOOL="$2"
            shift 2
            ;;
        --version)
            [[ $# -ge 2 ]] || { log_error "--version requires a value"; exit 1; }
            JIMMER_VERSION="$2"
            shift 2
            ;;
        --list-versions)
            for dir in "$TOOLKIT_DIR"/versions/*/skills; do
                basename "$(dirname "$dir")"
            done
            exit 0
            ;;
        --symlink)
            USE_SYMLINK=true
            shift
            ;;
        --mcp)
            INSTALL_MCP=true
            shift
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        *)
            echo -e "${RED}Error:${NC} Unknown option or unexpected argument: $1"
            print_usage
            exit 1
            ;;
    esac
done

if [[ "$TOOL" != "opencode" && "$TOOL" != "claude" && "$TOOL" != "qwen" && "$TOOL" != "gigacode" && "$TOOL" != "codex" ]]; then
    echo -e "${RED}Error:${NC} --tool must be 'opencode', 'claude', 'qwen', 'gigacode', or 'codex'"
    exit 1
fi

SKILLS_SOURCE="$TOOLKIT_DIR/versions/$JIMMER_VERSION/skills"
if [[ ! "$JIMMER_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || [[ ! -d "$SKILLS_SOURCE" ]]; then
    log_error "Unsupported Jimmer version: $JIMMER_VERSION. Use --list-versions."
    exit 1
fi

case "$TOOL" in
    opencode)
        CONFIG_DIR="$HOME/.config/opencode"
        ;;
    claude)
        CONFIG_DIR="$HOME/.claude"
        ;;
    qwen)
        CONFIG_DIR="$HOME/.qwen"
        ;;
    gigacode)
        CONFIG_DIR="$HOME/.gigacode"
        ;;
    codex)
        CONFIG_DIR="$HOME/.agents"
        ;;
esac

SKILLS_DIR="$CONFIG_DIR/skills"
mkdir -p "$SKILLS_DIR"

echo -e "\n${BOLD}Jimmer AI Toolkit${NC} ${DIM}→${NC} ${CYAN}$TOOL${NC} ${DIM}→${NC} $SKILLS_DIR/"

INSTALLED=0
SKIPPED=0
UPDATED=0

install_path() {
    local src="$1"
    local dst="$2"
    local label="$3"

    if [ -e "$dst" ] || [ -L "$dst" ]; then
        if [ "$USE_SYMLINK" = true ] && [ -L "$dst" ] && [ "$(readlink "$dst")" = "$src" ]; then
            log_skip "already linked: $label"
            SKIPPED=$((SKIPPED + 1))
            return
        fi
        if [ ! -L "$dst" ] && [ "$USE_SYMLINK" = false ] && [ -d "$src" ] && [ -d "$dst" ] && diff -qr "$src" "$dst" >/dev/null 2>&1; then
            log_skip "identical: $label"
            SKIPPED=$((SKIPPED + 1))
            return
        fi
        rm -rf "$dst"
        if [ "$USE_SYMLINK" = true ]; then
            ln -s "$src" "$dst"
            log_update "updated (symlink): $label"
        else
            mkdir -p "$(dirname "$dst")"
            cp -R "$src" "$dst"
            log_update "updated: $label"
        fi
        UPDATED=$((UPDATED + 1))
    else
        if [ "$USE_SYMLINK" = true ]; then
            ln -s "$src" "$dst"
            log_install "linked: $label"
        else
            mkdir -p "$(dirname "$dst")"
            cp -R "$src" "$dst"
            log_install "copied: $label"
        fi
        INSTALLED=$((INSTALLED + 1))
    fi
}

log_header "Skills"
log_info "Jimmer $JIMMER_VERSION"
SKILL_COUNT=0
for dir in "$SKILLS_SOURCE"/*; do
    [ -d "$dir" ] || continue
    [ -f "$dir/SKILL.md" ] || continue
    name=$(basename "$dir")
    install_path "$dir" "$SKILLS_DIR/$name" "$name"
    SKILL_COUNT=$((SKILL_COUNT + 1))
done

if [ "$INSTALL_MCP" = true ]; then
    log_header "MCP Server"

    MCP_DIR="$TOOLKIT_DIR/mcp/jimmer-docs-mcp"
    MCP_DIST="$MCP_DIR/dist/bundle.js"

    if ! command -v npm >/dev/null 2>&1; then
        log_error "npm not found. Install Node.js to build the MCP server."
        INSTALL_MCP=false
    elif ! command -v git >/dev/null 2>&1; then
        log_error "git not found. Install Git to retrieve MCP documentation snapshots."
        INSTALL_MCP=false
    else
        log_info "Building MCP server (npm install && npm run bundle)..."
        if (cd "$MCP_DIR" && npm install --silent && npm run bundle --silent) >/dev/null 2>&1; then
            log_install "built $MCP_DIST"
        else
            log_error "MCP build failed. Run manually: cd $MCP_DIR && npm install && npm run bundle"
            INSTALL_MCP=false
        fi
    fi

    if [ "$INSTALL_MCP" = true ]; then
        MCP_SERVER_BLOCK="\"jimmer-docs\": {
      \"type\": \"stdio\",
      \"command\": \"node\",
      \"args\": [\"$MCP_DIST\"]
    }"

        if [ "$TOOL" = "codex" ]; then
            if command -v codex >/dev/null 2>&1; then
                MCP_LIST="$(codex mcp list --json 2>/dev/null || true)"
                if printf '%s\n' "$MCP_LIST" | grep -Eq '"name"[[:space:]]*:[[:space:]]*"jimmer-docs"'; then
                    log_skip "jimmer-docs already registered in codex user config"
                else
                    codex mcp add jimmer-docs -- node "$MCP_DIST" >/dev/null 2>&1 \
                        && log_install "registered jimmer-docs (codex mcp)" \
                        || log_error "codex mcp add failed. Add manually: codex mcp add jimmer-docs -- node $MCP_DIST"
                fi
            else
                log_info "codex CLI not found. Register manually:"
                echo -e "    ${DIM}codex mcp add jimmer-docs -- node $MCP_DIST${NC}"
            fi
        elif [ "$TOOL" = "claude" ]; then
            # Claude Code reads global (user-scope) MCP from ~/.claude.json, not
            # ~/.claude/.mcp.json. Register via the official CLI so it lands in the
            # right place and survives config-format changes.
            if command -v claude >/dev/null 2>&1; then
                if claude mcp list 2>/dev/null | grep -q "jimmer-docs"; then
                    log_skip "jimmer-docs already registered in claude user config"
                else
                    claude mcp add jimmer-docs --scope user \
                        -- node "$MCP_DIST" >/dev/null 2>&1 \
                        && log_install "registered jimmer-docs (claude mcp, user scope)" \
                        || log_error "claude mcp add failed. Add manually: claude mcp add jimmer-docs --scope user -- node $MCP_DIST"
                fi
            else
                log_info "claude CLI not found. Register manually:"
                echo -e "    ${DIM}claude mcp add jimmer-docs --scope user -- node $MCP_DIST${NC}"
            fi
        elif [ "$TOOL" = "opencode" ]; then
            MCP_FILE="$CONFIG_DIR/.mcp.json"
            if [ -f "$MCP_FILE" ]; then
                if grep -q "jimmer-docs" "$MCP_FILE" 2>/dev/null; then
                    log_skip "jimmer-docs already in $MCP_FILE"
                else
                    log_info "$MCP_FILE exists. Add this to your mcpServers section:"
                    echo -e "    ${DIM}${MCP_SERVER_BLOCK}${NC}"
                fi
            else
                cat > "$MCP_FILE" << MCPEOF
{
  "mcpServers": {
    "jimmer-docs": {
      "type": "stdio",
      "command": "node",
      "args": ["$MCP_DIST"]
    }
  }
}
MCPEOF
                log_install "created $MCP_FILE"
            fi
        else
            MCP_FILE="$CONFIG_DIR/settings.json"
            if [ -f "$MCP_FILE" ]; then
                if grep -q "jimmer-docs" "$MCP_FILE" 2>/dev/null; then
                    log_skip "jimmer-docs already in $MCP_FILE"
                else
                    log_info "$MCP_FILE exists. Add this to your mcpServers section:"
                    echo -e "    ${DIM}${MCP_SERVER_BLOCK}${NC}"
                fi
            else
                cat > "$MCP_FILE" << MCPEOF
{
  "mcpServers": {
    "jimmer-docs": {
      "command": "node",
      "args": ["$MCP_DIST"]
    }
  }
}
MCPEOF
                log_install "created $MCP_FILE"
            fi
        fi

        echo ""
        log_info "Tool: ${CYAN}jimmer_docs_search${NC}"
    fi
fi

echo ""
echo -e "${BOLD}============================================================${NC}"
echo -e "${BOLD}Done!${NC}  ${GREEN}+$INSTALLED installed${NC}  ${YELLOW}~$UPDATED updated${NC}  ${DIM}-$SKIPPED skipped${NC}"
echo ""
echo -e "  Skills:  ${CYAN}$SKILL_COUNT installed${NC} into ${CYAN}$SKILLS_DIR${NC}"
echo -e "  Tasks:   ${CYAN}jimmer-entity${NC} ${CYAN}jimmer-dto${NC} ${CYAN}jimmer-query${NC} ${CYAN}jimmer-migrations${NC} ${CYAN}jimmer-debug${NC}"
if [ "$INSTALL_MCP" = true ]; then
    echo -e "  MCP:     ${CYAN}jimmer_docs_search${NC}"
fi
echo ""
echo -e "Ask your agent naturally. Skills load on demand from frontmatter descriptions."
