# Jimmer fork — Architecture rules

This repository owns the ORM and its Quarkus integration. The Gradle build is under `project/`; fork coordinates, compatibility and releases are documented in [FORK.md](FORK.md).

## Ownership and SQL correctness

- Fix ORM SQL generation and JDBC type handling in `project/jimmer-sql`, not with service-specific queries, save-then-find workarounds or disabled save-returning optimizations. Trace neighboring insert/update/upsert callers before changing a shared renderer.
- Render physical SQL types from existing getter/property metadata and the configured dialect. Runtime JDBC values can erase the distinction between `Instant` and `LocalDateTime`; never infer the database timestamp type solely from `java.sql.Timestamp`.
- Derived `VALUES` sources do not inherit target-column types. Preserve temporal and JSON types explicitly for update-returning sources, including nullable values and all-null batches, while keeping insert/upsert semantics and optimistic-lock predicates intact.
- JSON getter metadata can have no SQL type name. For JSON source casts, prefer the physical column type and fall back to the dialect's JSON SQL type; do not map every `PGobject` to JSON because the driver also uses it for other PostgreSQL types.
- Keep SQL-core code compatible with its Java 8 target and follow the surrounding Java style. Reuse existing dialect capabilities and SQL builders rather than adding application-specific branches or configuration switches.
- Save-result shape matching treats a null child fetcher as an ID-only entity reference, distinct from a null root fetcher. Reuse satisfied to-one ID views and known concrete discriminators in residual seeds; APPEND collections still require their stored snapshot. Never infer collection completeness from submitted children.
- Count/reselect shares a mutable query with its original projection. Prepare global filters in both current and retained selections before freezing it; projection-only subqueries must remain filtered during pagination.
- Object caches are single-view content caches, not authorization caches. Public filtered ID reads must check visible IDs before cache lookup, including negative hits; `forUpdate` must execute a locking database read. Internal association loaders retain their existing filtered-ID loading path.
- Propagate filter parameters through every tier of a parameterized cache chain. Declarative Quarkus factories use native `FilterState` and multi-view binders for filtered associations in LOCAL_ONLY, REMOTE_ONLY and FULL modes. Invalidation deletes every view for an owner key.

## Regression checks

- Extend the existing mutation tests and fixtures: `project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/mutation/ModifiedFetcherTest.java`, common `NativeDatabases`, and `project/jimmer-sql-test/jimmer-sql-test-model`. Do not create a new test application or manual application-JAR launcher.
- Type-inference defects need a real database reproduction, not only an expected-SQL string or H2 run. Preserve returned values, null semantics, temporal precision/time zones and concurrency behavior. Native database fixtures use `jimmer-sql-test-native-database`; distinguish executed tests from skipped checks.
- For temporal save-returning changes, run `:jimmer-sql:test --tests '*ModifiedFetcherTest' --tests '*ModifiedAssociationFetcherTest' --tests '*SaveKeyPropsTest'` from `project/`, with the PostgreSQL fixture configured for its native cases.
- JSON save-returning regressions belong in the existing `sql/json/ScalarProviderTest` and `JsonWrapper` fixture. Cover both `@Serialized` and custom JSON scalar providers, non-null-to-SQL-null updates, mixed/all-null batches and database-read return values; distinguish SQL `NULL` from JSON `null`.
- Align all consumer fork artifacts, including SQL/Kotlin, processors and Quarkus integration, on one released version. Verify resolved dependencies; a catalog's SQL version does not establish the extension's transitive version.
- Record durable architecture corrections here and in relevant documentation. Commit, push, tag and publication require explicit user authorization; a local fix is not yet available to consumers.
