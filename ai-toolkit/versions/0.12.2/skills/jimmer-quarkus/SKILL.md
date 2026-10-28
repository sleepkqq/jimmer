---
name: jimmer-quarkus
description: Integrate the separately versioned sleepkqq/jimmer Quarkus fork with aligned runtime/APT/KSP, CDI repositories, JTA, named datasources, native images and Redis readiness.
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

Do not mix upstream `org.babyfish.jimmer` artifacts with the fork's runtime/processors. Match the Kotlin compiler, KSP plugin and Quarkus platform to the fork's compatibility/build metadata rather than copying fixed versions from an example. Inspect the resolved runtime **and processor** dependency graphs after migration.

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

Read `jimmer-config` for the relevant key and its scope: only datasource-scoped options accept `quarkus.jimmer.<datasource-name>.<key>`. Dialect is derived from datasource metadata; validation uses `database-validation-mode` (the nested `database-validation.mode` form is deprecated).

The fork defaults to `constraint-violation-translatable=false` and `sql-state-exception-translator=true`: SQLState maps to `JimmerDataAccessException` subtypes. Enabling typed constraint investigation changes the error/performance contract; see `jimmer-debug`.

## Redis lifecycle

Use declarative `quarkus.jimmer.cache.entities`. With `quarkus.jimmer.cache.guard.enabled=true`, supply exactly one CDI `CacheReadiness` with `ready()` and a nonblank, process-stable `namespace()`. Not-ready bypasses cache reads and fills; invalidations still run. Tracker `isReady()` means local subscription readiness, **not CDC catch-up**. Read `jimmer-caching` for migration from deprecated `TRANSACTION_ONLY`/`BOTH`, retry/acknowledgement and fill fencing.

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

Sources: [unified module](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus), [consumer migration](https://github.com/sleepkqq/jimmer/blob/main/FORK.md), [config classes](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/cfg).
