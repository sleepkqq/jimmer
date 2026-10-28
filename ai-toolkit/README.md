# Jimmer AI Toolkit

Skills-native toolkit that helps AI coding agents work with Jimmer ORM without keeping large context files always loaded.

## What's Included

### Release-specific skills (`versions/<version>/skills/`)

Choose the **official Jimmer release used by the application**. Each release has
the same 17 topics, version-specific instructions and its own source manifest.

| Release | Scope |
|---|---|
| [0.9.111](versions/0.9.111/README.md) | Official `v0.9.111`, paired Java/APT and Kotlin/KSP examples, explicit boundaries for unsupported later APIs |
| [0.12.2](versions/0.12.2/README.md) | Existing refreshed set, based on official `v0.12.2`; separately identified unified Quarkus **fork** guidance retained |

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

Resolve the consumer's runtime **and processor** versions before selecting a set.
Detailed syntax lives in `GUIDE.md` or `references/`; each installed skill identifies
its release in frontmatter. Topic descriptions above summarize the newer set;
older releases explain supported alternatives where an API is absent.

`BINLOG_ONLY` requires a real delivery/invalidation path when used for cache
consistency. Skills describe the usage that fits their release instead of a
cross-release deprecation changelog.

```text
versions/
  0.9.111/
    sources.json          # official tag + exact commit
    skills/               # complete, independently installable set
    examples/             # Java/APT + Kotlin/KSP + H2 checks
  0.12.2/
    sources.json          # official source and separate fork provenance
    skills/
    examples/java/        # retained executable refresh fixture
mcp/                      # shared, supports source lookup by release ref
tests/                    # shared validation/install tests and historical evaluations
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
./install.sh                    # 0.12.2 skills, OpenCode (default)
./install.sh --list-versions
./install.sh --version 0.9.111   # official 0.9.111 skills
./install.sh --version 0.9.111 --tool codex
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
  --version VERSION                          Jimmer release (default: 0.12.2)
  --list-versions                            List bundled release sets
  --symlink                                  Use symlinks instead of copies
  --mcp                                      Build and install the MCP docs server
```

With `--mcp`, the installer builds the server (`npm install && npm run bundle`) and
registers it. Claude Code uses `claude mcp add --scope user`; Codex uses native
`codex mcp list --json` and `codex mcp add`; the remaining tools use their config file.

## Installed Layout

Skills land in the selected tool's user-config skills directory:

One release is active per tool/user directory. Installing another release
replaces these same `jimmer-*` topics, so an agent does not discover conflicting
versions under duplicate names. Other skills are preserved. For project-local
agent setups, copy the chosen release's skill directories into that project's
supported skills location. Do not install all release trees recursively.

After updating from the old flat layout, rerun the installer to repair old
`--symlink` targets, then start a fresh agent session to reload descriptions.

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
- [0.9.111 compatibility and evidence](versions/0.9.111/README.md) — supported APIs, source paths and executable Java/Kotlin checks.
- [Skill canaries](tests/skill-evals/README.md) — reproducible baseline/updated comparisons through OpenCode using the configured model provider.
- [Measured refresh results](tests/skill-evals/RESULTS.md) — assertion grades, retained answers, compiler feedback and evaluation limits.
- [Revision-to-revision evaluation](tests/skill-evals/ROUND2.md) — independent masked grading, unseen transfer tasks, regressions and executable mutation checks.
- [Cross-model evaluation](tests/skill-evals/CROSS-MODEL.md) — 192 answers from Luna, GPT-5.5 and Astra, with empty/reference comparisons and retained regressions.
- [Transfer-focused refinement](docs/skill-refinement-method.md) — source-driven examples, frozen comparisons and independent validation without benchmark-specific answer templates.

```bash
python3 tests/validate-skills.py
bash tests/test-codex-install.sh
bash tests/test-version-install.sh
# JDK 21 and Gradle 8.12.1 (Kotlin 2.1.20 / matching KSP):
gradle -p versions/0.9.111/examples --no-daemon --console=plain check
```

The docs MCP is shared. For old releases use `jimmer_source_lookup` with
`ref: "v0.9.111"` (or the manifest's commit), then read at the returned revision.
Documentation-repository refs are independent: never assume current docs or a
matching-looking docs tag establish compatibility with that Jimmer release.
