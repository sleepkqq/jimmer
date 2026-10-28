# Runnable Java and Kotlin examples

Both modules depend on official Jimmer **0.9.111**. They model the same Book /
BookStore domain and run against separate in-memory H2 databases. All runtime
checks throw on failure; Java checks do not depend on `-ea`.

## Run

Use **JDK 21 and Gradle 8.12.1**. Kotlin is pinned to `2.1.20`, KSP to the matching
`2.1.20-1.0.32`; Jimmer runtime and processors are pinned independently. Gradle 9
is not the declared toolchain for this historical processor fixture.

```bash
gradle -p versions/0.9.111/examples --no-daemon --console=plain check
# Or one language:
gradle -p versions/0.9.111/examples --no-daemon --console=plain :java:run
gradle -p versions/0.9.111/examples --no-daemon --console=plain :kotlin:run
```

On macOS, set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` if a newer JDK is the
default. A compatible Gradle installation or existing wrapper is sufficient;
there is no additional wrapper binary in this repository.

## Read side by side

| Concern | Java | Kotlin |
|---|---|---|
| Entities and shared properties | [Book](java/src/main/java/example/Book.java), [BookStore](java/src/main/java/example/BookStore.java), [TenantScoped](java/src/main/java/example/TenantScoped.java) | [Book](kotlin/src/main/kotlin/example/Book.kt), [BookStore](kotlin/src/main/kotlin/example/BookStore.kt), [TenantScoped](kotlin/src/main/kotlin/example/TenantScoped.kt) |
| DTO Views, Inputs, Specification | [Book.dto](java/src/main/dto/Book.dto), [BookStore.dto](java/src/main/dto/BookStore.dto) | [Book.dto](kotlin/src/main/dto/Book.dto), [BookStore.dto](kotlin/src/main/dto/BookStore.dto) |
| Drafts, fetchers, queries, writes and checks | [Examples.java](java/src/main/java/example/Examples.java) | [Examples.kt](kotlin/src/main/kotlin/example/Examples.kt) |
| Cacheable tenant-filter contract | [TenantFilter](java/src/main/java/example/TenantFilter.java) | [TenantFilter](kotlin/src/main/kotlin/example/TenantFilter.kt) |
| Optional Spring repository interface | [BookRepository](java/src/main/java/example/BookRepository.java) | [BookRepository](kotlin/src/main/kotlin/example/BookRepository.kt) |

The same [schema](schema/schema.sql) is used in both runs. Generated files stay in
each module's ignored `build/` directory. DTO files are declared task inputs so
editing only a `.dto` invalidates APT/KSP generation.

The standalone fixture deliberately passes one connection through a transaction
and rolls it back. Production code should use the application's connection and
transaction integration. The fixed-tenant filter is scoped per client here; do
not capture a request's tenant into a global singleton. Cache key contracts are
compiled, but Redis/CDC behavior is not exercised. Spring repository interfaces
are compile-only: derived-query startup requires a Spring integration test in
the consuming application.
