---
name: jimmer-quarkus
description: Integrates Jimmer with Quarkus through the sleepkqq/jimmer quarkus-jimmer module - aligned runtime, APT and KSP versions, CDI repositories, JTA transactions, named datasources, native images and Redis readiness. Use when the user wires or debugs Jimmer in a Quarkus app. Not for Spring Boot setup (jimmer-config) or cache design (jimmer-caching).
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Quarkus

Target the **unified `sleepkqq/jimmer` repository**, module `project/jimmer-quarkus`. Check the consumer's resolved dependencies and the module source, not the archived standalone extension or a different publisher's README. Java packages remain `org.babyfish.jimmer.*` and `io.quarkiverse.jimmer.*`; package names are not Maven coordinates.

## Dependencies first

Use one project-managed `jimmerVersion` for the runtime, BOM, APT/KSP and all direct Jimmer dependencies:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.sleepkqq.jimmer:quarkus-jimmer:$jimmerVersion")
    ksp("com.github.sleepkqq.jimmer:jimmer-ksp:$jimmerVersion")
    // Java instead of KSP:
    // annotationProcessor("com.github.sleepkqq.jimmer:jimmer-apt:$jimmerVersion")
}
```

Current fork release: `1.2.1` (JitPack, no credentials; a tag alone does not prove the artifacts were built). It contains every v0.12.4 code change (only the upstream version-bump commits are absent). Do not mix upstream `org.babyfish.jimmer` artifacts with the fork's runtime/processors. Match the Kotlin compiler, KSP plugin, Quarkus platform and JDK to the fork's compatibility/build metadata (1.2.1 is tested with Kotlin 2.4.21, KSP 2.3.12 and Quarkus 3.40.1.1; the Quarkus extension targets JDK 25 while core modules stay on Java 8) rather than copying fixed versions from an example. Inspect the resolved runtime **and processor** dependency graphs after migration.

## Differences From Spring Boot

| Aspect | Spring Boot | Quarkus |
|---|---|---|
| Integration | `jimmer-spring-boot-starter` | unified fork's `quarkus-jimmer` |
| Config prefix | `jimmer.*` | `quarkus.jimmer.*` |
| DI | `@Service`, `@Component` | `@ApplicationScoped` |
| REST | `@RestController`, `@GetMapping` | `@Path`, `@GET`, JAX-RS |
| Repository import | `org.babyfish.jimmer.spring.repository.*` | `io.quarkiverse.jimmer.runtime.repository.*` |

The extension generates `JRepository`/`KRepository` implementations at build time and provides synthetic CDI SQL clients/repositories. Use `Customizer`/`KCustomizer` only for needed builder changes; retain CDI discovery of interceptors, preprocessors, filters, translators and resolvers. A separate model JAR must be visible to Jandex.

`io.quarkiverse.jimmer.runtime.generator.UUIDv7IdGenerator` is available when the schema calls for time-ordered UUIDs. Preserve existing ID policies.

## Transaction and datasource boundary

- Use `jakarta.transaction.Transactional` for multi-statement writes/graph saves. The connection manager integrates Agroal with Narayana; a successful compile is not a transaction test.
- Inject `JSqlClient`/`KSqlClient` directly if that is the application's style; a repository wrapper is optional.
- Named SQL clients/repositories use `@io.quarkus.agroal.DataSource("name")`. Check entity/datasource association and qualify both repository and injection site.
- Jimmer uses JDBC: execute blocking work on an appropriate worker, not a reactive event-loop thread.
- Object-cache query admission requires a proven non-transactional read (JTA `NO_TRANSACTION`, auto-commit, connection owned by the manager). Reads inside `@Transactional` always run fresh SQL; do not wrap read-only endpoints in a transaction if they should use the cache.
- The extension acquires a pooled connection only at the first real JDBC call (1.2.1). A read fully served by the object cache takes no connection and enlists nothing, inside or outside `@Transactional`; wrapping reads in a transaction does not save connections for them, and pool-exhaustion errors now surface at the first statement instead of at the `execute` entry.
- With `BINLOG_ONLY` and caches, the writing instance sees its own committed writes: object and owning-side association cache entries of its own inserts, updates, upserts, deletes and middle-table changes are evicted after commit (rollback: nothing; auto-commit: after the `execute` scope). Bulk update/delete of a cached type pre-selects ids once; inverse-side association caches and writes from other services still rely on CDC.

## Resource Layer

```java
@Path("/domain-objects")
@ApplicationScoped
public class DomainObjectResource {
    @Inject
    DomainObjectService service;

