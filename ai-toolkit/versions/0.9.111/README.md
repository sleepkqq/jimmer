# Jimmer 0.9.111

All 17 toolkit topics, authored for the **official** release rather than relabeled
from current documentation. Java and Kotlin use the same capabilities but their
own generated APIs. Install only this set for a 0.9.111 consumer:

```bash
./install.sh --version 0.9.111
./install.sh --version 0.9.111 --tool codex --symlink
```

## Provenance

- Repository: [`babyfish-ct/jimmer`](https://github.com/babyfish-ct/jimmer).
- Tag: [`v0.9.111`](https://github.com/babyfish-ct/jimmer/tree/v0.9.111).
- Peeled commit: `03cc7aac141c8be951c37535f42c341951e83025`.
- Machine-readable pin: [sources.json](sources.json).
- Audit date: 2026-10-01. Context7 resolved the official library but its documentation
  query failed; the tag's source, grammar and upstream tests supplied the evidence.
- Skills link to release-tagged source. For immutable MCP reads, pass the full
  commit above to `jimmer_source_lookup` and use the returned revision for reads.
  The documentation repository does not share Jimmer's release tags automatically.

## Release boundaries checked in source

Paths below are relative to the upstream `project/` directory.

| Area | 0.9.111 contract | Source inspected |
|---|---|---|
| Trigger modes | All three modes supported; no transaction-mode deprecation | `jimmer-sql/.../event/TriggerType.java` |
| Save results | Modified/original entity, counts, Fetcher/View execution; **no `isAccepted`** | `SimpleSaveResult.java`, `SimpleEntitySaveCommand.java`, Kotlin save APIs |
| Conditional writes | Optimistic locking or explicit typed update predicate/count; no `setUpdateWhere` or `VersionMode` | `AbstractEntitySaveCommand.java`, `KSaveCommandPartialDsl.kt` |
| Bulk DML | Typed update/delete and graph batches; no query-derived insert/upsert or mutation returning | `JSqlClient.java`, `KSqlClient.kt`, mutation interfaces |
| Queries | Page/slice/exists, base queries/CTEs, `@TypedTuple` projections, `forEach`; no JDBC query `stream()` | root/base query interfaces, tuple processors and Java/Kotlin base-query tests |
| DTO grammar | Nested Views/Inputs/Specifications and input presence modes; no reusable-DTO arrows, fragments, `for`, `fold` or polymorphic branches | `jimmer-dto-compiler/src/main/antlr/.../Dto.g4`, `DtoPropBuilder.java` |
| Mapping | Mapped superclasses, formulas, ID views, logical delete, embedded/JSON values; no polymorphic entity inheritance, `MapsId`, `DatabaseDefault` | `jimmer-core/src/main/java/.../sql`, processor generators |
| Kotlin construction | `new(Entity::class).by { ... }` and the generated `Entity { ... }` factory in the model package | KSP draft/producer generators and upstream Kotlin tests |
| Java facades | APT generates `Immutables.create<Type>(...)`, `Tables`, `TableExes` and `Fetchers` per common package | `jimmer-apt/.../entry/{EntryProcessor,ImmutablesGenerator,TablesGenerator,FetchersGenerator}.java` |
| Jackson | Jackson 2 `org.babyfish.jimmer.jackson.ImmutableModule` | core Jackson package |
| Filters/caches | Sorted view parameters and event-affecting contract; Java/Kotlin factories | `CacheableFilter.java`, `KCacheableFilter.kt`, `KSqlClientDsl.kt` |
| Configuration | Spring `jimmer.*` source-verified defaults | `jimmer-spring-boot-starter/.../cfg/JimmerProperties.java` |
| Quarkus / DDL compiler | Neither module is shipped by the official tag | `project/settings.gradle.kts` and module tree |

Topics keep stable names across releases; each release's skills describe the
usage that fits it, with a compact note where an outcome needs a different API,
instead of a changelog of other releases. The Quarkus skill requires checking
the consumer's **separately versioned extension** rather than assuming official
coordinates or a unified module.

## Java and Kotlin examples

[`examples/`](examples/README.md) contains independent Java/APT and Kotlin/KSP
modules, identical DTO syntax and a shared H2 schema. The runnable checks exercise
the documented drafts, Views, Fetchers, dynamic queries, Specification, base
queries, collection membership, page/slice/traversal, PATCH presence, association
MERGE/REPLACE, conditional arithmetic, stale optimistic saves, tenant filtering
and rollback.

```bash
# JDK 21, Gradle 8.12.1, network access to Maven Central / plugin portal
gradle -p versions/0.9.111/examples --no-daemon --console=plain check
```

These are compiler and H2 behavior checks, not production-dialect concurrency,
Redis/CDC, Spring repository startup, Quarkus integration or native-image tests.
The existing model-output evaluations under `tests/skill-evals` target the newer
set. Separate [0.9.111 quick model-output canaries](evals/README.md) now exercise
generated answers and compile/run their Java/Kotlin code; their initial failure
and targeted skill correction are retained independently, and a
simplified-prompt round on `deepseek-v4.1-flash` passed 28/28 after three
documented guidance fixes (creation facades, save-time dissociation, typed-tuple
entry point).

### Verification recorded on 2026-10-01

| Check | Result |
|---|---|
| `gradle ... check`, JDK 21 / Gradle 8.12.1 | Java APT and Kotlin KSP compilation plus both H2 assertion programs passed |
| `python3 tests/validate-skills.py` | Both releases, 34 skills, local links, 32 historical + 7 release canary definitions and 26 helper scripts validated |
| 0.9.111 quick canaries, `deepseek-v4.1-flash` | 28/28 in the simplified-prompt round and again after the facade/version-agnostic cleanup; graded Java/Kotlin answers and `StorePatch` compiled and passed H2 (`evals/results/2026-10-01-flash*/verify.log`) |
| Usage-first canaries, `deepseek-v4.1-flash` (2026-10-02) | 28/28 after the usage-first skill rewrite; one `Page`-import gap found and fixed in `jimmer-query`; graded by inspection (compile verifier not rerun) |
| `bash tests/test-version-install.sh` | Both releases, all five tools, copy/symlink switching, repeat installs and old dangling-link migration passed |
| `bash tests/test-codex-install.sh` | Default installation and stubbed MCP registration passed |
| Retained `versions/0.12.2/examples/java` | Existing APT/H2 assertions passed after relocation |

Compiler feedback was applied to the examples **and** guidance: `Formula` belongs
to `org.babyfish.jimmer`, KSP requires one model declaration per Kotlin source
file, and default Kotlin DTO properties are immutable constructor values. The
Kotlin base-query example uses this tag's `baseTableSymbol` / `selections.add`
API rather than later syntax.

A full source re-audit of this set on 2026-10-01 (after the first canary round)
found two wrong absence claims, now corrected in place: this release **does**
support `@TypedTuple` projections (`TypedTuple.java`, the APT/KSP `tuple`
processors and upstream `TypedTupleTest`), and KSP **does** generate the
`Entity { ... }` factory in the model package in addition to
`new(Entity::class).by { ... }` (`DraftGenerator.addNewByFun` with
`withCreator = false`). The DTO guide also names the supported
`#allReferences` macro and the Java-only `@EnableDtoGeneration` trigger.

The later 2026-10-01 model-output round on `deepseek-v4.1-flash` exposed three
guidance gaps, now documented and source-checked: Java creation goes through the
APT-generated `Immutables.createBook(...)` facade (`BookDraft.$.produce(...)` is
the equivalent `$`-style call, and Kotlin has the `Book { ... }` factory);
save-time dissociation of a removed child needs `SET_NULL`/`DELETE` on the owning
reference even when its FK is nullable (`DetachOptions.getDissociateAction`,
`ChildTableOperator.disconnect`); and a generated `@TypedTuple` mapper chain
starts at the static method named after the first declared component
(`TypedTupleGenerator.generateFirstMethod`, upstream `TypedTupleTest`).

## Adding another release

Create `versions/<official-version>/` with its own `sources.json`, complete
`skills/` tree and focused examples. Resolve the official tag to a commit and
audit differences against that source before copying guidance. Keep installed
skill names stable and keep skill text release-agnostic: no pinned version in
headings, prose or frontmatter descriptions (the release directory and
`sources.json` carry it), while `Sources:` links stay pinned to the release tag.
The installer lists available directories; shared validation checks metadata,
topic parity and local links without loading every release into an agent at
once.
