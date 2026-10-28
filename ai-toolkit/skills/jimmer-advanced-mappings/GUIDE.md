# Jimmer Advanced Mappings

Use for computed props, id views, logical deletion, embeddables, JSON columns, and derived associations. Base entity design lives in `jimmer-entity`.

## @Formula — calculated scalar

```kotlin
// In-memory, from loaded props (works in DTOs/fetchers, auto-adds dependencies)
@Formula(dependencies = ["firstName", "lastName"])
val fullName: String
    get() = "$firstName $lastName"

// SQL-level, computed in DB; %alias is the current table alias
@Formula(sql = "concat(%alias.FIRST_NAME, ' ', %alias.LAST_NAME)")
val fullName: String
```

`dependencies` are property names (not columns) and may traverse associations or embeddables (`"authors.firstName"`, `"nameInfo.lastName"`); in-memory formulas may depend on other formulas. SQL formulas are abstract, appear in the table DSL, and their optional `dependencies` must be scalars. Choose in-memory computation for fetched data and SQL computation when query predicates/order require it. Formula properties are not ordinary writable columns.

Kotlin plain getters (0.12.4+, [#1519](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-ksp/README.md)): a `val` with an implemented getter and no `@Formula` — expression, block, inherited or inside an embeddable — is excluded from Jimmer metadata, Draft, fetchers and DDL. It is fine for pure Kotlin convenience logic, but it is invisible to fetchers/DTOs and throws `UnloadedException` if its inputs were not fetched. Add `@Formula(dependencies = ...)` to make it fetch-aware; other Jimmer annotations on it are rejected.

## @IdView — FK as scalar

```kotlin
@ManyToOne
val relatedObject: RelatedObject

@IdView                          // view of relatedObject.id, no extra query
val relatedObjectId: UUID

@IdView("labelObjects")          // ids of a list association
val labelObjectIds: List<UUID>
```

## @ManyToManyView — deep association shortcut

```kotlin
@OneToMany(mappedBy = "book")
val authorLinks: List<BookAuthor>

@ManyToManyView(prop = "authorLinks", deeperProp = "author")
val authors: List<Author>        // read-only view through an association entity
```

## @LogicalDeleted — soft delete

```kotlin
@LogicalDeleted("true")          // boolean flag; deleted when true
val deleted: Boolean

@LogicalDeleted("now")           // timestamp; null = live, deleted when set
val deletedAt: LocalDateTime?

@Default("NEW")                  // initial live value
@LogicalDeleted("DELETED")       // enum (or int): every other constant is live
val status: Status

@LogicalDeleted(generatorType = MyLongGenerator::class)  // unique tombstone values (also generatorRef)
val deletedMillis: Long
```

- Global filter hides deleted rows automatically; `deleteById` becomes `UPDATE` when entity has the flag (`DeleteMode.AUTO`/`LOGICAL`; `PHYSICAL` forces a real delete).
- Save contract (0.12.4+, [#1359](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-core/src/main/java/org/babyfish/jimmer/sql/LogicalDeleted.java)):
  - a **loaded live** value is written as given (`RUNNING` stays `RUNNING`); an **unloaded** flag is initialized (from `@Default` or the strategy's live value) only on insert — never reset on update or the update branch of an upsert;
  - a **loaded deleted** value (including one set by a `DraftInterceptor`) is rejected with `IllegalArgumentException` — logical deletion goes through a delete command so its association rules run;
  - key matching: a non-null enum/int flag with a single deleted value is added to the business key with its exact live value (an unloaded flag matches by its initial value). Upserting `code=A, status=RUNNING` will not match an existing `code=A, status=NEW` row; change the status of an existing row by ID. The test model pairs this with `unique(CODE, STATUS)`.
- `@KeyUniqueConstraint` upserts + logical delete — two valid schema shapes:
  - multi-version flag (timestamp/tombstone values): the DB unique constraint must include the delete-flag column (`unique(key..., deleted_millis)`) — deleted versions coexist;
  - boolean flag: a partial unique index (`unique ... where deleted = false`) works for key-based upsert on dialects that can express a conflict predicate (PostgreSQL); dialects that cannot fall back to select-then-write.
- `@JoinTable(deletedWhenEndpointIsLogicallyDeleted = true)` physically deletes middle-table rows of a logically deleted endpoint; a `@JoinTable` with its own `logicalDeletedFilter` logically deletes them instead.

## @Embeddable + @PropOverride

```kotlin
@Embeddable
interface Point {
    val x: Int
    val y: Int
}

// in entity
@PropOverride(prop = "x", columnName = "LEFT_X")
@PropOverride(prop = "y", columnName = "TOP_Y")
val topLeft: Point
```

Embeddable props usable in DSL (`table.topLeft.x`), DTOs (`flat(topLeft)` or a nested block), and composite ids. `@PropOverride.prop` is a dot-separated chain for nested embeddables.

Since 0.12.4 ([#1168](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-dto-compiler/src/main/java/org/babyfish/jimmer/dto/compiler/DtoPropBuilder.java)) a `.dto` file may `export` the embeddable type itself, and entity DTOs can reuse those types for the embedded property (`contact -> ContactInfoInput`) in views and in plain/dynamic inputs. Before 0.12.4 reuse (`->`) was association-only. DTO syntax lives in `jimmer-dto`.

## @Serialized — JSON column

```kotlin
@Serialized
val tags: List<String>           // jsonb/json column, Jackson-serialized
```

Match the JDBC dialect's JSON representation and test a database round trip. `@Serialized` is column mapping, not HTTP serialization or DTO input validation.

### Jackson module selection

Inspect the application's actual mapper API and the resolved Jimmer module. Current source exposes `org.babyfish.jimmer.jackson.v2.ImmutableModuleV2` for `com.fasterxml.jackson.databind.ObjectMapper` and `org.babyfish.jimmer.jackson.v3.ImmutableModuleV3` for the `tools.jackson` API. Older documentation may show the removed unsuffixed `ImmutableModule`; do not copy that import without checking source. Framework integrations may register the matching module automatically. Jimmer core's optional Jackson dependencies do not guarantee that databind is present in a standalone consumer.

For generated Inputs, verify JSON omission/null/value through the mapper used by the application, then check `toEntity()` loaded state. Loading the wrong module or manually copying fields can destroy that distinction.

## @MapsId — target id inside own id

Use when the schema itself says so: the association's FK columns ARE the owner's PK (shared-PK one-to-one) or one segment of a composite PK. It describes column identity, not naming.

```kotlin
@Id
val bookId: Long                 // ordinary @Id — NOT an @IdView

@MapsId                          // whole id mapped from the target
@OneToOne
@JoinColumn                      // still required (owning side); name comes from the naming strategy
val book: Book
```

For composite ids, `@MapsId("pathInsideId")` maps the target id into one path of the embedded id; the other parts stay local. If both ID and association are provided they must agree; verify how the installed save implementation derives missing mapped segments instead of duplicating assignment logic.

Query optimization: id-oriented predicates/order/group/selections on the association reuse the owner's own columns (no join), and a mapped-id association used purely as a bridge gets its middle join removed — only where semantics (nullability, no target columns referenced) allow.

## @Transient + TransientResolver — computed association/aggregate

```kotlin
@Transient(ref = "domainObjectCountResolver")   // DI bean name (or value = Resolver::class)
val childCount: Long
```

Resolver implements `KTransientResolver<ID, V>` (Kotlin) / `TransientResolver<ID, V>` (Java) with batch `resolve(ids)` — N+1 safe, cacheable. For an entity-returning property `V` is the target ID (or `List<ID>`), not the entity. `ref` resolves a container bean (Spring; the Quarkus fork resolves CDI beans) — use it when the resolver class lives in another module.

Shared resolver across entities — resolver context (experimental, opt-in via `@ExperimentalTransientResolverContext` in Kotlin, `KTransientResolverContext`; Java `TransientResolverContext`): the `resolve(ids, ctx)` overload exposes `ctx.prop` (which property/declaring type is being resolved), `ctx.connection`, `ctx.sourceIds`, `ctx.resolver` — one resolver implements the algorithm (e.g. tree breadcrumbs) for every concrete entity instead of one resolver class per entity. Old single-arg resolvers keep working unchanged.

## Generic mapped superclass

`@MappedSuperclass` supports self-bounded generics with `where` constraints — define recursive structure once:

```kotlin
@MappedSuperclass
interface BaseTreeNode<T> where T : BaseTreeNode<T> {
    @ManyToOne val parent: T?
    @OneToMany(mappedBy = "parent") val children: List<T>
}

@Entity
interface ProductCategory : BaseTreeNode<ProductCategory> { @Id val id: Long }
```

`parent`/`children` materialize with the concrete type (`ProductCategory`). Multiple inheritance of mapped superclasses is allowed (`BaseEntity, TenantAware`) — the tenant/audit pattern pairs with global filters and draft interceptors. Combine with resolver context: one shared resolver serves all tree entities extending the same base.

## Enum mapping

```kotlin
@EnumType(EnumType.Strategy.NAME)   // NAME (default varchar) or ORDINAL
enum class Status {
    @EnumItem(name = "A") ACTIVE,
    @EnumItem(name = "D") DISABLED,
}
```

`@EnumItem(ordinal = ...)` overrides the stored ordinal for `ORDINAL`.

## Misc

- `@Default("0")` — ORM-level default for unloaded primitive props.
- `@ExcludeFromAllScalars` — prop skipped by `#allScalars` in DTOs.
- `@JoinSql("...")` — custom SQL join predicate using `%alias` and `%target_alias` for nonstandard associations. Not saveable, cacheable, remote, or join-optimizable.
- `@DatabaseValidationIgnore` — skip DB-schema validation for entity/prop (on the `@Id`, it skips the primary-key check). Since 0.12.4 ([#1319](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/runtime/DatabaseValidators.java)) primary keys and real foreign keys are actually validated; see `jimmer-migrations`.
- `ForeignKeyType.FAKE` on `@JoinColumn(foreignKeyType = ...)` — association without real DB FK constraint.

Sources: [advanced mapping](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/), [MapsId](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/maps-id), [logical deletion](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/logical-deleted/entity), [transient resolver](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/calculated/transient).
