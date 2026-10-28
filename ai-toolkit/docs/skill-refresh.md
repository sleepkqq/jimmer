# Skill refresh evidence

Reviewed: 2026-09-20. This historical refresh now lives under `versions/0.12.2/`.
The subsequent release-specific layout intentionally puts version boundaries in
skill instructions and pins provenance in each release's `sources.json`.
For the 0.9.111 audit and paired examples see [its evidence](../versions/0.9.111/README.md).

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
