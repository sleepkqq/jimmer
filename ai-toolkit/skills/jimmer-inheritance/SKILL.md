---
name: jimmer-inheritance
description: Maps polymorphic Jimmer entity hierarchies with SINGLE_TABLE or JOINED, discriminators, subtype fetchers and DTO branches, type matching in queries, and explicit entity type transitions. Use when the user models subtypes of an entity or needs to change an entity's type. Not for mapped superclasses that only share columns (jimmer-entity) or Kotlin sealed classes outside the ORM.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Jimmer Entity Inheritance

Use entity inheritance for a polymorphic identity/query contract. `@MappedSuperclass` only shares properties; it does not create a queryable entity hierarchy. Check installed annotation, DTO compiler and mutation APIs before changing a hierarchy.

## Map the hierarchy

```kotlin
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
interface Asset {
    @Id
    val id: Long

    @Discriminator
    val kind: String

    val name: String
}

@Entity
@DiscriminatorValue("BOOK")
interface BookAsset : Asset {
    val isbn: String
}

@Entity
@DiscriminatorValue("TOOL")
interface ToolAsset : Asset {
    val inspectionInterval: Int
}
```

- Declare `@Inheritance` once at the root (default strategy `SINGLE_TABLE`) and one inherited ID. Every concrete branch is an `@Entity`.
- Both `SINGLE_TABLE` and `JOINED` require a physical `@Discriminator` property (String or enum). Prefer explicit stable `@DiscriminatorValue` values.
- The discriminator is read-only: saves derive it from the runtime entity type. Do not implement type transitions by assigning it.
- A type with derived types and no discriminator value is abstract in Jimmer. A leaf's default value is its simple type name; an explicit value can make a root/intermediate type instantiable.
- `SINGLE_TABLE`: subtype-only **database columns must be nullable**, even when the property's subtype contract is non-null.
- `JOINED`: root holds ID/discriminator/common columns; each derived table's ID is its PK and an FK to its direct entity parent. There is no table-per-class strategy.
- Joined physical deletion defaults to explicit child-table deletes (`JoinedTableDissociateAction.DELETE`). `joinedTableDissociateAction = JoinedTableDissociateAction.LAX` is appropriate only when the schema really supplies all required `ON DELETE CASCADE`s.
- Database validation (`DatabaseValidationMode` `WARNING`/`ERROR`) checks discriminator columns, single-table subtype nullability and — effective since 0.12.4 (#1319) — primary keys and real association FKs. The joined derived-table-to-parent FK is not among the checks; review it in the migration.

## Query and DTO shape

Root queries default to polymorphic matching and materialize actual subtypes. Root fetchers use `forType(...)` for branch-only fields. `instanceOf` includes descendants, `exactType` matches one type, `treatAs` has inner semantics, and `tryTreatAs` preserves nonmatching rows with nullable branch values.

```dto
export com.example.catalog.Asset

AssetView {
    id
    name
    #types {
        #exhaustive
        BookAsset {
            isbn
        }
        ToolAsset {
            inspectionInterval
        }
    }
}
```

`#exhaustive` rejects missing concrete branches at compile time. Without it, omitted types use the implicit common-fields branch; an explicit `default` customizes that branch. `input` supports the same branching and converts to the corresponding entity subtype.

## Mutations

Mutations default to `TypeMatchMode.AUTO` (exact for instantiable types, polymorphic for abstract ones), unlike root queries' polymorphic default. An abstract root can use `UPDATE_ONLY` with `AUTO`/`POLYMORPHIC`, not insert/upsert.

Mode is set with `setTypeMatchMode(TypeMatchMode.…)` on save commands and mutable update/delete statements (`setAssociatedTypeMatchModeAll` and per-type/per-property variants for associated objects). Type changes are disabled by default. Enable `setTypeChangeAllowed(true)` (Kotlin DSL `setTypeChangeAllowed()`) only for an intended transition and **exact** save matching; it cannot be combined with polymorphic matching. `setDefaultTypeChangeAllowed` and `setAssociatedTypeChangeAllowed*` exist, but do not enable it globally to fix a discriminator mismatch. Query-derived DML (insert/upsert from select) supports single-table subtypes, not joined-inheritance targets.

## CDC / binlog events

Since 0.12.4 (#1516), a `SINGLE_TABLE` row fed to `sqlClient.binLog.accept(...)` (the CDC path recommended over transaction triggers) is materialized as its concrete subtype by the discriminator column; columns belonging to other subtypes are ignored, and entity events and cache invalidation see the concrete type. The CDC payload must therefore include the discriminator column; an unmapped discriminator value fails with `IllegalArgumentException`. Earlier versions could fail to deserialize such rows — verify CDC after upgrading or when adding a subtype. Cache wiring lives in `jimmer-caching`.

## Verify

Compile entities and a polymorphic DTO; fetch through the root and assert runtime branch/loaded fields. Exercise both type-match scopes and a rejected wrong-subtype write. Review nullable columns or joined PK/FKs in the migration and run database validation once against it. With CDC, replay one subtype row through the binlog API and assert the event's entity type.

Sources: [mapping](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/inheritance/mapping), [queries](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/inheritance/query), [mutations](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/inheritance/mutation), [DTOs](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/inheritance/dto), [`TypeMatchMode`](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/TypeMatchMode.java), [single-table binlog](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/event/binlog/impl/BinLogParser.java).
