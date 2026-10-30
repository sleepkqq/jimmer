# Skill refresh evidence

## 2026-10-30 refresh (current)

Tracks `sleepkqq/jimmer` **1.2.1**,
with upstream `dev` merged at `5ed2a810b` (`v0.12.4-4`, absorbing the DDL
association-key fix and the upstream `v0.12.5` version bump).

| Source | Snapshot |
|---|---|
| `sleepkqq/jimmer` | `1.2.1`; connection-free cache hits, own-write eviction, DTO admission fallback |
| `babyfish-ct/jimmer` | `5ed2a810b` (`v0.12.4-4`); every v0.12.4 code change plus the DDL association-key fix |
| Quarkus extension | `3.40.1.1`, JDK 25 (core modules stay Java 8); Kotlin `2.4.21`, KSP `2.3.12`, Gradle `9.8.2` |

Consumer-visible changes documented and, where contradicted, corrected:

- Quarkus acquires a pooled connection only at the first real JDBC call
  (`LazyConnection`); a read fully served by the object cache takes no
  connection, inside or outside `@Transactional`, and pool exhaustion surfaces
  at the first statement. Removed the implication that a transaction saves
  connections for cache-served reads.
- `findById`/`findByIds` (entity, Fetcher, DTO view) of a type whose only filter
  is the built-in logical-delete filter judge visibility on the cached row, so a
  warm read issues no SQL; user/reversed/ignored/sharding filters, `forUpdate`
  and content-only types keep the SQL check. Corrected the toolkit's previous
  "every ID read rechecks visibility with SQL" wording.
- `BINLOG_ONLY` clients evict their own object and owning-side association
  cache entries after commit (rollback: nothing; auto-commit: after the
  `execute` scope); bulk update/delete of a cached type pre-selects ids once.
  Inverse-side association caches and other services' writes still need CDC; no
  configuration key exists. Corrected "own writes stay stale until CDC".
- Automatic DTO/View admission that cannot be applied now continues on the
  ordinary cached path; only an explicit content hint keeps the isolated
  cache-disabled fallback.

Versions and snippets updated to `1.2.1`; historical "since 1.1.x" notes retained.
Claims were re-checked against `FORK.md`, `QuarkusConnectionManager`,
`LazyConnection`, `MutationKeyListener`, `MutationKeys`, `EntitiesImpl` and
`project/jimmer-quarkus/.claude/connection-management.md`. No LLM evaluation was run.

## 2026-10-28 refresh (historical)

Release-specific sets were removed: `skills/` now tracks this repository's latest
fork release. All 17 skills were re-verified claim by claim against:

| Source | Snapshot |
|---|---|
| `babyfish-ct/jimmer` | `v0.12.4` (`37da4250d`); every code change of `v0.12.2..v0.12.4` is in the fork (only version-bump commits differ) |
| `sleepkqq/jimmer` | `1.1.14` (`047055c84`): object-cache admission, `CacheAdmissionPolicy`, Quarkus config, SQLState translation |
| `babyfish-ct/jimmer-doc` | `10b60594e` |

Corrections: nonexistent `UnloadedPropertyException`, `fetchExists` and
`insertEntities` names; DDL-compiler snapshot directory layout; the Quarkus
database-validation key (`quarkus.jimmer.database-validation-mode`; the nested
`database-validation.mode` group is declared but never read); `@JoinSql` and
`setKeyProps` semantics. Additions cover #1076, #1095, #1168, #1203, #1246, #1274,
#1319, #1352, #1359, #1383, #1482, #1499, #1516, #1519, Kotlin transactional
client routing and fork cache admission. New Kotlin snippets were derived from
signatures and tests and were not compiled.

## 2026-09-20 refresh (historical)

## Sources inspected

