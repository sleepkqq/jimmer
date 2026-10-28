---
name: jimmer-kotlin
description: Implement Jimmer Kotlin draft, query, fetcher and save DSL with KSP, null-aware predicates and the matching Java equivalents.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Kotlin

Use official `jimmer-sql-kotlin` with its matching `jimmer-ksp`. Align Kotlin/KSP plugin versions separately. In Spring set `jimmer.language=kotlin`; the standalone `newKSqlClient` builder is already Kotlin-specific. For Quarkus, see `jimmer-quarkus`.

## Draft creation

Keep one Jimmer model declaration per Kotlin source file. KSP rejects multiple `@Entity`, `@MappedSuperclass`, `@Embeddable` or `@Immutable` types in the same file.

```kotlin
import example.Book

val book = Book {
    name = "Jimmer Guide"
    price = BigDecimal("29.90")
}
```

`Book { ... }` is the generated factory in the model's package. The alternate spelling `new(Book::class).by { ... }` (with `org.babyfish.jimmer.kt.new` and the generated `by`) is emitted into the same file; both return the immutable `Book`.

Java equivalent, using the APT-generated `Immutables` facade of the entities' common package:

```java
Book book = Immutables.createBook(draft -> {
    draft.setName("Jimmer Guide");
    draft.setPrice(new BigDecimal("29.90"));
});
```

The `$`-style call `BookDraft.$.produce(draft -> { ... })` is identical. APT also generates `Tables`, `TableExes` and `Fetchers` interfaces for the same purpose. For an Input, prefer its generated `toEntity()` or direct save overload instead of manually copying properties.

## Query / Fetcher / save

- Import generated model property extensions and `org.babyfish.jimmer.sql.kt.ast.expression.*` for operators. Table/selection helpers may need `org.babyfish.jimmer.sql.kt.ast.table.*`.
- Use `table` (no Java `TableEx`), `eq`/`like`, and backticked `eq?`/`like?` for optional operands. `KClass<V>` replaces Java `Class<V>`.
- `newFetcher(Book::class).by { name(); store { name() } }` builds a Fetcher; `table.fetch(BookView::class)` selects a generated View.
- Generated Kotlin DTOs are immutable by default; use constructor arguments such as `BookSpec(name = "guide")`, not Java-style setters or `apply { name = ... }` unless the project explicitly generates mutable DTOs.
- `sqlClient.save(entity) { setMode(SaveMode.UPDATE_ONLY) }.modifiedEntity` returns the known partial saved object. To request a View use `saveCommand(entity) { ... }.execute(BookView::class).modifiedView`.
- Kotlin nullability is `T?`; non-null properties may still be unloaded. Inspect with `org.babyfish.jimmer.kt.isLoaded(entity, Book::store)` before reading a partial field.

## Preprocessor vs interceptor

`DraftPreProcessor` is suitable when behavior does not depend on original/existing state:

```kotlin
class BookNamePreProcessor : DraftPreProcessor<BookDraft> {
    override fun beforeSave(draft: BookDraft) {
        if (isLoaded(draft, Book::name)) {
            draft.name = draft.name.trim()
        }
    }
}
```

Both languages implement the Java `org.babyfish.jimmer.sql.DraftPreProcessor` / `DraftInterceptor` contracts. Register through the actual client/framework integration. An interceptor needing original state can force preselection (`QueryReason.INTERCEPTOR`); declared dependencies control original fields. Batch hook work rather than running a query per item. Do not move original-state logic into a preprocessor just to remove a SELECT, and do not overwrite creation timestamps on every update.

A standalone (non-Spring) Jackson 2 mapper registers `org.babyfish.jimmer.jackson.ImmutableModule`, e.g. `ObjectMapper().registerModule(ImmutableModule())`.

Verify generated DTO/draft/fetcher imports with KSP, null/value/omitted Input behavior and representative SQL. Source: [Kotlin production DSL](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt), [KSP generators](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-ksp/src/main/kotlin/org/babyfish/jimmer/ksp/immutable/generator).
