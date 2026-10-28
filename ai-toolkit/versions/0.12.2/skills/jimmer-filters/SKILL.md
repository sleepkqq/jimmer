---
name: jimmer-filters
description: Implement Jimmer global filters for tenant or visibility scopes, cacheable filter parameters and invalidation; verify filter behavior across root queries, associations, caches and mutation boundaries.
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
5. Test roots, to-one/to-many association fetches, ID loads and two successive request contexts. A non-null association whose target is filtered out needs a deliberate fetching/nullability policy.

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

Use the same object/owner IDs under two scopes and assert isolation both cold and warm. Change a filter-dependent field through another writer, deliver its CDC event and assert affected views refresh. Include missing-context and explicit administrative cases.

Sources: [custom filters](https://babyfish-ct.github.io/jimmer-doc/docs/query/global-filter/user-filter), [cacheable filter contract](https://github.com/babyfish-ct/jimmer/blob/v0.12.2/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/filter/CacheableFilter.java), [multi-view caching](https://babyfish-ct.github.io/jimmer-doc/docs/cache/multiview-cache/).
