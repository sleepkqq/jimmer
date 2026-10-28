---
name: jimmer-migrations
description: Writes Liquibase or Flyway migrations that match Jimmer entities, or reviews DDL-compiler output - owning foreign keys, natural-key unique constraints for upserts, logical-deletion columns, inheritance tables, defaults. Use when an entity change needs a changelog or migration, or when entity and schema disagree. Not for entity modeling decisions themselves (jimmer-entity).
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
5. Compile the affected mapping, then apply the migration to a disposable database and run database validation (below) plus one representative write. Compile alone does not verify SQL:

```bash
scripts/compile.sh /path/to/project
```

## DDL Compiler — generated schema from entities

Jimmer provides `jimmer-ddl-compiler` as an additional KSP/APT processor. Verify it exists in the installed distribution and align its publisher/version with the runtime and other processors. It produces SQL; it is not an automatic production migration runner.

- Enable: `ksp("org.babyfish.jimmer:jimmer-ddl-compiler:<version>")` (or `annotationProcessor`) + `jimmerDdl.*` args: `enabled` (default `true`), `databaseType` (default `auto`, from the JDBC URL; e.g. `postgresql`), `outputFormat` `flyway` (`V<version>__<description>.sql`, default) or `plain`, `outputDir` (default `build/generated/jimmer-ddl/main/resources/db/migration`), `version` (default `1001`), `description`.
- Live diff: `compareDatabase` defaults to `true` — with `jdbcUrl`/`jdbcUsername`/`jdbcPassword`/`jdbcSchema`/`jdbcDriver` (or `springResourcePath` + `springProfile`, default `local`) it reads the database and emits diff SQL; if the database cannot be read it falls back to offline DDL. Check which mode produced the file.
- Offline baseline: a **directory** of per-table lockfiles in `.jimmer-ddl/entity-table-snapshot/` (Git-tracked). The compiler stages the next baseline in `build/generated/jimmer-ddl/main/resources/.jimmer-ddl/entity-table-snapshot/`; after accepting the migration the build must mirror that whole directory back, deleting lockfiles of removed tables. The old single `entity-table-snapshot.properties` file (still shown on the documentation page) is not read; the module README at v0.12.4 is authoritative.
- Destructive changes: `allowDestructiveChanges` defaults to `false` — removed columns are not dropped (kept in the staged snapshot for a later run), and an inferred table rename creates the new table, keeps the old one and warns. When enabled, drops and renames are emitted; a column rename is always add-new + drop-old, i.e. data loss unless you hand-write a `RENAME`/copy step.
- Scope knobs: `includePackages`/`excludePackages`, `includeForeignKeys`/`includeIndexes`/`includeComments`/`includeSequences`/`includeManyToManyTables`, `nullabilityRepairOnly`; `profiles` + `jimmerDdl.profile.<name>.<option>` generate for several dialects in one build.
- Kotlin (0.12.4+): `val`s with implemented getters and no backing field — and `@Formula`/`@Transient` properties — never produce columns. A change that only adds such getters needs no migration.
- In projects with an established hand-written migration flow, the generator is a draft source, not a replacement — the reviewed migration file stays the source of truth.

## Database Validation

`jimmer.database-validation-mode` (Spring) / `quarkus.jimmer.database-validation-mode` (Quarkus fork), values `NONE` (default)/`WARNING`/`ERROR`, compares the model with the live schema at startup. Since 0.12.4 (#1319) it really checks **primary-key columns** and **real foreign keys** (earlier releases effectively skipped both). After upgrading, a schema that passed before may fail: add the missing PK/FK constraint, mark a deliberately constraint-free association `ForeignKeyType.FAKE`, or use `@DatabaseValidationIgnore` on the entity, `@Id` or association as a reviewed exception. Run it once against the migrated disposable database.

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
| `@LogicalDeleted` | flag column: boolean not null default false / nullable timestamp / etc. An enum/int flag with one deleted value joins key matching (0.12.4+), so a key-upsert constraint is `unique(key..., flag)` |
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

Sources: [DDL compiler](https://babyfish-ct.github.io/jimmer-doc/docs/configuration/ddl-compiler), [DDL compiler README](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-ddl-compiler/README.md), [database validation](https://babyfish-ct.github.io/jimmer-doc/docs/configuration/database-validation), [validator source](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/runtime/DatabaseValidators.java), [Quarkus config](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/cfg/JimmerRuntimeConfig.java), [dissociation](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/on-dissociate), [logical deletion](https://babyfish-ct.github.io/jimmer-doc/docs/mapping/advanced/logical-deleted/entity).
