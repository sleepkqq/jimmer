---
name: jimmer-caching
description: Configure Jimmer object, association and resolver caches with transaction/binlog triggers, Java/Kotlin factories and multi-view invalidation.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Caching

ORM caches assemble immutable graphs; they are not arbitrary query-result caches. Identify every database writer and the delivery/invalidation path before enabling caches.

## Trigger policy

`TriggerType` contains `BINLOG_ONLY`, `TRANSACTION_ONLY`, `BOTH`. Choose the mode from the real writer topology and the evidence it requires:

| Mode | Required evidence |
|---|---|
| `BINLOG_ONLY` (default) | Committed row/middle-table events actually reach Jimmer BinLog; the enum does not install CDC |
| `TRANSACTION_ONLY` | Required mutations use Jimmer transaction triggers; external/native writers need separate invalidation coverage |
| `BOTH` | Separate transaction and binlog trigger channels are deliberately wired; do not double-register a listener accidentally |

With `BOTH`, Java `getTriggers(true)` / Kotlin `getTriggers(true)` selects the transaction channel; the default/nontransaction channel is binlog. Preserve the framework's transaction-aware cache operator. Events raised inside a transaction are not proof that the transaction committed; verify rollback and post-commit invalidation behavior before trusting a custom listener.

For CDC: committed events → BinLog → successful required cache invalidations → acknowledgement/checkpoint. Failed invalidation must retry/redeliver. Outage/catch-up requires an explicit stale-read/bypass/reset policy; TTL is only a backstop. Test external writers, two instances, rollback, restart and replay.

## Cache types and factory APIs

| Cache | Key → value | Factory method |
|---|---|---|
| Object | Type + ID → entity row | `createObjectCache` |
| To-one association | Owner ID → target ID | `createAssociatedIdCache` |
| To-many association | Owner ID → target ID list | `createAssociatedIdListCache` |
| Transient resolver | Owner ID → calculated value | `createResolverCache` |

Java uses `org.babyfish.jimmer.sql.cache.CacheFactory`; Kotlin uses `org.babyfish.jimmer.sql.kt.cache.KCacheFactory`. A factory may return null for uncached types/properties. Reuse the installed provider and its `ChainCacheBuilder`; do not invent a custom cache chain when the provider already supplies one.

Builder shape (the application's existing provider supplies `factory`):

```java
JSqlClient client = JSqlClient.newBuilder()
    .setConnectionManager(connectionManager)
    .setCacheFactory(factory)
    .build();
```

```kotlin
val client = newKSqlClient {
    setConnectionManager(connectionManager)
    setCacheFactory(factory)
}
```

This only wires the provider. It does not establish invalidation or Redis connectivity. Framework auto-configuration is preferable when already used.

## Filter-sensitive caches

Only property (association/resolver) caches have multi-view semantics; object caches remain single-view. A filter on a target entity can make every property cache pointing at it view-dependent. Provide a sorted map of all visibility parameters through `CacheableFilter`/`KCacheableFilter`, the event-affecting contract and parameterized cache storage, or leave those properties uncached. Read abandoned-cache diagnostics rather than assuming a configured cache is active.

Transient resolver caches need dependency-triggered eviction; they do not infer arbitrary business dependencies. Association invalidation must cover both old and new owners and relevant middle-table changes. Arbitrary predicate queries still hit the DB even when their resulting entities can be assembled from cache.

Sources: [trigger enum](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/event/TriggerType.java), [Java caches](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/cache), [Kotlin caches](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/cache).
