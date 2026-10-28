---
name: jimmer-caching
description: Configure Jimmer object, association and resolver caches; migrate deprecated transaction triggers to CDC; diagnose stale or filter-sensitive cache entries and fork-specific Quarkus Redis readiness.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Caching

Jimmer ORM caches assemble entity graphs; they are not arbitrary query-result or application caches. Check the resolved ORM version and cache provider first. Quarkus-specific details target the unified `sleepkqq/jimmer` module.

## Start with the invalidation path

**Do not configure `TRANSACTION_ONLY` or `BOTH` for new work. Both are deprecated.** Migrate away from transaction triggers; advanced mutations may reject any mode other than `BINLOG_ONLY`. Treat them as a removal/migration risk: upstream has not specified a removal release. Older documentation still recommends `BOTH`; the current `TriggerType` source supersedes that advice.

Treat the strategy and its prerequisites as one recommendation: use `BINLOG_ONLY` **with committed-change delivery**, replacing deprecated transaction-trigger modes. The enum is not a CDC installer.

```text
all DB writers → committed row/middle-table events → Jimmer BinLog
             → required cache invalidations succeed → acknowledge/checkpoint
```

Trace each arrow in the application's real integration before enabling caching. Old/new row images must describe the changes needed by entity and association invalidation. If an invalidation fails, propagate failure and retry/redeliver; acknowledging first can permanently lose it. TTL is only a fallback.

| State | Safe read policy | Recovery evidence |
|---|---|---|
| CDC disconnected or catch-up unknown | Bypass affected caches, including fills | Source checkpoint/catch-up and fresh cache namespace or coordinated reset |
| Redis subscription untrusted | Built-in Quarkus chains bypass reads/fills | Subscription recovery plus L1 reset; not proof of CDC freshness |
| Cache delete fails | Do not acknowledge the event | Successful retry; duplicate processing tolerated |
| Healthy pipeline | Use configured cache tiers | Cross-writer, cross-instance entity and association tests |

A migration must account for existing transaction listeners and warm entries as well as changing the setting. Report the reason for the replacement and how cached traffic becomes safe; a successful startup or Redis PING is insufficient. Verify two instances, an external writer, consumer restart, replay, and a load held across invalidation.

## Cache kinds

| Kind | Key → Value | Created by | Invalidation |
|---|---|---|---|
| Object cache | `Type-id` → entity row | `createObjectCache(type)` | on delivered entity-change events |
| Association cache | `Type.prop-ownerId` → target id(s) | `createAssociatedIdCache` / `createAssociatedIdListCache` | on delivered FK/middle-table events; verify all affected directions |
| Calculated cache | `Type.prop-id` → resolver value | `createResolverCache` | user-assisted (resolver reacts to trigger events) |

Association + calculated together = "property caches" — only they can be multi-view; object cache is always single-view.

## Wiring

Use the installed provider's `CacheFactory`/`KCacheFactory` and `ChainCacheBuilder`; returning `null` from a factory method opts that type/property out. Spring Redis and Quarkus Redis binders have different constructors/builders: do not transplant a `RedisConnectionFactory` example into Quarkus. In the unified Quarkus module prefer its existing declarative `quarkus.jimmer.cache.entities` configuration; read [Quarkus cache lifecycle](references/quarkus.md) for guard, reconnect, timeouts, or custom factories.

## Multi-view caches — user filters

A user-defined global filter on an entity makes every association cache TARGETING it, and every calculated cache relying on those, filter-sensitive:

- Such properties either stay uncached or become multi-view — a single-view cache configured for them is IGNORED (Jimmer reports the reason via the abandoned-cache callback; wire it up and read it instead of guessing).
- Multi-view storage adds a `SubKey` dimension: `Key → SubKey → Value`, where SubKey encodes the filter arguments (e.g. `{"tenant":"a"}`), so each client view caches separately.
- The filter must implement the cacheable-filter contract (provide its SubKey parameters) for its target's property caches to stay cacheable.
- Include every value that affects visibility in the sorted parameter map; also implement the event-affecting/invalidation contract. Test the same owner ID under two filter contexts. A plain user filter is not automatically cacheable.

Cost model: multi-view multiplies entries per key by the number of distinct filter views — reserve it for genuinely per-view data (tenancy, permissions), keep hot shared data single-view.

## Sharp edges

- Calculated (`@Transient` resolver) caches don't invalidate themselves — the resolver must subscribe to relevant trigger events and evict its own entries.
- Object cache serves id-based loads and fetcher joins; queries by arbitrary predicates still hit the DB — the cache accelerates shape assembly, not WHERE clauses.
- With `BINLOG_ONLY`, out-of-band SQL can be covered by CDC; cache presence alone does not imply a row-aware DML plan. Inspect `QueryReason` rather than reviving deprecated transaction triggers.
- Redis tier: helper binders (`RedisValueBinder`, tracking variants) handle serialization via the provided `ObjectMapper` — entity types must stay Jackson-serializable.

## Sources

- [TriggerType](https://github.com/babyfish-ct/jimmer/blob/v0.12.2/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/event/TriggerType.java)
- [Cache consistency](https://babyfish-ct.github.io/jimmer-doc/docs/cache/consistency) — its transaction-trigger recommendation is outdated; retain the delivery/acknowledgement mechanism only.
- [Multi-view filters](https://babyfish-ct.github.io/jimmer-doc/docs/cache/multiview-cache/user-filter)
