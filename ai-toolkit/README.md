# Jimmer AI Toolkit

Skills-native toolkit that helps AI coding agents work with Jimmer ORM without keeping large context files always loaded.

## What's Included

### Skills (`skills/`)

One skill set, kept current with this repository: the `sleepkqq/jimmer` fork
(`com.github.sleepkqq.jimmer:*`, which contains every upstream `babyfish-ct/jimmer`
change it is based on). Behavior that exists only in the fork — the unified Quarkus
module, object-cache admission, SQLState translation — is labeled as fork-only, and
"since X" notes mark changes that matter when a consumer is on an older release.

Task skills:
- `jimmer-entity` — entity creation/change workflow with interface, association, key, base type, and repository rules
- `jimmer-dto` — `.dto` workflow and syntax for Views, Inputs, Specifications, input handle modes, fold/flat, aliases, and configurations
- `jimmer-query` — typed query workflow for filters, pagination, `TABLE_EX`, aggregates, typed tuples, base tables, and bulk operations
- `jimmer-migrations` — Liquibase/Flyway migrations aligned with Jimmer annotations and DB constraints
- `jimmer-debug` — diagnosis workflow for save, dissociation, key, loading, optimistic lock, and query errors
- `jimmer-dml` — typed insert/upsert from query sources, conflict handling, returning, and dialect fallbacks
- `jimmer-inheritance` — polymorphic entity mapping, subtype queries/DTOs, and controlled type transitions
- `jimmer-filters` — global visibility filters, cache parameters, and mutation-boundary checks

Reference skills:
- `jimmer-repositories` — repository/service boundaries, built-ins, and `saveCommand` return patterns
- `jimmer-fetchers` — Fetcher API, generated code, View-vs-Fetcher decisions, and N+1 batch loading
- `jimmer-save-modes` — `SaveMode`, `AssociatedSaveMode`, key matching, upsert masks, save command options, `QueryReason`
- `jimmer-advanced-mappings` — `@Formula`, `@IdView`, `@ManyToManyView`, `@LogicalDeleted`, `@Embeddable`, `@Serialized`, `@MapsId`, transient resolvers
- `jimmer-kotlin` — Kotlin entity/query/save/KSP patterns
- `jimmer-quarkus` — the unified [sleepkqq/jimmer](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus) module: aligned dependencies, CDI/JTA, native support, and cache readiness
- `jimmer-caching` — object/property caches, CDC invalidation, multi-view filters, and Quarkus Redis lifecycle
- `jimmer-config` — framework-specific keys, defaults, and build/runtime scope
- `jimmer-performance` — measured SQL/QueryReason diagnosis, result fetching, batching, and pagination

Align the consumer's runtime **and processor** artifacts on one fork release.
Detailed syntax lives in `GUIDE.md` or `references/`.

`BINLOG_ONLY` requires a real delivery/invalidation path when used for cache
consistency.

```text
skills/                   # 17 installable skills
examples/java/            # executable Java/APT/H2 fixture
mcp/                      # docs/source MCP server
tests/                    # validation, install tests and historical evaluations
```

### Skill Scripts

Scripts live inside the skills that use them:
- `jimmer-entity/scripts/` — project scan + compile helpers
- `jimmer-query/scripts/` — project scan + compile helpers
- `jimmer-dto/scripts/` — compile helper
- `jimmer-debug/scripts/` — compile helper
- `jimmer-migrations/scripts/` — migration discovery + compile helpers

### MCP Server (`mcp/jimmer-docs-mcp/`)

- `jimmer_docs_search` — find relevant sections in the official documentation repository
- `jimmer_docs_read` — read unchanged documentation/source with commit-pinned continuation
- `jimmer_source_lookup` — locate official Jimmer API types at a branch, tag, or commit

The server preserves MDX examples and exposes source revisions, refresh status, and
imported fragments. See the [MCP guide](mcp/jimmer-docs-mcp/README.md).

## Prerequisites

- Node.js 18+ — required only when using `--mcp`
- Git 2.29+ — required only when using `--mcp`
- Agent CLI with skills support: OpenCode by default, or Claude Code/Qwen Code/GigaCode/Codex

## Installation

Skills install into the agent's **user config**, so every project can use them. Safe to run repeatedly.

