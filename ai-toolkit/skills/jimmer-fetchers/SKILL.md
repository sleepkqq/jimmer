---
name: jimmer-fetchers
description: Defines Jimmer Fetcher and View read shapes, loaded-state boundaries, batched association loading and reference fetch strategies, and fixes N+1 loops or stream-incompatible graph loads. Use when the user loads an entity graph, picks which associations or IDs to load, chooses Fetcher versus DTO View, or sees N+1 queries on associations. Not for .dto syntax (jimmer-dto).
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Fetchers

Read the caller's required fields and installed generated API first. A Jimmer object can be non-null yet have unloaded properties; accessing one throws rather than triggering lazy loading.

## View vs Fetcher

| Need | Use |
|---|---|
| Stable API/read model | `.dto` View |
| Save/write payload | `.dto` Input |
| Runtime/dynamic field set | Fetcher |
| GraphQL-style selection | Fetcher |

Use a View when a named static boundary helps the API. A reusable static Fetcher is equally valid for entity-returning code; dynamic selection is not its only purpose. Preserve the project's established contract instead of creating a DTO for every query.

## Input DTO Rule

`Input<E>` generates `toEntity()`. Do not manually build entities from input DTOs.

```java
DomainObject domainObject = input.toEntity();
repository.save(domainObject);
```

## Fetcher API

```java
DOMAIN_OBJECT_FETCHER
    .allScalarFields()
    .relatedObject(RELATED_OBJECT_FETCHER.name());
```

Use the generated style already present: `Fetchers.BOOK_FETCHER` and `BookFetcher.$` are both valid Java APIs.

Kotlin (KSP) builds the same shape inline with `table.fetchBy { }` or reusably with `newFetcher(Book::class).by { }`:

```kotlin
val BOOK_LIST = newFetcher(Book::class).by {
    allScalarFields()
    store(ReferenceFetchType.JOIN_ALWAYS) {
        name()
    }
    authors({
        batch(16)
        filter { orderBy(table.firstName) }
    }) {
        firstName()
        lastName()
    }
}
```

| Method | Includes |
|---|---|
| `allScalarFields()` | persistent scalar shape; do not assume every formula/transient/large field is included |
| `allReferenceFields()` | FK associations id-only |
| `allTableFields()` | scalars + references |

### Reference fetch strategy

`ReferenceFetchType` per reference association: `AUTO` (default), `SELECT` (separate batched query), `JOIN_IF_NO_CACHE`, `JOIN_ALWAYS` (fetch via join in main query):

```java
BOOK_FETCHER.allScalarFields()
    .store(ReferenceFetchType.JOIN_ALWAYS, BOOK_STORE_FETCHER.allScalarFields());
```

In `.dto` files the same is `!fetchType(JOIN_ALWAYS)`.

### Field-level config

Association fields accept a config block: `filter(...)` and `batch(n)` everywhere; `limit(limit, offset)` on lists; `depth(n)` / `recursive(strategy)` on recursive self-associations. Java passes `it -> it.batch(16)`; Kotlin passes `({ batch(16) })` before the child block. Global default for references: `setDefaultReferenceFetchType(...)` / Spring `jimmer.default-reference-fetch-type`.

Bound recursion/fan-out intentionally. A filter can remove a referenced target; verify fetch/nullability semantics, not just join count. `JOIN_ALWAYS` deliberately bypasses the reference cache opportunity; `JOIN_IF_NO_CACHE` makes the choice conditional.

## Generated Code

| Generated class | Purpose |
|---|---|
| `DomainObjectDraft` | mutable builder |
| `DomainObjectTable` / `DomainObjectTableEx` | typed query tables |
| `DomainObjectProps` | typed prop constants |
| `Tables` | table constants |
| `Fetchers` | fetcher constants |
| `Immutables` | entity factory only |
| `*View`, `*Input`, `*Spec` | DTO classes |

## N+1

Jimmer batch-loads associations (defaults: batch 128, list batch 16). Tune in config when needed:

```yaml
jimmer:
  default-batch-size: 128
  default-list-batch-size: 16
```

Before tuning, eliminate explicit per-row repository/resolver calls. `stream()` rejects a fetch graph that needs secondary association loading; use a compatible join-only shape or a bounded batch traversal and close the JDBC stream within its transaction.

Verify the loaded shape and SQL count for multiple roots. For save results, use the requested Fetcher/View with acceptance checks; residual associations can still need follow-up queries (`jimmer-save-modes`).

Sources: [fetchers](https://babyfish-ct.github.io/jimmer-doc/docs/query/object-fetcher/), [Fetcher](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/fetcher/Fetcher.java), [ReferenceFetchType](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/fetcher/ReferenceFetchType.java), [streaming](https://babyfish-ct.github.io/jimmer-doc/docs/query/usage), [save result fetching](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/returning).
