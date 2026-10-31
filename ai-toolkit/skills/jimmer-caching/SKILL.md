---
name: jimmer-caching
description: Configures Jimmer object, association and resolver caches (Caffeine, Redis), CDC-based invalidation instead of deprecated transaction triggers, multi-view caches for filtered data, and diagnoses stale or unused cache entries. Use when the user enables, tunes or debugs Jimmer caching. Not for HTTP, nginx or application-level caches unrelated to Jimmer.
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

Under `BINLOG_ONLY`, the writing instance evicts the object cache and owning-side association cache entries of its own inserts, updates, upserts, deletes and middle-table changes after commit (1.2.4): rollback evicts nothing, auto-commit evicts when the `execute` scope closes, and a bulk update of a cached type returns its ids from `UPDATE ... RETURNING` where the dialect supports it (otherwise one id pre-select), and a bulk delete adds one id select before its unchanged direct `DELETE`. Inverse-side association caches and writes from other services still rely on CDC; there is no configuration key for this.

## Cache kinds

| Kind | Key → Value | Created by | Invalidation |
|---|---|---|---|
| Object cache | `Type-id` → entity row | `createObjectCache(type)` | on delivered entity-change events |
| Association cache | `Type.prop-ownerId` → target id(s) | `createAssociatedIdCache` / `createAssociatedIdListCache` | on delivered FK/middle-table events; verify all affected directions |
| Calculated cache | `Type.prop-id` → resolver value | `createResolverCache` | user-assisted (resolver reacts to trigger events) |

Association + calculated together = "property caches" — only they can be multi-view; object cache is always single-view.

## Wiring

Use the installed provider's `CacheFactory`/`KCacheFactory` and `ChainCacheBuilder`; returning `null` from a factory method opts that type/property out. Spring Redis and Quarkus Redis binders have different constructors/builders: do not transplant a `RedisConnectionFactory` example into Quarkus. In the unified Quarkus module prefer its existing declarative `quarkus.jimmer.cache.entities` configuration; read [Quarkus cache lifecycle](references/quarkus.md) for guard, reconnect, timeouts, content-only entries, admission policy beans or custom factories.

## Fork: root queries can read the object cache

Upstream object caches serve ID loads and association/fetcher hydration, never ordinary root queries. The `sleepkqq/jimmer` fork (current `1.2.4`) adds **object-cache admission**: SQL selects the rows (ID skeleton, membership, order, paging, filters, FKs), the cache supplies entity content. Read [object-cache admission](references/object-cache-admission.md) before reasoning about a fork query that returns stale-looking values or unexpectedly skips columns.

| Fork situation | Behavior |
|---|---|
| Hint-free root query selecting a DTO/View whose entity declares an object cache | Admitted automatically: the projection's stored scalar leaves may come from the cache |
| Bare entity/Fetcher slot without a hint | Fresh SQL |
| `useObjectCache()` / `useObjectCache(fetcher)` / `useObjectCache(false)` | Legacy whole-entity hydration / explicit leaf whitelist / disable all admission |
| Inside a transaction, `forUpdate`, command purpose, stream/forEach, count | Never admitted |
| `CacheAdmissionPolicy` (via `CacheFactory.isObjectCacheAdmissible`) | Query-level or per-row veto; a vetoed row is loaded fresh, siblings stay cached |
| `content-only` cache | Entity, ID and association reads stay SQL-backed; DTO/View content is still admitted |

A DTO used for an edit form is admitted like a display DTO. A read that must see the current row uses a bare entity projection, `useObjectCache(false)` or a policy veto.

`findById`/`findByIds` (entity, Fetcher or DTO view) of a type whose only filter is the built-in logical-delete filter judge visibility on the cached row (1.2.4), so a warm read issues no SQL. User, reversed, ignored or sharding filters, `forUpdate` and `content-only` types keep the SQL visibility check; a cached payload without the logical-deleted column falls back to a visible-id query for the whole call. In Quarkus such a read also holds no pooled connection.

## Multi-view caches — user filters

A user-defined global filter on an entity makes every association cache TARGETING it, and every calculated cache relying on those, filter-sensitive:

- Such properties either stay uncached or become multi-view — a single-view cache configured for them is IGNORED (Jimmer reports the reason via the abandoned-cache callback; wire it up and read it instead of guessing).
- Multi-view storage adds a `SubKey` dimension: `Key → SubKey → Value`, where SubKey encodes the filter arguments (e.g. `{"tenant":"a"}`), so each client view caches separately.
- The filter must implement the cacheable-filter contract (provide its SubKey parameters) for its target's property caches to stay cacheable.
- Include every value that affects visibility in the sorted parameter map; also implement the event-affecting/invalidation contract. Test the same owner ID under two filter contexts. A plain user filter is not automatically cacheable.

Cost model: multi-view multiplies entries per key by the number of distinct filter views — reserve it for genuinely per-view data (tenancy, permissions), keep hot shared data single-view.

## Sharp edges

- Calculated (`@Transient` resolver) caches don't invalidate themselves — the resolver must subscribe to relevant trigger events and evict its own entries.
- Object cache serves id-based loads and fetcher joins; queries by arbitrary predicates still hit the DB — the cache accelerates shape assembly, not WHERE clauses. In the fork, admitted root queries also take content from it, but WHERE/membership still run in SQL.
- Before v0.12.4, ID-only references built while deserializing a cached graph inside an active draft context (`produce`, save, interceptors) could retain that context's drafts, so reshaping or reusing the cached object later failed (#1274). Upgrade instead of copying cached objects defensively.
- v0.12.4 parses `SINGLE_TABLE` binlog rows into the concrete subtype from the discriminator and ignores sibling-subtype columns (#1516); a missing/unmapped discriminator or an unknown column is an error. On older versions polymorphic CDC events could not be deserialized correctly.
- With `BINLOG_ONLY`, out-of-band SQL still needs CDC; your own bulk update of a cached type reads ids through `UPDATE ... RETURNING` and a bulk delete adds one id select (see the invalidation path). Inspect `QueryReason` rather than reviving deprecated transaction triggers.
- Redis tier: helper binders (`RedisValueBinder`, tracking variants) handle serialization via the provided `ObjectMapper` — entity types must stay Jackson-serializable.

## Sources

- [TriggerType](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/event/TriggerType.java)
- [Cache consistency](https://babyfish-ct.github.io/jimmer-doc/docs/cache/consistency) — its transaction-trigger recommendation is outdated; retain the delivery/acknowledgement mechanism only.
- [Multi-view filters](https://babyfish-ct.github.io/jimmer-doc/docs/cache/multiview-cache/user-filter)
- [Draft isolation test (#1274)](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/cache/DraftIsolationTest.java), [SINGLE_TABLE binlog test (#1516)](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/binlog/SingleTableBinLogTest.java)
- Fork: [FORK.md](https://github.com/sleepkqq/jimmer/blob/main/FORK.md), [`CacheAdmissionPolicy`](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/cache/CacheAdmissionPolicy.java)