```bash
chmod +x install.sh
./install.sh                    # OpenCode (default)
./install.sh --mcp              # skills + MCP docs server
./install.sh --tool claude      # install for Claude Code
./install.sh --tool claude --mcp
./install.sh --tool qwen
./install.sh --tool gigacode
./install.sh --tool codex       # Codex global skills (~/.agents/skills)
./install.sh --tool codex --mcp # native codex mcp registration
```

### Options

```text
./install.sh [OPTIONS]

  --tool opencode|claude|qwen|gigacode|codex Target CLI tool (default: opencode)
  --symlink                                  Use symlinks instead of copies
  --mcp                                      Build and install the MCP docs server
```

With `--mcp`, the installer builds the server (`npm install && npm run bundle`) and
registers it. Claude Code uses `claude mcp add --scope user`; Codex uses native
`codex mcp list --json` and `codex mcp add`; the remaining tools use their config file.

## Installed Layout

Skills land in the selected tool's user-config skills directory:

Reinstalling replaces the same `jimmer-*` topics; other skills are preserved.
Start a fresh agent session afterwards to reload descriptions.

| Tool       | Skills directory                |
|------------|---------------------------------|
| opencode   | `~/.config/opencode/skills/`    |
| claude     | `~/.claude/skills/`             |
| qwen       | `~/.qwen/skills/`               |
| gigacode   | `~/.gigacode/skills/`           |
| codex      | `~/.agents/skills/`              |

```text
<skills-dir>/
  jimmer-entity/SKILL.md
  jimmer-dto/SKILL.md
  jimmer-query/SKILL.md
  jimmer-migrations/SKILL.md
  jimmer-debug/SKILL.md
  jimmer-dml/SKILL.md
  jimmer-inheritance/SKILL.md
  jimmer-filters/SKILL.md
  jimmer-repositories/SKILL.md
  jimmer-fetchers/SKILL.md
  jimmer-save-modes/SKILL.md
  jimmer-kotlin/SKILL.md
  jimmer-quarkus/SKILL.md
  jimmer-advanced-mappings/SKILL.md
  jimmer-caching/SKILL.md
  jimmer-config/SKILL.md
  jimmer-performance/SKILL.md
  jimmer-entity/scripts/scan-project.sh
  jimmer-query/scripts/scan-project.sh
  jimmer-dto/scripts/compile.sh
  jimmer-migrations/scripts/next-migration.sh
  jimmer-debug/scripts/compile.sh
```

No always-loaded context imports are appended to agent entry files. Agents discover and load skills by frontmatter descriptions and triggers.

## Usage

Ask naturally:
- "Design a Jimmer entity for this domain object"
- "Generate View and Input DTOs for this entity"
- "Build a Jimmer query with filters and pagination"
- "Create migration for this entity change"
- "Diagnose this Jimmer save error"

Skills call their own local `scripts/` helpers when project discovery or compile verification is needed.

## Notes

Skill examples use synthetic domains or public upstream examples. Replace them with project-specific names only inside the target project.

## Maintenance and checks

- [Research and source ledger](docs/skill-refresh.md) — public source revisions, corrections, and authoring decisions.
- [Skill canaries](tests/skill-evals/README.md) — reproducible baseline/updated comparisons through OpenCode using the configured model provider.
- [Measured refresh results](tests/skill-evals/RESULTS.md) — assertion grades, retained answers, compiler feedback and evaluation limits.
- [Revision-to-revision evaluation](tests/skill-evals/ROUND2.md) — independent masked grading, unseen transfer tasks, regressions and executable mutation checks.
- [Cross-model evaluation](tests/skill-evals/CROSS-MODEL.md) — 192 answers from Luna, GPT-5.5 and Astra, with empty/reference comparisons and retained regressions.
- [Transfer-focused refinement](docs/skill-refinement-method.md) — source-driven examples, frozen comparisons and independent validation without benchmark-specific answer templates.

```bash
python3 tests/validate-skills.py
bash tests/test-codex-install.sh
gradle -p examples/java --no-daemon --console=plain run
```

When a consumer pins an older fork release, read source at that tag with
`jimmer_source_lookup` (`ref: "<tag>"`) instead of assuming current behavior.
