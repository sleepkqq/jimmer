---
name: jimmer-advanced-mappings
description: Map Jimmer formulas, transient resolvers, logical deletion, embedded/JSON values, ID views and derived associations with the generated Java/Kotlin APIs.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Advanced mappings

Start with `jimmer-entity` for ownership/nullability. Here every mapping must agree with loaded state, real schema, writes and cache invalidation. Read [GUIDE.md](GUIDE.md) for annotation examples.

| Requirement | Mechanism |
|---|---|
| Computed from already loaded fields | `@Formula(dependencies = ...)` |
| DB expression usable in query/filter/order | `@Formula(sql = ...)` |
| Association FK IDs | `@IdView` |
| Read-only traversal through link entity | `@ManyToManyView` |
| Soft deletion | `@LogicalDeleted` + actual unique/delete semantics |
| Embedded columns / composite ID | `@Embeddable`, `@PropOverride` |
| JSON column | `@Serialized` + compatible dialect/Jackson |
| Batched calculated association/value | `@Transient` + resolver |

**`@MapsId`, `@DatabaseDefault`, polymorphic entity inheritance and resolver-context overloads are not available**; do not imitate them with duplicate physical-column mappings. A shared-PK mapping needs a schema-specific, compiled and round-trip-tested design; do not invent a mapping merely from the column names.

Java formula on an entity (`org.babyfish.jimmer.Formula`, not the SQL annotation package):

```java
@Formula(dependencies = {"name"})
default String displayName() {
    return "Book: " + name();
}
```

Kotlin equivalent:

```kotlin
@Formula(dependencies = ["name"])
val displayName: String
    get() = "Book: $name"
```

Request the formula in the Fetcher/View; Jimmer includes its dependencies. Formula fields are not ordinary writable columns. Kotlin must compile default interface implementations with the project's appropriate JVM-default option.

Verify metadata compilation, selected and omitted shapes, mutation/schema round trip and relevant cache eviction. Jackson 2 uses `org.babyfish.jimmer.jackson.ImmutableModule`.

Source: [release annotations](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-core/src/main/java/org/babyfish/jimmer/sql).
