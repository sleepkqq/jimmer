---
name: jimmer-fetchers
description: Define Jimmer Java/Kotlin Fetchers and Views, loaded-state boundaries, batched graph loading, reference fetch strategies and bounded recursive shapes.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Fetchers

Jimmer entities are partial immutable graphs. Reading an unloaded field throws `UnloadedPropertyException`; it never starts a lazy query. Choose fields for the actual caller.

| Need | Shape |
|---|---|
| Stable public response | Generated `.dto` View with explicit allowed fields |
| Submitted state | Generated Input (`toEntity()`), not a read View |
| Dynamic or reusable entity shape | Fetcher; static Fetchers are also valid |

Java:

```java
var fetcher = Fetchers.BOOK_FETCHER
    .name()
    .price()
    .store(Fetchers.BOOK_STORE_FETCHER.name());
List<Book> books = sqlClient.findByIds(fetcher, ids);
```

Kotlin (import `org.babyfish.jimmer.sql.kt.fetcher.newFetcher` and the generated `by` extension):

```kotlin
val fetcher = newFetcher(Book::class).by {
    name()
    price()
    store { name() }
}
val books = sqlClient.findByIds(fetcher, ids)
```

ID is automatically included. `allScalarFields()` includes persistent scalars, not every transient/formula property. `allReferenceFields()` includes ID-only references; `allTableFields()` combines scalar and reference fields. Avoid broad shapes for privacy-sensitive public output.

## Reference loading

`ReferenceFetchType`: `AUTO` resolves the default, `SELECT` batches a separate load, `JOIN_IF_NO_CACHE` uses join when cache is unavailable, `JOIN_ALWAYS` forces joining. DTO `!fetchType(JOIN_ALWAYS)` is equivalent. Choose by measured SQL shape/cache behavior, not the belief that every extra query is N+1.

Collection configs include `filter`, `batch`, `limit`; recursive configs include depth/recursion strategy. Verify nullable/filter-hidden targets and bound fan-out. Default batches are 128 for reference loading and 16 for collections; Spring keys are `jimmer.default-batch-size` and `jimmer.default-list-batch-size`.

## Generated API and verification

Java generates `BookDraft`, `BookTable`/`BookTableEx`, `BookProps`, package `Tables`/`Fetchers` and DTO classes. Kotlin generates draft/producer DSL, property/query/fetcher extensions and DTOs through KSP. Do not import Java table types into a Kotlin-native model merely to imitate Java code.

Eliminate explicit per-row repository/resolver calls before tuning batches. `forEach` is the release's batched traversal; there is no query `stream()`. Verify SQL count and loaded properties over several roots, plus excluded sensitive fields. Save Fetcher/View overloads are available but may run a read after mutation (`jimmer-save-modes`); no `isAccepted` exists.

Sources: [fetcher API](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/fetcher), [Kotlin fetcher DSL](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/fetcher).
