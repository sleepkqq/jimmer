---
name: jimmer-entity
description: Define Jimmer entities in Java or Kotlin with owning/inverse associations, natural keys, ID generation, mapped superclasses and dissociation policies.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Entity mapping

Target the coordinates the project resolves for the runtime and APT/KSP, and verify both dependency graphs. Do not mix examples or coordinates from a different Jimmer distribution.

## Workflow

1. Read nearby entities, ID strategy, package naming and actual schema. Use `scripts/scan-project.sh /path/to/project` when discovery is needed (script paths are relative to this skill).
2. Model immutable interfaces, then compile with `scripts/compile.sh /path/to/project` so APT/KSP checks the mapping.
3. Test a representative save when keys, ownership or dissociation change. Compilation does not prove the DB constraint exists.

## Equivalent Java / Kotlin entities

Use separate source files in **both languages**; KSP rejects multiple immutable/entity/mapped-superclass/embeddable declarations in one Kotlin file. SQL annotations are from `org.babyfish.jimmer.sql`. Java nullability uses `org.jetbrains.annotations.Nullable`.

```java
@Entity
@KeyUniqueConstraint
public interface Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key String name();
    @Version int version();

    @Nullable
    @ManyToOne
    @OnDissociate(DissociateAction.SET_NULL)
    BookStore store();
}

@Entity
public interface BookStore {
    @Id long id();
    String name();
    @OneToMany(mappedBy = "store")
    List<Book> books();
}
```

```kotlin
@Entity
@KeyUniqueConstraint
interface Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Key val name: String
    @Version val version: Int

    @ManyToOne
    @OnDissociate(DissociateAction.SET_NULL)
    val store: BookStore?
}

@Entity
interface BookStore {
    @Id val id: Long
    val name: String
    @OneToMany(mappedBy = "store")
    val books: List<Book>
}
```

The database needs `unique(name)`, a nullable `book.store_id` FK and the matching identity/version columns. Identity is an example, not a request to change an existing sequence/UUID policy.

## Mapping rules

- Properties are non-null unless Java `@Nullable` / Kotlin `T?`. Non-null does not mean loaded.
- Default naming maps camelCase to snake_case. Use `@Table`, `@Column`, `@JoinColumn` only to override the real schema.
- `@Key` defines save matching, not DDL. Independent natural keys use `@Key(group = "byName")`. `@KeyUniqueConstraint` promises corresponding database uniqueness; check nullable keys and other unique constraints before enabling native upsert.
- `@OnDissociate` belongs on the **owning reference**, never on inverse `books`. Actions include `NONE`, `LAX`, `CHECK`, `SET_NULL`, `DELETE`. `SET_NULL` requires nullable FK; `DELETE` removes the dissociated child, not its parent. DB `ON DELETE` is a separate contract.
- Add reverse navigation only when required. `@ManyToMany`/`@JoinTable` describe middle tables; `@ManyToManyView` is a read-only shortcut through an association entity.
- Use `@IdView` for FK IDs, `@Default` for an ORM-supplied default and `@Version` for optimistic locking. An unloaded property differs from explicit null or an empty collection.
- Reuse `@MappedSuperclass` for real shared properties; entity-to-entity polymorphic inheritance is not supported (`jimmer-inheritance`), and `@MapsId`/`@DatabaseDefault` mappings are not available.
- Create immutable values through the generated facades: Java `Immutables.createBook(d -> ...)` (APT generates `Immutables`/`Tables`/`TableExes`/`Fetchers` per common package; `BookDraft.$.produce(...)` is the same call in `$` style) and Kotlin `Book { ... }` (KSP; `new(Book::class).by { ... }` is the equivalent older spelling). All of them return the entity (`Book`), never the mutable draft.
- Repositories are optional. Reuse existing Spring `JRepository` / `KRepository` or direct SQL clients (`jimmer-repositories`).

For DTOs, save semantics and computed properties load `jimmer-dto`, `jimmer-save-modes`, `jimmer-advanced-mappings` respectively.

Source: [official mapping annotations](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-core/src/main/java/org/babyfish/jimmer/sql).
