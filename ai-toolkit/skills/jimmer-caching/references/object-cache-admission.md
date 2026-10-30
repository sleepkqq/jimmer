# Object-cache query admission — sleepkqq/jimmer fork

Fork-only (`com.github.sleepkqq.jimmer:*`, contract below is release `1.2.0`). Upstream `org.babyfish.jimmer` has no `useObjectCache`, `CacheAdmissionPolicy` or content-only caches: there the object cache serves ID loads and association/fetcher hydration only.

Admission lets a root query take entity **content** from a declared object cache. It is not a query-result cache: SQL still runs and decides which rows exist.

## What stays SQL-authoritative on every path

Membership, `WHERE`/global filters/visibility, join topology and FK values, ordering, duplicates, pagination, concrete type (discriminator), scalar tuple slots, association edges and every leaf outside the admitted mask. The query first selects an ID-only skeleton (plus retained values); cache content is overlaid on the rows SQL returned.

## Execution paths

| Query | What may come from the cache |
|---|---|
| No hint, slot is a converted DTO/View (`table.fetch(View::class)`, also inside a typed tuple or a joined slot) and that exact entity type declares an object cache | **Automatic admission** (since 1.1.11). Mask = the projection's stored scalar leaves, recomputed on every execution. Works for ordinary and `content-only` caches |
| No hint, bare entity or entity-Fetcher slot | Nothing — stays fresh SQL, also beside an admitted DTO sibling |
| `useObjectCache()` / `useObjectCache(true)`, cache not content-only | Legacy hydration (1.1.0): ID-only skeleton, whole entity bodies from the object cache |
| `useObjectCache()` on a `content-only` root | Same derived stored-leaf mask as automatic admission; if it cannot be honored, the complete projection runs on a cache-disabled client |
| `useObjectCache(fetcher)` | Explicit whitelist (1.1.3): only the named stored scalar/embedded/JVM-formula-dependency leaves; association entries only navigate into children |
| `useObjectCache(false)` | Nothing. Also suppresses automatic admission |

Never admitted into a derived mask: id, discriminator, `@Version`, `@LogicalDeleted`, formulas, persistent associations, views (`@IdView` etc.), remote and non-stored properties, SQL-template properties. An explicit mask naming a collection, ID view, remote/non-stored property, SQL formula or version/logical-delete property is rejected; it must be a subset of the query's own fetcher.

A cache declared only on a subtype does not admit a base-type slot. A `content-only` policy declared on a base type covers derived types.

## Guards (all paths)

Admission is declined — the original query runs fresh — unless every guard holds:

- QUERY purpose; command/save-internal reads, `forUpdate`/locking reads, aggregates, count, `stream`/`forEach` and merged set queries with a mask stay fresh.
- The connection manager positively proves the connection is outside a transaction: `ConnectionManager.isTransactionKnownInactive(con)`. The fork implements it in the Spring, Quarkus (JTA status `NO_TRANSACTION` and auto-commit) and Jimmer transaction managers; the default implementation returns `false`. **A read inside `@Transactional` is therefore not admitted.**
- Cacheable slots: no effective join fetch of the slot, no JOINED-inheritance root, a derived/base-table root declines.
- Native ID lookups (`findById`/`findByIds`/`findMapByIds`) are outside this policy; they keep their complete-snapshot-or-fresh behavior and are fresh under `content-only`. Since 1.2.0 a type whose only filter is the built-in logical-delete filter judges visibility on the cached row, so a warm ID read issues no SQL; a user, reversed, ignored or sharding filter, `forUpdate` and content-only types keep the SQL visibility check.

Fallback: a missing, negative or incompatible cached value (wrong concrete type, incomplete payload) re-runs the **whole original query once**; a page is never shortened. Only an explicit content hint (`useObjectCache()` or an explicit Fetcher mask) keeps the isolated cache-disabled fallback — an automatic admission that could not be applied (an active transaction, a locking read, an ineligible slot) continues on the ordinary cached path. A real cache exception propagates.

## Content-only caches

`CacheFactory.isObjectCacheContentOnly(type)` (Quarkus: `content-only: true`) keeps the cache for content admission only: bare entity reads, ID reads and association loading stay SQL-backed, converted DTO/View slots are still admitted. Per-field lists (`content-fields`) existed only in 1.1.7–1.1.11 and are removed from 1.1.12; delete the key when upgrading.

## Application admission policy

`org.babyfish.jimmer.sql.cache.CacheAdmissionPolicy` (1.1.13; row overload 1.1.14), exposed through `CacheFactory.isObjectCacheAdmissible(type)` and `isObjectCacheAdmissible(type, row)`. Default admits everything.

| Method | Effect of `false` |
|---|---|
| `isAdmissible(type)` | Query-level gate: every row of that type stays fresh. Overrides an explicit `useObjectCache(...)` and the legacy declared-cache path |
| `isAdmissible(type, row)` | Only this row is loaded once with the full projection on the cache-disabled client, never seeds the cache, and is overlaid like the others; sibling rows stay cached |

`row` is the freshly retained entity instance of this query, not the DTO: only retained properties are loaded and all come from this SQL. Decide from id/FK/version values and check `ImmutableObjects.isLoaded` before reading anything else.

```kotlin
@RequestScoped // Quarkus: optional CDI bean; inactive request scope => denied
class EditableRowsStayFresh(private val viewer: Viewer) : CacheAdmissionPolicy {
    override fun isAdmissible(type: ImmutableType) = true
    override fun isAdmissible(type: ImmutableType, row: Any): Boolean =
        !(row is Document && viewer.canEdit(row.id))
}
```

Outside Quarkus implement both methods on your `CacheFactory`/`KCacheFactory`; a custom factory that wraps another must delegate `isObjectCacheContentOnly`/`isObjectCacheAdmissible` (`GuardedCacheFactory` does).

## Freshness contract

Admission follows projection metadata and guards, not caller intent: a DTO used for an edit form is admitted like a display DTO. Cached content is a whole-row snapshot whose freshness is bounded by invalidation delivery (CDC). For a read that must observe the current row use a bare entity projection, `useObjectCache(false)`, or deny it in the policy. Admission never serves membership, authorization facts or association edges from the cache.

## Verify

1. With SQL and cache logs on, a warm admitted DTO query issues the skeleton SELECT and a cache GET, not a full-column SELECT.
2. Put a deliberately stale value in the cache: admitted leaves may be stale, while membership, order, FK/edges, unapproved leaves and scalar slots match SQL.
3. The same query inside a transaction, with `forUpdate`, with `useObjectCache(false)`, and for a policy-denied row returns current values.
4. A negative or wrong-type cache entry triggers one full fallback query, not a shortened page.

Sources: [`ConfigurableRootQuery`](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/query/ConfigurableRootQuery.java), [`CacheAdmissionPolicy`](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/cache/CacheAdmissionPolicy.java), [`CacheFactory`](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/cache/CacheFactory.java), [`ConfigurableRootQueryImpl`](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/impl/query/ConfigurableRootQueryImpl.java), [`ObjectCacheQueryProjectionTest`](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/cache/ObjectCacheQueryProjectionTest.java), [FORK.md](https://github.com/sleepkqq/jimmer/blob/main/FORK.md).