    @GET
    public List<DomainObjectListView> findAll() {
        return service.findAll();
    }

    @POST
    public DomainObjectDetailView create(DomainObjectCreateInput input) {
        return service.create(input);
    }
}
```

## Service Layer

```java
@ApplicationScoped
public class DomainObjectService {
    @Inject
    DomainObjectRepository repository;

    // Put @Transactional on the service's write operation.
}
```

## Config

```yaml
quarkus:
  jimmer:
    language: kotlin            # java | kotlin
    show-sql: true
    pretty-sql: true
    database-validation-mode: ERROR  # validate a migrated schema; choose project policy
    trigger-type: BINLOG_ONLY        # requires real CDC for event-driven caching
  # entities in a separate module need Jandex visibility:
  index-dependency:
    model:
      group-id: com.example
      artifact-id: example-model
```

### Key registry

Read `jimmer-config` for the relevant key and its scope: only datasource-scoped options accept `quarkus.jimmer.<datasource-name>.<key>`. Dialect is derived from datasource metadata. Validation is read only from `quarkus.jimmer.database-validation-mode` (`JimmerRuntimeConfig.databaseValidationMode()`); the deprecated nested `database-validation.mode` group still parses but `JQuarkusSqlClient` never reads it, so it is silently ignored. The module README's config table (checked at 1.2.1) still lists the nested key; source wins.

The fork defaults to `constraint-violation-translatable=false` and `sql-state-exception-translator=true`: SQLState maps to `JimmerDataAccessException` subtypes. Enabling typed constraint investigation changes the error/performance contract; see `jimmer-debug`.

## Redis lifecycle

Use declarative `quarkus.jimmer.cache.entities`. With `quarkus.jimmer.cache.guard.enabled=true`, supply exactly one CDI `CacheReadiness` with `ready()` and a nonblank, process-stable `namespace()`. Not-ready bypasses cache reads and fills; invalidations still run. Tracker `isReady()` means local subscription readiness, **not CDC catch-up**. Read `jimmer-caching` for migration from deprecated `TRANSACTION_ONLY`/`BOTH`, retry/acknowledgement and fill fencing.

| Fork cache feature | Quarkus wiring |
|---|---|
| Hint-free DTO/View root queries read cached content | Automatic for every entity in `cache.entities`; disable per query with `useObjectCache(false)` |
| Keep entity/ID/association reads SQL-backed | `entities[].content-only: true` (no per-field list since 1.1.12) |
| Viewer-sensitive or per-row freshness | Optional CDI `CacheAdmissionPolicy` bean; outside an active request scope it denies |
| Own `CacheFactory` bean | Replaces the `@DefaultBean` producers entirely, including guard, content-only and policy wiring |

The admission contract and its guards are in `jimmer-caching` (object-cache admission reference).

## Kotlin + Quarkus

Follow the application's Kotlin/Quarkus compiler setup; where all-open is needed for CDI classes:

```kotlin
allOpen {
    annotation("jakarta.enterprise.context.ApplicationScoped")
}
```

KSP processor `jimmer-ksp` must be wired; entity module needs `index-dependency` as above.

## Verify

Compile generated DTOs/drafts and repositories, then run a datasource/JTA integration check. For a native target, run the application's native integration test: the module registers Jimmer reflection metadata, so do not add blanket reflection annotations or class-initialization flags without a specific failing type.

Sources: [unified module](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus), [consumer migration](https://github.com/sleepkqq/jimmer/blob/main/FORK.md), [config classes](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/cfg), [`QuarkusConnectionManager`](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/cfg/support/QuarkusConnectionManager.java).
