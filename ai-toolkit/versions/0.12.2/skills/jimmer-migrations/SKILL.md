---
name: jimmer-migrations
description: Align Jimmer entities with Liquibase/Flyway migrations or reviewed DDL-compiler output; verify owning FKs, natural-key upserts, logical deletion, inheritance and database defaults.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Jimmer Migrations

Read the actual database dialect/schema, migration history and entity annotations. ORM metadata is not proof that a database constraint exists. Preserve the established migration runner; use DDL generation as a reviewed draft when appropriate.

## Workflow

1. Detect migration setup:

```bash
scripts/next-migration.sh /path/to/project
```

2. Read entity annotations and existing migrations.
3. Follow existing migration format and naming exactly.
4. Add Liquibase file to master changelog when project uses one.
5. Compile the affected mapping, then apply the migration to a disposable database and run schema validation/one representative write. Compile alone does not verify SQL:

```bash
scripts/compile.sh /path/to/project
```

## DDL Compiler — generated schema from entities

Jimmer provides `jimmer-ddl-compiler` as an additional KSP/APT processor. Verify it exists in the installed distribution and align its publisher/version with the runtime and other processors. It produces SQL; it is not an automatic production migration runner.

- Enable: add the processor + `jimmerDdl.*` args (`enabled`, `databaseType` e.g. `postgresql`, `outputFormat` `flyway`|`plain`, `outputDir`, `version`, `description`).
- Incremental: a snapshot file (`.jimmer-ddl/entity-table-snapshot.properties`) records table hashes — later builds emit diff SQL and detect `@Table(name=...)` renames. With JDBC settings and `compareDatabase`, it diffs against the live schema instead.
- Scope knobs: `includePackages`/`excludePackages`, `includeForeignKeys`/`includeIndexes`/`includeSequences`/`includeManyToManyTables`; `profiles` generate for several dialects in one build.
- In projects with an established hand-written migration flow, the generator is a draft source, not a replacement — the reviewed migration file stays the source of truth.

## Type Mapping Starting Points

Resolve precision, length, timezone, enum strategy and scalar-provider/UUID representation from the actual model and driver. These examples are not mandatory defaults:

| Java/Kotlin | PostgreSQL | MySQL |
|---|---|---|
| `UUID` | `uuid` | `char(36)` |
| `String` | `varchar(255)` | `varchar(255)` |
| `Int` / `Integer` | `integer` | `int` |
| `Long` | `bigint` | `bigint` |
| `Boolean` | `boolean` | `tinyint(1)` |
| `Instant` | `timestamptz` | `timestamp` |
| `LocalDateTime` | `timestamp` | `timestamp` |
| `LocalDate` | `date` | `date` |
| `BigDecimal` | `numeric(19,4)` | `decimal(19,4)` |
| Enum | `varchar(50)` | `varchar(50)` |
| `@Serialized` | `jsonb` | `json` |

## Annotation Alignment

| Jimmer annotation | DB constraint |
|---|---|
| `@OnDissociate(DELETE)` | ORM deletes dissociated targets; does **not** require/infer `ON DELETE CASCADE` |
| `@OnDissociate(SET_NULL)` | owning FK must be nullable; database `ON DELETE SET NULL` is a separate policy |
| `@Key` | unique constraint on key columns (one constraint per `group`) |
| `@KeyUniqueConstraint` | matching DB unique constraint required; logical-delete and nullable-key semantics must match the actual conflict target; `isNullNotDistinct = true` requires corresponding DB null uniqueness |
| `@Version` | integer not null default 0 |
| `@LogicalDeleted` | flag column: boolean not null default false / nullable timestamp / etc. |
| `@OneToOne @JoinColumn` | FK plus unique when truly one-to-one |
| `@MapsId` | PK column doubles as FK — no separate FK column |
| `@JoinColumn(foreignKeyType = FAKE)` | no FK constraint on purpose — do not add one |
| `@DatabaseDefault` | real database DEFAULT/generated behavior; annotation alone does not install it |
| `@Inheritance(SINGLE_TABLE)` | root discriminator; subtype-only DB columns nullable |
| `@Inheritance(JOINED)` | derived table PK is also FK to direct entity supertype |

`@OnDissociate` belongs on the owning to-one property. Removing a child from a saved collection does not delete its parent, so parent-FK `ON DELETE CASCADE` is not the mechanism for that operation. Verify any DB cascade separately for physical parent deletion and CDC events.

## Index Rules

- Index every FK column unless covered by stronger index.
- `@ManyToOne` FK -> index.
- inverse `@OneToMany` FK -> index on child table.
- many-to-many join table -> indexes for both directions.
- unique constraint for `@Key` usually replaces extra same-column index.
- For soft-delete uniqueness, choose a multi-version tombstone key or an active-row partial unique index supported by the dialect/conflict target. Do not universally add `(key, boolean_deleted)` and expect unlimited deleted duplicates. Verify native-upsert SQL instead of assuming every partial index enables/disables it.

## Safety

- Do not drop/rename columns without explicit user confirmation.
- Do not generate irreversible data migrations from guesses.
- The DB key/nullability/ownership contract must match the model; ORM dissociation actions and DB referential actions remain distinct.

Sources: [DDL compiler](https://babyfish-ct.github.io/jimmer-doc/docs/configuration/ddl-compiler), [dissociation](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/on-dissociate), [logical deletion](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/logical-deleted/entity).
