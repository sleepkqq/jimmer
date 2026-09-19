# Architecture

## Module Structure

| Directory | Gradle name | Artifact | Description |
|---|---|---|---|
| `runtime/` | `:quarkus-jimmer` | quarkus-jimmer | Runtime: SqlClient, repositories, config, caching |
| `deployment/` | `:quarkus-jimmer-deployment` | quarkus-jimmer-deployment | Build-time: code generation, bean scanning |
| `integration-tests/` | `:integration-tests` | (not published) | Tests with H2 + Redis |

Group: `com.github.sleepkqq.quarkus-jimmer-extension`

## Build

```bash
./gradlew build                        # full build
./gradlew :quarkus-jimmer:compileJava  # compile runtime only
./gradlew publishToMavenLocal          # publish to local Maven
./gradlew integration-tests:test       # run integration tests
```

Java/Kotlin target: JDK 21. Gradle 9.4.0.

## Key Versions (libs.versions.toml)

- Quarkus: 3.37.2
- Jimmer: 0.11.0
- Kotlin: 2.4.0

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
