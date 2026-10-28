---
name: jimmer-inheritance
description: Map Jimmer polymorphic entity hierarchies with SINGLE_TABLE or JOINED, discriminator and subtype fetch/DTO branches; handle type matching and explicit entity type transitions.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Jimmer Entity Inheritance

Use entity inheritance for a polymorphic identity/query contract. `@MappedSuperclass` only shares properties; it does not create a queryable entity hierarchy. Check installed annotation, DTO compiler and mutation APIs before changing a hierarchy.

## Map the hierarchy

```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public interface Asset {
    @Id long id();
    @Discriminator String kind();
    String name();
}

@Entity
@DiscriminatorValue("BOOK")
public interface BookAsset extends Asset {
    String isbn();
}

@Entity
@DiscriminatorValue("TOOL")
public interface ToolAsset extends Asset {
    int inspectionInterval();
}
```

- Declare `@Inheritance` once at the root and one inherited ID. Every concrete branch is an `@Entity`.
- Both `SINGLE_TABLE` and `JOINED` require a physical `@Discriminator` property (String or enum). Prefer explicit stable `@DiscriminatorValue` values.
- The discriminator is read-only: saves derive it from the runtime entity type. Do not implement type transitions by assigning it.
- A type with derived types and no discriminator value is abstract in Jimmer. A leaf's default value is its simple type name; an explicit value can make a root/intermediate type instantiable.
- `SINGLE_TABLE`: subtype-only **database columns must be nullable**, even when the property's subtype contract is non-null.
- `JOINED`: root holds ID/discriminator/common columns; each derived table's ID is its PK and an FK to its direct entity parent. There is no table-per-class strategy.
- Joined physical deletion defaults to explicit child-table deletes. `joinedTableDissociateAction=LAX` is appropriate only when the schema really supplies all required cascades.

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

Type changes are disabled by default. Enable `setTypeChangeAllowed(true)` only for an intended transition and **exact** save matching; it cannot be combined with polymorphic matching. Do not globally enable it to fix a discriminator mismatch. Query-derived DML supports single-table subtypes, not joined-inheritance targets.

## Verify

Compile entities and a polymorphic DTO; fetch through the root and assert runtime branch/loaded fields. Exercise both type-match scopes and a rejected wrong-subtype write. Review nullable columns or joined PK/FKs in the migration.

Sources: [mapping](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/inheritance/mapping), [queries](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/inheritance/query), [mutations](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/inheritance/mutation), [DTOs](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/inheritance/dto).
