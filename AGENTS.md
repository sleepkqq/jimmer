# Jimmer fork — Architecture rules

This repository owns the ORM and its Quarkus integration. The Gradle build is under `project/`; fork coordinates, compatibility and releases are documented in [FORK.md](FORK.md).

## Ownership and SQL correctness

- Fix ORM SQL generation and JDBC type handling in `project/jimmer-sql`, not with service-specific queries, save-then-find workarounds or disabled save-returning optimizations. Trace neighboring insert/update/upsert callers before changing a shared renderer.
- Render physical SQL types from existing getter/property metadata and the configured dialect. Runtime JDBC values can erase the distinction between `Instant` and `LocalDateTime`; never infer the database timestamp type solely from `java.sql.Timestamp`.
- Derived `VALUES` sources do not inherit target-column types. Preserve temporal types explicitly for update-returning sources, including nullable values, while keeping insert/upsert semantics and optimistic-lock predicates intact.
- Keep SQL-core code compatible with its Java 8 target and follow the surrounding Java style. Reuse existing dialect capabilities and SQL builders rather than adding application-specific branches or configuration switches.

## Regression checks

- Extend the existing mutation tests and fixtures: `project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/mutation/ModifiedFetcherTest.java`, common `NativeDatabases`, and `project/jimmer-sql-test/jimmer-sql-test-model`. Do not create a new test application or manual application-JAR launcher.
- Type-inference defects need a real database reproduction, not only an expected-SQL string or H2 run. Preserve returned values, null semantics, temporal precision/time zones and concurrency behavior. Native database fixtures use `jimmer-sql-test-native-database`; distinguish executed tests from skipped checks.
- For temporal save-returning changes, run `:jimmer-sql:test --tests '*ModifiedFetcherTest' --tests '*ModifiedAssociationFetcherTest' --tests '*SaveKeyPropsTest'` from `project/`, with the PostgreSQL fixture configured for its native cases.
- Align all consumer fork artifacts, including SQL/Kotlin, processors and Quarkus integration, on one released version. Verify resolved dependencies; a catalog's SQL version does not establish the extension's transitive version.
- Record durable architecture corrections here and in relevant documentation. Commit, push, tag and publication require explicit user authorization; a local fix is not yet available to consumers.
