# Jimmer Performance

Use when writing or reviewing any Jimmer data path where round-trips matter. Core rule: count the SQL statements Jimmer emits (enable SQL log) — every statement must be explainable.

## Save results and acceptance

For an accepted write, `save`/`saveCommand` returns a modified immutable object, not automatically every column/association. Check acceptance before consuming conditional/batch results and explicitly request additional result fields.

```kotlin
val saved = sqlClient.save(entity) { setMode(SaveMode.UPSERT) }.modifiedEntity
val ids = sqlClient.saveEntities(entities).items.map { it.modifiedEntity.id }
```

```java
DomainObjectDetailView view = repository.saveCommand(input)
    .execute(DomainObjectDetailView.class)   // returns the saved shape directly
    .getModifiedView();
```

Avoid an unconditional `save` followed by `findById` of the same aggregate: pass the Fetcher/View shape to `execute(...)` instead. **Exception:** a conflicting `INSERT_IF_ABSENT` root is rejected (`isAccepted=false`) and does not return the existing row even when a View is requested. An API needing that existing row without updating it needs a separate key lookup and an explicit concurrency policy. `UPSERT` self-assignment is still a database UPDATE, with trigger/locking effects.

How the result is materialized (cheapest first): values already known from the saved object are copied; unresolved local columns are read straight from the DML statement via `RETURNING` where the dialect supports it (H2, PostgreSQL); only the residual part of the requested shape (associations, formulas, non-local data) runs as follow-up queries. When the database itself may rewrite values (triggers, generated columns, normalization), enable `saveResultReadsAllProperties` so requested properties are read back instead of copied.

Requesting result fields can change the SQL itself: with `forbidUpdate()` and a supplied ID, a requested Fetcher/View now forces a technical self-assignment (fake update) or a follow-up select to return the stored row (0.12.4). That adds affected rows, trigger execution and locks; request only the fields the caller uses. Fork `1.0.5+` additionally skips residual reads for satisfied to-one ID views and seeds known subtype discriminators instead of rereading polymorphic results.

## Upsert cost ladder (cheapest first)

1. A compatible ID/key, constraint and dialect can use native upsert. `@KeyUniqueConstraint` enables the natural-key fast path; it is not required for ID-only matching and is not a single-statement guarantee.
2. A `DraftInterceptor` that needs original state introduces preselection (`QueryReason: INTERCEPTOR`). Use `DraftPreProcessor` only for behavior independent of existence/original values.
3. Missing native-upsert prerequisites can require select-then-write. Inspect the actual `QueryReason`, null-key, logical-delete, lock and dialect behavior before changing annotations.

