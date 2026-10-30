---
name: jimmer-dev-cache-internals
description: Maps the fork's object-cache internals - query hydration (useObjectCache hints, content masks, per-row admission, opaque seeds), content-only entity policy, filtered multi-view caches and Quarkus cache runtime - with their invariants, tests and known debt. Use before changing or reviewing anything under sql/cache, EntitiesImpl, ObjectCacheQueryExecution, CacheContentMask, data loaders or project/jimmer-quarkus/runtime cache code, or when asked "почини кэш", "переделай кэширование", "почему кэш не используется". Not for configuring caches in an application (consumer skill jimmer-caching).
---

# Object-cache internals

Read [references/invariants.md](references/invariants.md) before editing. Every rule there
records a fixed defect; weakening one needs a test proving the replacement.

## Contract in one paragraph

Object caches are single-view **content** caches. SQL stays authoritative for membership,
visibility, join topology, order, duplicates, pagination and scalar slots. The cache only
supplies stored leaves of rows SQL already selected. Anything missing, negative, incompatible
or unproven falls back to the **complete original query** — never a shortened page. Shared
caches receive content only with positive proof of an inactive transaction on the executing
manager's own connection; locking, command and streaming reads never touch them.

## Map

| Concern | Location |
|---|---|
| Public hint API | `sql/ast/query/ConfigurableRootQuery.java` (`useObjectCache`), Kotlin `kt/ast/query/KConfigurableRootQuery.kt` |
| Entry and fallback | `ast/impl/query/ConfigurableRootQueryImpl.java` (`execute` path, cache-disabled fallback client) |
| Hint/mask state | `ast/impl/query/TypedQueryData.java` (copied through every `with*` method) |
| Execution | `ast/impl/query/ObjectCacheQueryExecution.java` — skeleton query, seeds, cache load, validation, tuple rebuild |
| Mask tree | `ast/impl/query/CacheContentMask.java` — compile, projection reduction, seeds, validate, overlay |
| Visibility proof | `ast/impl/query/ObjectCacheQuerySeed.java`, `ast/impl/EntitiesImpl.java` (`findMapByIdsForQuery`, `forSqlClient`) |
| Transaction proof | `runtime/ConnectionManager.java` (`isTransactionKnownInactive`), `transaction/AbstractTxConnectionManager.java` |
| Admission policy | `cache/CacheAdmissionPolicy.java`, `cache/CachesImpl.java`, `cache/CacheConfig.java`, `cache/CacheFactory.java` |
| Native fetcher filtering | `fetcher/impl/FetcherFactory.java` (`filterExpanded`), `fetcher/impl/FetcherImpl.java` |
| Association assembly | `loader/AbstractDataLoader.java`, `loader/Utils.java` |
| Quarkus | `project/jimmer-quarkus/runtime/.../cfg/JimmerCacheConfig.java`, `runtime/cache/**` (local/Redis factories, binders, trackers, `TransactionCacheOperatorFlusher`) |

Paths under `sql/` and `ast/` are relative to
`project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/`.

## Tests

- `project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/cache/ObjectCacheQueryProjectionTest.java`
  — hydration, masks, admission, fallback (large; new cases may go in a focused sibling class).
- Same package: `PolymorphicObjectCacheModeTest`, `ObjectCacheTest`, `FilterCacheTest`,
  `FilterCacheEvictTest`, `LogicalDeletedCacheTest`, `DraftIsolationTest`, `MutateCacheTest`.
- Quarkus: `project/jimmer-quarkus/integration-tests` (PostgreSQL and Redis via Testcontainers).

```bash
./gradlew :jimmer-sql:test --tests 'org.babyfish.jimmer.sql.cache.*'
```

Every behavioural change asserts both the executed SQL (`ctx.sql(...)`) and which cache
entries were touched, and covers the fallback: an incompatible or missing cached value must
produce the original query's full result.

## Known debt (confirmed by review, 2026-10)

Fix these before adding features on top; each is a separate, test-first change.

1. `ObjectCacheQueryExecution.tryExecute` (~300 lines) and `tryExecuteWithContent` (~340 lines)
   duplicate the eligibility prelude, skeleton execution and tuple rebuild; `Slot` and
   `MaskSlot` are parallel types. Lift the shared mechanics, keep mask-specific decisions apart.
2. `CacheContentMask` (~1200 lines) owns six jobs and repeats the children/type-branch walk in
   about eleven methods with drifting coverage. Split compile / reduction / hydration and use
   one traversal primitive.
3. An explicit mask with a recursive property throws `IllegalArgumentException` from
   `execute()` (`CacheContentMask.of`), while the documented contract is to decline. Validate
   caller-supplied masks at `useObjectCache(Fetcher)`; decline shape-dependent cases at execution.
4. `restoreVisibility`/`explicitSelection` dereference a projection that can be null; only the
   unrelated `hasInheritedBranchChild` decline prevents it. One inherited-branch child also
   disables the hint for the whole projection.
5. Public surface leaks internals: `FetcherFactory.filterExpanded` is public with a nullable
   native-only contract; `useObjectCache(boolean)` defaults to a silent no-op on both
   interfaces; Kotlin KDoc omits streaming and mask-fallback details present in Java docs.
6. Masks are identity-keyed map keys in `ObjectCacheQueryExecution`; two equal masks never
   share a cache load. Keep identity semantics explicit if this stays.

7. Own-write eviction (`MutationKeyListener`) evicts only owning-side association caches. The
   inverse (`mappedBy`) side and parents' one-to-many caches after an FK change still wait for CDC;
   report the inverse prop / old and new parent ids from the same hooks to close it.

## Upstream friction

Fork hooks live in upstream classes `ConfigurableRootQueryImpl`, `TypedQueryData`,
`MergedTypedRootQueryImpl`, `FetcherSelectionImpl`, `EntitiesImpl`, `AbstractDataLoader` and
`CachesImpl`. Keep them to narrow calls into fork-owned classes; every new field in
`TypedQueryData` must be copied in all of its `with*` methods.