| Source | Snapshot | Used for |
|---|---|---|
| [babyfish-ct/jimmer](https://github.com/babyfish-ct/jimmer) | release `v0.12.2`, commit `871c49379149c1950a836bb11b59d30a0babd476` | Core annotations, trigger deprecation, save/DML contracts and regression tests |
| [babyfish-ct/jimmer-doc](https://github.com/babyfish-ct/jimmer-doc) | `5f3ad502fd64faca7695a8073e55c170dd4fd53e` | DTO composition, inheritance, DML sources, save conditions/results, DDL generation, filters and streaming |
| [sleepkqq/jimmer](https://github.com/sleepkqq/jimmer) | local public-fork checkout `d16eb39ee49a7429093fcc8c8b6db22f57262f04`, unified release line `1.0.1` | **Quarkus authority:** `project/jimmer-quarkus`, config classes, repository integration, cache guard/tracker/fences, publication coordinates |

The Quarkus skill targets the unified module, not the legacy standalone extension. Public fork source was inspected directly; no private application schemas, identifiers, source excerpts or deployment details are part of these skills or tests.

Context7 was attempted but unavailable due to its monthly quota. Research continued through the Jimmer documentation MCP, official documentation checkout, public repositories and source/tests. Source takes precedence when prose is stale.

### Concrete source checks

- `TriggerType.java`: both transaction-trigger modes are `@Deprecated`; migration is requested and advanced mutation support may require `BINLOG_ONLY`. No removal release is specified. The documentation's cache-consistency page still describes `BOTH` as recommended; skills explicitly correct this disagreement.
- `SaveMode.java`, `SaveUpdateWhereTest.java`, save-result/update-where/version-mode documentation: rejected insert-if-absent roots, `isAccepted`, conditional eligibility, root-only assignment version mode, residual fetching and technical self-updates.
- `ImmutablePropImpl.java`: rejects `@OnDissociate` on non-reference properties and inverse one-to-one associations. An annotation on the inverse collection was a real error in the original example.
- `CacheableFilter.java`: sorted parameter map plus change-affecting contract, not just a cache-key annotation.
- Quarkus `JimmerRuntimeConfig`, `JimmerBuildTimeConfig`, datasource config classes, `JimmerCacheConfig`, `JimmerCacheGuardConfig`: key scopes, phases and defaults. Source corrects a stale nested-validation example in the module README.
- Quarkus cache source/module documentation: readiness distinguishes local subscription recovery from application-owned CDC freshness. Timeouts, reconnect and fill fences are separate mechanisms.
- Real APT compilation rejected redundant `?` on a nullable DTO property; source `DtoPropBuilder` confirms the rule. JSON round-trip checks also distinguish a non-null collection made optional from a required collection. The example and entry-point guidance were corrected and re-evaluated.
- `ImmutableModuleV2`/`ImmutableModuleV3` in current core source replace the unsuffixed module shown in older Jackson documentation. The standalone compile fixture supplies its own matching databind dependency; it is compile-only in Jimmer core.

## Skill-authoring research

Inspected concrete public examples and guidance rather than generating a generic style guide:

- [Agent Skills best practices](https://agentskills.io/skill-creation/best-practices): coherent task scope, defaults, gotchas, useful procedures and moderate detail.
- [Agent Skills evaluations](https://agentskills.io/skill-creation/evaluating-skills): snapshot old skills, fresh paired runs, explicit assertions, retained outputs and honest limitations.
- [Anthropic skill-creator](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md): execute/grade/revise loop and separate trigger/output evaluation.
- [Vercel React best practices](https://github.com/vercel-labs/agent-skills/blob/main/skills/react-best-practices/SKILL.md): prioritized high-impact rules and on-demand examples/references.

Applied patterns: precise descriptions, immediate domain-specific failure modes, short task procedure, conditional reference loading, source links and a concrete verification step. Existing useful guides/scripts were retained. Broad prohibitions were replaced where they changed valid ORM behavior: static Fetchers, direct SQL clients, legitimate conflict lookups and supported older DSL syntax are not errors by themselves.

## Scope of the refresh

All existing skills were inspected and revised. Added coherent missing areas: `jimmer-inheritance`, `jimmer-dml`, `jimmer-filters`. Quarkus cache lifecycle is a conditional reference under caching rather than another always-advertised skill.

Primary corrections: trigger deprecation/CDC migration, owning-side dissociation, save acceptance and result shape, conditional writes/version assignment, unified Quarkus coordinates and error defaults, DTO presence handling, safer public projections, typed query sources, streaming/resource lifetime and measured performance claims.

The benchmark protocol and results live under [tests/skill-evals](../tests/skill-evals/README.md). Output-quality canaries are not production workload benchmarks or proof that every example compiles on every consumer version.