Batch: prefer `saveEntities(entities)` (with `SaveMode.INSERT_ONLY` for pure inserts) to a loop of single saves. Since 0.12.4 duplicate new targets with the same key in one save share one generated ID and insert once (#1076). Shape, generated keys, driver capabilities and association stages can split a batch; count emitted statements instead of promising exactly one round-trip.

## Atomic column math — assignment expressions

Counters and other read-modify-write patterns move into the database. `set(prop) { expression }` on a save command replaces the assignment for an already-loaded property:

```kotlin
sqlClient.save(Book { id = bookId; price = delta }) {
    setMode(SaveMode.UPDATE_ONLY)
    set(Book::price) { target.price + newNonNull(Book::price) }   // SET PRICE = PRICE + ?
}
```

Supported in `UPDATE_ONLY` and the update branch of `UPSERT`; the target must be a local scalar column already selected by the object shape (and allowed by the `UpsertMask` if present). When the post-update value is needed by the result or a trigger, Jimmer reads it via DML returning or a follow-up query — never assume it equals the input.

## Bulk update returning

When updated values are needed immediately, return them from the same statement instead of re-selecting:

```kotlin
val rows: List<Tuple2<Long, String>> =
    sqlClient.createUpdateReturning(Author::class) {
        set(table.firstName, concat(table.firstName, value("*")))
        where(table.firstName eq "Dan")
        returning(table.id, table.firstName)
    }.execute()          // or .stream() for large row sets
```

Java: `createUpdate(...).set(...).where(...).returning(...)`; Kotlin shortcut `executeUpdateReturning`. One selection → list of values, several → tuples. Fork `1.0.7+` also returns owning-reference IDs (`getAssociatedId("store")`, Kotlin ID-view properties) from the same statement; inverse references and target-table joins remain unsupported.

## QueryReason audit

Every extra SELECT in the SQL log is tagged with a `QueryReason`. Treat unexpected reasons as findings: `INTERCEPTOR`, `TRIGGER` (transaction triggers need pre-images), `KEY_UNIQUE_CONSTRAINT_REQUIRED`, `OPTIMISTIC_LOCK`, `CANNOT_DELETE_DIRECTLY` (delete must select rows first — see below), `INVESTIGATE_CONSTRAINT_VIOLATION_ERROR` (only after an actual violation — fine).

## Deletes under triggers/cache

Legacy transaction triggers can force select-before-delete (`CANNOT_DELETE_DIRECTLY`); both `TRANSACTION_ONLY` and `BOTH` are deprecated. Cache presence is not the cause of every row-aware delete, but under `BINLOG_ONLY` a bulk update/delete of a type with a cache now pre-selects its target ids once (1.2.1) so the writing instance can evict its own entries after commit. Writes from other services are covered only **if CDC delivers the required events**. Choose native bounded deletion only after checking associations, logical deletion, filters, parameter binding and CDC coverage; it is not a generic escape from ORM semantics.

## Pagination

| Need | API | Cost |
|---|---|---|
| Page with exact total | `fetchPage(page, size)` | generally data + count, subject to empty/short-page optimizations |
| Infinite scroll / typeahead ("has more?") | `fetchSlice(limit, offset)` → `slice.isTail` | one query with `limit+1`, no count |
| Existence only | query `exists()` (Java/Kotlin `ConfigurableRootQuery`) | `select 1 ... limit 1`, no count scan |

Exact counts on search-as-you-type paths are wasted work — reach for `fetchSlice`.

Deep pages (large offset): set `offset-optimizing-threshold` (config or `setOffsetOptimizingThreshold`) — past the threshold Jimmer rewrites the page query to an id-first plan instead of a raw offset scan. Pair with a product-level page-depth cap.

## N+1 and fetch strategy

- Fork root-query object-cache admission (`useObjectCache`) changes which columns SQL reads; reason about it with `jimmer-caching`, not by removing fetch fields.
- Fetcher associations batch-load by default (batch 128 / list batch 16, configurable). Unloaded access throws, it does not lazy-load. Look for explicit query loops and resolver/interceptor per-ID queries.
- `ReferenceFetchType.JOIN_ALWAYS` (or `!fetchType(JOIN_ALWAYS)` in `.dto`) folds a reference into the main query — right for mandatory to-one refs without cache; `SELECT` keeps batching — right when the target is cached.
- Collection field config `batch(n)` / `limit(limit, offset)` bounds fan-out per parent.

## Selections with subqueries

A correlated scalar subquery may cause repeated work, or the optimizer may decorrelate it. Check the plan before rewriting it as a join/window/base query. Reusing a language-level expression variable does not ensure single database evaluation; expose a base-query/CTE column when appropriate. Verify with the target database's EXPLAIN and representative cardinalities.

## Checklist before "done"

- [ ] SQL log reviewed: statement count matches expectations, no surprise QueryReason
- [ ] Accepted-save results taken from the command; any rejected-conflict lookup is deliberate
- [ ] Counters/aggregates updated with assignment expressions, not read-modify-write
- [ ] Bulk updates that feed later logic use `returning(...)`, not a follow-up select
- [ ] Loops contain no queries; batch APIs used
- [ ] Pagination API matches product need (page vs slice vs exists)
- [ ] Selections free of unbounded per-row subqueries

Sources: [save returning](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/returning), [assignment expressions](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/assignment), [QueryReason](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/mutation/QueryReason.java), [streaming](https://babyfish-ct.github.io/jimmer-doc/docs/query/usage).
