# Jimmer fork — agent guide

This repository is the `sleepkqq/jimmer` fork of `babyfish-ct/jimmer`: the ORM, its Quarkus
extension and the consumer AI toolkit, published as `com.github.sleepkqq.jimmer:*`.
Release history, migration notes and compatibility live in [FORK.md](FORK.md).
`CLAUDE.md` is a symlink to this file.

## Boundaries

- **Upstream is read-only.** Never push, open PRs or issues, comment, review or label on
  `babyfish-ct/jimmer`, `belovaf/jimmer` or any other third-party fork. Only `git fetch` them.
  Every GitHub write targets `sleepkqq/jimmer` explicitly, and only when the owner asks.
- **Public repository.** Keep private consumer names, packages, schemas, source snippets and
  implementation details out of tracked code, tests, docs and commit messages. Core changes
  are generic; regressions use anonymized existing Jimmer fixtures.
- Commit, push, tag and publication require explicit owner authorization. A local fix is not
  available to consumers until a release is published.
- Module-level `AGENTS.md` files from upstream (`project/jimmer-ksp`, `project/jimmer-ddl-compiler`)
  apply inside those modules.

## Layout

| Path | Contents |
|---|---|
| `project/` | Gradle 9.8.2 build (JDK 25). Conventions in `project/buildSrc/src/main/kotlin/org/babyfish/jimmer/`. |
| `project/jimmer-core`, `jimmer-core-kotlin` | Immutable model, metadata, drafts, Jackson integration. |
| `project/jimmer-sql`, `jimmer-sql-kotlin` | SQL DSL, query AST (`ast/impl/query`), save pipeline (`ast/impl/mutation`), fetchers/loaders, caches, dialects, runtime. |
| `project/jimmer-apt`, `jimmer-ksp`, `jimmer-dto-compiler` | Java/Kotlin code generation and the shared DTO language compiler. |
| `project/jimmer-client*`, `jimmer-spring-boot-starter`, `jimmer-ddl-compiler`, `jimmer-bom` | Client/OpenAPI generators, Spring integration, DDL generation, BOM. |
| `project/jimmer-quarkus/{runtime,deployment,integration-tests}` | Fork-owned Quarkus extension (Java/Kotlin target 25). |
| `project/jimmer-sql-test/*` | Shared test entities and DTOs (APT, KSP) and JDBC test support. |
| `ai-toolkit/` | Skills for library **consumers**; not instructions for working on this repo. |
| `.claude/skills/jimmer-dev-*` | Skills for working **on** this repo: change workflow, tests, review, cache internals, upstream sync, release. |

Core modules compile with `sourceCompatibility = 1.8`: no `var`, records, `List.of`,
`Map.of`, `String.isBlank`, `Optional.isEmpty` or other post-8 APIs in `jimmer-core`,
`jimmer-sql` and processors. Only the Quarkus extension targets 25.

## Build and test

Run Gradle from `project/`:

```bash
./gradlew :jimmer-sql:test --tests 'org.babyfish.jimmer.sql.query.SubQueryGlobalFilterTest'
./gradlew :jimmer-sql-kotlin:test --tests '*VirtualPredicateTest'
./gradlew :jimmer-ksp:test :jimmer-dto-compiler:test   # jimmer-apt has no tests; its output is compiled via jimmer-sql-test
./gradlew :jimmer-quarkus:integration-tests:test
```

- Java SQL tests use JUnit 5; Kotlin SQL tests use JUnit 4. H2 is initialized from
  `jimmer-sql/src/test/resources/database.sql`.
- Native databases run only when the environment variable `jimmer-sql-test-native-database`
  is set (for example `env jimmer-sql-test-native-database=postgres ./gradlew ...`); services
  are in `jimmer-sql/src/test/resources/docker-compose.yml`. Otherwise native cases are
  skipped — report them as skipped, never as passed.
- Run focused classes, not the full suite, unless the change is cross-cutting. At most two
  heavy Gradle runs at a time on the development host.

## Engineering standard

The bar is the upstream maintainer's (belovaf) practice. Use `jimmer-dev-change` for the
full workflow; the rules:

1. **Reproduce first.** A failing test in the owning module's existing test class or fixture
   precedes the fix. Type-inference, temporal, JSON and dialect defects need a native-database
   reproduction, not only expected SQL or H2.
2. **Fix the root cause in the shared layer** (query AST, save pipeline, fetcher/loader,
   dialect, processor), not in a caller, a consumer workaround or a disabled optimization.
   Trace neighbouring callers before changing a shared renderer or reader.
3. **Smallest correct change.** Typical fixes touch tens of production lines; tests are
   two to three times larger. Large diffs are reserved for a coherent new API across all layers.
4. **Reuse and delete.** Compose existing infrastructure (`KeyMatcher`, `FetcherUtil`,
   `ImmutableObjects`, dialect capabilities, SQL builders). When two paths do the same thing,
   lift one implementation and delete the other; never keep "both paths" as a hedge.
   No configuration switch for information the model already derives.
