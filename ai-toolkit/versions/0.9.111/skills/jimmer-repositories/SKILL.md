---
name: jimmer-repositories
description: Use Jimmer Spring JRepository/KRepository built-ins, derived methods and typed queries while preserving service transactions and Java/Kotlin result shapes.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Repositories

Inspect current data-access boundaries. Direct `JSqlClient`/`KSqlClient` is valid; no repository wrapper is mandatory. Repository integration here is Spring-based; for Quarkus see `jimmer-quarkus`.

Take the first option expressing the **whole result**: existing built-in → supported derived method → typed DSL. An empty repository is enough until a caller needs more. Keep business decisions and multi-statement transactions in the existing service boundary.

Java (`org.babyfish.jimmer.spring.repository.JRepository`):

```java
public interface BookRepository extends JRepository<Book, Long> {
    List<Book> findByName(String name);
}
```

Kotlin (`org.babyfish.jimmer.spring.repository.KRepository`):

```kotlin
interface BookRepository : KRepository<Book, Long> {
    fun findByName(name: String): List<Book>
}
```

Add `findByName` only when called. Validate derivation at application startup; successful interface compilation alone does not exercise Spring's query parser.

Built-ins include `findNullable(id[, fetcher])`, `findAll`, `viewer(ViewType)`, saves and delete. Prefer `viewer(BookView.class)` in Java / `viewer(BookView::class)` in Kotlin for an existing View contract. Repository pagination may use Spring Data `Page`; direct SQL DSL `fetchPage` uses Jimmer `Page` — do not mix return types accidentally.

## Typed result without an unconditional second lookup

Java:

```java
return repository.saveCommand(input)
    .setMode(SaveMode.INSERT_ONLY)
    .execute(BookView.class)
    .getModifiedView();
```

Kotlin:

```kotlin
return repository.sql.saveCommand(input) {
    setMode(SaveMode.INSERT_ONLY)
}.execute(BookView::class).modifiedView
```

Using the Kotlin repository's `sql` client makes the release's command API explicit; do not assume method parity with Java. For a conditional query use `sql()` in a Java default method or `sql` in Kotlin and follow `jimmer-query`.

Fetchers may intentionally require several batched SQL statements; one repository invocation is not one-statement or snapshot-atomic evidence. For conflict branches, inspect `jimmer-save-modes` before promising an existing row from `INSERT_IF_ABSENT`.

Verify generated types, repository startup, emitted SQL, return type and transaction lifetime.

Sources: [Spring repository APIs](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-spring-boot-starter/src/main), [KRepository](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-spring-boot-starter/src/main/java/org/babyfish/jimmer/spring/repository/KRepository.kt).
