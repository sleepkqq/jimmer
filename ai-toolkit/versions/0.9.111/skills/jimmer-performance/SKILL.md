---
name: jimmer-performance
description: Diagnose Jimmer Java/Kotlin SQL counts, QueryReason fallbacks, pagination, batched traversal, partial save results and atomic bulk updates.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Performance

Measure representative SQL, bindings, result cardinality and plans before optimizing. Preserve transaction isolation, authorization and returned shape. Compilation is API evidence, not a query-plan benchmark.

## Read cost

- Fetcher associations batch-load; eliminate explicit per-row queries first. Defaults are reference batch 128 / list batch 16. Keep public fields explicit.
- Java `.fetchPage(page, size)` and Kotlin `.fetchPage(page, size)` provide totals; `.fetchSlice(limit, offset)` avoids exact count for has-more; `.exists()` avoids counting solely for a boolean.
- Always use stable order with an ID tie-breaker. For deep pages consider bounded keyset traversal or source-verified offset optimization, and compare plans.
- Java `.forEach(128, consumer)` / Kotlin `.forEach(batchSize = 128) { ... }` provide batched consumption. No query `stream()` exists here. A huge per-root graph still consumes memory.
- Base queries/CTEs and `@TypedTuple` projections exist. Reusing a host-language expression does not guarantee single SQL evaluation.

## Write cost

Java:

```java
var result = sqlClient.saveEntitiesCommand(books)
    .setMode(SaveMode.INSERT_ONLY)
    .execute();
```

Kotlin:

```kotlin
val result = sqlClient.saveEntities(books) {
    setMode(SaveMode.INSERT_ONLY)
}
```

Batch APIs avoid application loops of single saves, but driver/generated-key/shape differences can split statements. Graph writes are not guaranteed one round trip.

For a successful save, use `modifiedEntity` when its partial shape is sufficient. Request a Fetcher/View via command execution if more fields are required; the fetch happens after saving. A strict insert-if-absent endpoint needing an unchanged existing row may legitimately need a key lookup with explicit concurrency policy.

`isModified` describes changed immutable-object identity. Kotlin also has `isRowAffected` (nonempty affected-count map), while Java `SimpleSaveResult` does not. Neither is root acceptance. Use Java `getAffectedRowCount(Book.class)` versus Kotlin `affectedRowCount(Book::class)` for a particular entity table; see `jimmer-save-modes` for the complete result contract.

Inspect `QueryReason` for extra reads: interceptors needing original state, transaction triggers, optimistic checks, missing native key constraints and dialect limitations. `DraftPreProcessor` only replaces an interceptor when original-state logic is unnecessary. Do not disable transaction triggers as a performance shortcut while leaving caches stale.

Atomic arithmetic belongs in `createUpdate` expressions (`jimmer-dml`), not read-modify-write. When subsequent logic needs updated values, an explicit read in the required transaction scope can be correct.

## Verification

Compare SQL counts and DB state before/after for multiple roots, duplicate keys and zero-row conditional updates. Inspect cold/warm caches separately. If deleting through native SQL, first establish logical-deletion, association, filter and invalidation equivalence; it is not a generic escape from ORM work.

Sources: [QueryReason](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/mutation/QueryReason.java), [root query APIs](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/query/TypedRootQuery.java).
