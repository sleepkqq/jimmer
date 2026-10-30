---
name: jimmer-filters
description: Implements Jimmer global filters (Filter, KFilter, CacheableFilter) for tenant isolation or visibility rules, their cache parameters and invalidation, and verifies them across root queries, associations, caches and writes. Use when the user wants a predicate applied to every query of an entity, such as tenant scoping or hiding blocked or deleted rows. Not for per-request search filters (jimmer-query, jimmer-dto Specifications) or UI filters.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Jimmer Global Filters

Use a global filter for a query visibility rule shared across root queries and association loads. Use a Specification/ordinary predicate for user-entered search criteria. Read the resolved filter API and existing registration before changing scope.

## Implementation path

1. Identify the filtered entity or existing mapped superclass and the trusted request context supplying its scope.
2. Java implements `Filter<ScopeProps>` using generated Props; Kotlin implements `KFilter<Scope>` using the entity/base interface itself.
3. Add the predicate through `FilterArgs`/`KFilterArgs`; register via the framework's existing bean discovery or SQL-client builder.
4. Decide how missing context behaves. For required tenant isolation, reject the operation or produce no results; do not silently omit the predicate and expose every tenant. Administrative bypass must be explicit and scoped.
5. Test roots, to-one/to-many association fetches, ID loads, subqueries and two successive request contexts. A non-null association whose target is filtered out needs a deliberate fetching/nullability policy.

## Where filters apply (version-sensitive)

| Location | Behavior |
|---|---|
| Joins inside a subquery's **select list**, including nested subqueries | Filtered since v0.12.4 (#1203): inner join → predicate in the subquery `WHERE`, left join → in the join `ON`. Earlier versions filtered only subquery predicates, so a scalar subquery projection could read hidden rows. Expect changed SQL and results after upgrading |
| Fork: pagination/count of queries with projection subqueries | Filters applied to retained subqueries before the count query is derived (fork 1.0.6) |
| Fork: ID reads with a single-view object cache | A user filter forces the current visibility to be rechecked by one ID-only SQL before cached bodies are used; `forUpdate` bypasses the cache (fork 1.0.6). When the only filter is the built-in logical-delete filter, its visibility is judged on the cached row instead, so a warm read issues no SQL (fork 1.2.1) |
| Fork: object-cache query admission | Membership and visibility stay SQL; only admitted content comes from the cache. `CacheAdmissionPolicy` controls freshness, not authorization |

## Cache contract

A plain filter can make affected property caches unusable. For cacheable variants implement `CacheableFilter`/`KCacheableFilter`:

- Return a stable sorted parameter map containing **every visibility-affecting value**. Empty/missing tenant parameters collapse distinct views.
- Implement `isAffectedBy` and any dependency invalidation required by the filter. Parameterizing keys alone does not invalidate them.
- Use parameterized/multi-view caches for affected associations/resolvers, or leave them uncached. Object caches remain single-view; do not add per-tenant variants to the object cache API.
- Inspect the abandoned-cache callback rather than assuming a configured cache is being used.

For implementation and CDC delivery use `jimmer-caching`. `TRANSACTION_ONLY` and `BOTH` are deprecated; cacheable filters do not make those modes a supported default.

## Mutation boundary

A query filter is not a complete authorization policy. Verify the exact save/update/delete path: do not assume read filters automatically validate writable tenant IDs, referenced IDs, raw SQL or arbitrary mutation predicates. Set trusted scope fields server-side, validate associations and apply required write predicates/constraints. Do not disable logical-deletion or visibility filters just to make a test pass.

## Verify

Use the same object/owner IDs under two scopes and assert isolation both cold and warm, including a scalar subquery in the select list and, on the fork, an admitted DTO query and a paged count. Change a filter-dependent field through another writer, deliver its CDC event and assert affected views refresh. Include missing-context and explicit administrative cases.

Sources: [custom filters](https://babyfish-ct.github.io/jimmer-doc/docs/query/global-filter/user-filter), [cacheable filter contract](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/filter/CacheableFilter.java), [subquery selection filters](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/query/SubQueryGlobalFilterTest.java), [multi-view caching](https://babyfish-ct.github.io/jimmer-doc/docs/cache/multiview-cache/), fork [tenant filter and cache fixes](https://github.com/sleepkqq/jimmer/blob/main/FORK.md).
