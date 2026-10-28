# Advanced mapping reference

## ID views and calculated columns

For nullable `Book.store`, match nullability:

```java
@IdView("store")
@Nullable Long storeId();

@Formula(sql = "upper(%alias.NAME)")
String upperName();
```

```kotlin
@IdView("store")
val storeId: Long?

@Formula(sql = "upper(%alias.NAME)")
val upperName: String
```

List ID views use `@IdView("authors")` with `List<ID>`. They describe the association's IDs, not an independent mutable database column. SQL formulas depend on dialect; in-memory formulas use declared dependencies. Do not read an unloaded dependency by constructing a partial object and calling its formula directly.

## Association entity shortcut

Java: `@OneToMany(mappedBy = "book") List<BookAuthor> authorLinks();` plus `@ManyToManyView(prop = "authorLinks", deeperProp = "author") List<Author> authors();`.

Kotlin uses the same annotations on `val authorLinks: List<BookAuthor>` and `val authors: List<Author>`. Save the real link association; the shortcut is read-only.

## Logical deletion

Java `@LogicalDeleted("true") boolean deleted();`, Kotlin `@LogicalDeleted("true") val deleted: Boolean` enable the built-in visibility filter and logical delete path. Timestamp/tombstone generators are available for multiple historical rows. `DeleteMode.PHYSICAL` is a deliberate physical-delete request.

Align unique constraints with retained history. `(business_key, boolean_deleted)` permits only one deleted row per key; it does not support unlimited deleted duplicates. Test the generated upsert SQL against the actual constraint/dialect. Inspect join-table logical-deletion options and both association directions when cache invalidation depends on them.

## Embedded values

Java:

```java
@Embeddable
public interface Point {
    int x();
    int y();
}
```

Kotlin:

```kotlin
@Embeddable
interface Point {
    val x: Int
    val y: Int
}
```

On an owning property, `@PropOverride(prop = "x", columnName = "LEFT_X")` / `@PropOverride(prop = "y", columnName = "TOP_Y")` remap columns. Embedded values support nested DSL paths and DTO `flat`. Save a complete required embedded/composite-ID value; partial components can cause `IncompleteProperty`.

## JSON and enum values

Java `@Serialized List<String> tags();`, Kotlin `@Serialized val tags: List<String>` map a JSON column. Test a real JDBC round trip; HTTP validation and serialization are separate boundaries. Standalone Jackson 2 needs databind and `ImmutableModule`; Spring may register it automatically.

Use `@EnumType(EnumType.Strategy.NAME)` (default) or `ORDINAL` deliberately. `@EnumItem(name = "A")` / `ordinal = ...` align stored enum codes. Renaming constants or changing ordinal order can be a data migration.

## Transient resolvers

Java `@Transient(ref = "bookCountResolver") long bookCount();` (Spring bean, useful across modules) or `@Transient(BookCountResolver.class)`, Kotlin `@Transient(ref = "bookCountResolver") val bookCount: Long` reference an integration-managed resolver.

Implement Java `TransientResolver<ID, Value>` or Kotlin `KTransientResolver<ID, Value>` with `resolve(ids)` returning a map for the requested owner IDs. Query all owners in one batch, preserve expected empty/default values, and use the resolver's supported connection/current-filter mechanisms. Register dependency-triggered eviction if its result is cached. Implement the `resolve(ids)` form; do not use a context-aware `resolve(ids, ctx)` overload.

For shared fields use a concrete-property `@MappedSuperclass`. Do not assume self-bounded generic recursive mapped superclasses; verify the processor before proposing one.

Sources: [TransientResolver](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/TransientResolver.java), [KTransientResolver](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/KTransientResolver.kt), [Jackson module](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-core/src/main/java/org/babyfish/jimmer/jackson/ImmutableModule.java).