5. **Minimal hooks in upstream classes.** Widen an existing guard, extend an existing dispatch,
   add a default method — keep fork logic in fork-owned classes so upstream syncs stay mergeable.
6. **Parity.** Java and Kotlin APIs, docs and tests move together; APT and KSP move together.
7. **Shape.** Package-private `final` implementation classes; small `private static` helpers;
   early returns; named predicates (`isRootVisibilityKnown(...)`) instead of long inline
   conditions; immutable data with copy-on-change; JDK exceptions whose message names the
   offending property. Methods past ~80 lines or classes past ~600 lines need a reason.
8. **Comments state invariants, not narration.** Javadoc goes on public API and SPI contracts
   and states semantics and exceptions. Implementation types live in `.impl` packages (public
   there only for cross-package access, as upstream does); API packages such as `sql.cache`
   or `sql.ast.query` hold supported contracts only.
9. **Public API is minimal.** No silent no-op defaults on public interfaces; contract errors
   fail at the API call, shape-dependent limitations decline at execution.

## Commits

Follow upstream style: imperative, capitalised subject without a conventional-commit prefix,
no trailing period, optional `(#issue)`. The body says what changed and why, which invariants
are preserved, and ends with a validation ledger:

```text
Preserve explicit live logical-delete values on save (#1359)

<what and why; invariants kept>

Validation: 27 new H2 cases and 144 neighbouring tests passed.
PostgreSQL regression added but not run locally.
```

One commit is one behaviour. Build, CI and tooling changes go in separate commits.
Version bumps and code-generation contract changes are declared in the commit that needs them.

## SQL correctness invariants

- Render physical SQL types from getter/property metadata and the configured dialect. Runtime
  JDBC values can erase the distinction between `Instant` and `LocalDateTime`; never infer the
  database timestamp type solely from `java.sql.Timestamp`.
- Derived `VALUES` sources do not inherit target-column types. Preserve temporal and JSON types
  explicitly for update-returning sources, including nullable values and all-null batches,
  while keeping insert/upsert semantics and optimistic-lock predicates intact.
- JSON getter metadata can have no SQL type name. For JSON source casts, prefer the physical
  column type and fall back to `Dialect.getJsonSqlType()`; do not map every `PGobject` to JSON,
  the driver also uses it for other PostgreSQL types.
- Prefer fully loaded non-nullable key groups before nullable alternatives, preserving
  property-count order within each category. Entity and mutation-shape matching share this
  metadata-based order, so batch deduplication, lookup and SQL use the same key.
- Save-result shape matching treats a null child fetcher as an ID-only entity reference,
  distinct from a null root fetcher. Reuse satisfied to-one ID views and known concrete
  discriminators in residual seeds; APPEND collections still require their stored snapshot.
  Never infer collection completeness from submitted children.
- Count/reselect shares a mutable query with its original projection. Prepare global filters
  in both current and retained selections before freezing it; projection-only subqueries stay
  filtered during pagination.
- Bulk update-returning validates raw owning-reference IDs against their parent FK property.
  Preserve physical-table/inheritance checks; inverse references and joined target properties
  are not columns of the mutation target.

Regression homes: save-returning and temporal cases extend `sql/mutation/ModifiedFetcherTest`
(run with `*ModifiedAssociationFetcherTest` and `*SaveKeyPropsTest`, PostgreSQL configured);
JSON cases extend `sql/json/ScalarProviderTest` with the `JsonWrapper` fixture, covering
`@Serialized` and custom providers, non-null-to-SQL-null, mixed/all-null batches and
database-read values, distinguishing SQL `NULL` from JSON `null`.

## Caches

Object caches are single-view content caches, not authorization or query-result caches: SQL
stays authoritative for membership, visibility, order and pagination, and anything uncertain
falls back to the complete original query. Shared caches only receive content proven to come
from an inactive transaction on the executing manager's connection. `BINLOG_ONLY` clients report their
own mutated keys through `MutationKeyListener`; the connection manager evicts them only after
commit, never before, and a cache error never fails the commit. Load
`jimmer-dev-cache-internals` before changing anything under `sql/cache`, `EntitiesImpl`,
`ObjectCacheQueryExecution`, `CacheContentMask`, loaders, or the Quarkus cache runtime; it holds
the full invariant list and known debt.

## Consumers and releases

All consumer artifacts — SQL, Kotlin, APT/KSP processors, Quarkus runtime/deployment — must
resolve to one released fork version; check the resolved dependency tree, since a catalog's SQL
version does not establish the extension's transitive version. Use `jimmer-dev-release` for a
release and `jimmer-dev-upstream-sync` for merging upstream `dev`. Record durable architecture
corrections in this file or the matching skill, and user-visible behaviour in FORK.md.
