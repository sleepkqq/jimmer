---
name: jimmer-filters
description: Implement Jimmer Java/Kotlin global visibility filters, sorted cache parameters and event invalidation; verify tenant isolation across reads and mutations.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Global filters

Use global filters for visibility rules shared by root queries and association loading. Specifications are for user-entered search criteria. Required tenant identity comes from a trusted context; reject missing identity rather than skipping the predicate.

Java uses generated Props and `org.babyfish.jimmer.sql.filter.*`:

```java
public final class TenantFilter implements Filter<TenantScopedProps> {
    private final String tenant;

    public TenantFilter(String tenant) {
        this.tenant = Objects.requireNonNull(tenant);
    }

    @Override
    public void filter(FilterArgs<TenantScopedProps> args) {
        args.where(args.getTable().tenantId().eq(tenant));
    }
}
```

Kotlin uses the model interface and `org.babyfish.jimmer.sql.kt.filter.*`:

```kotlin
class TenantFilter(private val tenant: String) : KFilter<TenantScoped> {
    override fun filter(args: KFilterArgs<TenantScoped>) {
        args.where(args.table.tenantId eq tenant)
    }
}
```

Assumes an existing `@MappedSuperclass TenantScoped` with `tenantId`. A fixed tenant per client is useful for isolated clients/tests; do not capture the first request's tenant into a global singleton. Production request-context filters must obtain the same trusted value for both predicate and cache parameters. Register through the existing Spring bean discovery or client builder.

## Cacheable variant

Implement Java `CacheableFilter<TenantScopedProps>` / Kotlin `KCacheableFilter<TenantScoped>` when property caches are enabled:

- Java `SortedMap<String, Object> getParameters()` / Kotlin `getParameters(): SortedMap<String, Any>?` must include **every visibility-affecting parameter** with stable values. For example Java `new TreeMap<>(Map.of("tenant", tenant))`, Kotlin `sortedMapOf<String, Any>("tenant" to tenant)`.
- Implement `isAffectedBy(EntityEvent<?>)` and required dependency invalidation. Parameters prevent view collisions; they do not evict stale entries. A conservative `true` is correct but may invalidate more than needed; refine from actual changed properties.
- Use multi-view/parameterized association/resolver caches, or keep affected properties uncached. Object caches remain single-view.
- Inspect abandoned-cache callbacks; a configured single-view property cache can be ignored for a filtered target.

Choose invalidation from the real writer topology (`jimmer-caching`).

## Mutation boundary and verification

A query filter is not complete write authorization. Set trusted tenant fields server-side, validate association IDs and inspect each save/update/delete path. Keep administrative bypass explicit. Test root, ID, to-one/to-many loading in two scopes, with cold and warm caches, missing context and filter-dependent changes. A non-null association whose target becomes invisible needs a deliberate mapping/fetch policy.

Sources: [Java filter contract](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/filter/CacheableFilter.java), [Kotlin filter contract](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/filter/KCacheableFilter.kt).
