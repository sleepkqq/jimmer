# Architecture

## Module Structure

| Directory | Gradle name | Artifact | Description |
|---|---|---|---|
| `runtime/` | `:jimmer-quarkus:quarkus-jimmer` | quarkus-jimmer | Runtime: SqlClient, repositories, config, caching |
| `deployment/` | `:jimmer-quarkus:quarkus-jimmer-deployment` | quarkus-jimmer-deployment | Build-time: code generation, bean scanning |
| `integration-tests/` | `:jimmer-quarkus:integration-tests` | (not published) | Tests with PostgreSQL + Redis |

Group: `com.github.sleepkqq.jimmer`. ORM/APT/KSP use sibling project dependencies.

## Build

```bash
cd project                            # from the repository root
./gradlew build                        # full ORM + extension build
./gradlew :jimmer-quarkus:quarkus-jimmer:compileJava
./gradlew publishToMavenLocal          # publish to local Maven
./gradlew :jimmer-quarkus:integration-tests:test
```

Extension Java/Kotlin target: JDK 21. Gradle 9.7.1.

## Key Versions (libs.versions.toml)

- Quarkus: 3.39.2
- Jimmer: fork 1.0.0 (upstream dev 7c1d302b0)
- Kotlin: 2.4.20; KSP: 2.3.12

## Deployment vs Runtime

- **Deployment**: Classpath scanning, code generation, bean registration, native image config. Runs at build-time only.
- **Runtime**: Configuration, SqlClient init, repository base classes, caching, connection management. Runs at application start and request-time.

## Key Packages (runtime)

| Package | Content |
|---|---|
| `io.quarkiverse.jimmer.runtime` | `JQuarkusSqlClient`, `SqlClients.kt`, `Jimmer` accessor, recorders |
| `runtime.cfg` | `JimmerBuildTimeConfig`, `JimmerRuntimeConfig`, `JimmerDataSourceRuntimeConfig` |
| `runtime.cfg.support` | `QuarkusConnectionManager` |
| `runtime.repository` | `JRepository<E, ID>`, `JRepositoryImpl` |
| `runtime.repo` | `AbstractJavaRepository`, `AbstractKotlinRepository`, `UuidV7Paging.kt` (closers keyset-пагинации `fetchUuidV7Slice` / `fetchUuidV7Page`), DTO `UuidV7Slice` / `UuidV7Page` |
| `runtime.cache.impl` | `TransactionCacheOperatorFlusher` |
| `runtime.exception` | `JimmerDataAccessException` + подтипы, `SqlStateExceptionTranslator` (SQLState→exception) |
| `runtime.client.openapi` | OpenAPI generation recorders |
| `runtime.client.ts` | TypeScript generation recorders |
| `runtime.cloud` | Microservice exchange support |
| `runtime.dialect` | `DialectDetector` (auto-detect DB dialect) |
| `runtime.meta` | `QuarkusMetaStringResolver` |

## Key Packages (deployment)

| Package | Content |
|---|---|
| `io.quarkiverse.jimmer.deployment` | `JimmerProcessor` (main @BuildStep processor) |
| `deployment.bytecode` | `JimmerRepositoryFactory`, `JavaClassCodeWriter`, `KotlinClassCodeWriter` |

## SqlClient Creation Flow

1. `JimmerDataSourcesRecorder` records init lambdas at build-time
2. `QuarkusSqlClientProducer` creates `JQuarkusSqlClient` (lazy init)
3. `JQuarkusSqlClient.createBuilder()` configures JSqlClient.Builder:
   - Resolves dialect, schema, connection manager
   - Sets up triggers, caching, DI providers
   - Wraps connections via `QuarkusConnectionManager`
4. Multi-datasource: beans qualified with `@DataSource("name")`

## Repository Pattern

1. User defines interface extending `JRepository<E, ID>` / `KRepository<E, ID>`
2. `JimmerProcessor` discovers at build-time via Jandex
3. `JimmerRepositoryFactory` generates bytecode implementation
4. Derived query methods parsed from method names (`findByNameLike`)

## Code Style

- Java for main code, Kotlin for SqlClients.kt and Kotlin-specific wrappers
- 1 tab indentation
- Quarkus extension conventions: `@Record`, `@BuildStep`, synthetic CDI beans
