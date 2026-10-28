---
name: jimmer-dml
description: Writes Jimmer set-based DML - bulk insert or upsert from a typed query source with createInsert and createUpsert, ON CONFLICT keys, update expressions, RETURNING rows and dialect fallbacks. Use when the user wants a single-statement mass insert, upsert or insert-select. Not for saving an object graph with save or saveCommand (jimmer-save-modes) or ordinary update/delete queries (jimmer-query).
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Jimmer Query-derived DML

Choose this for query-to-table copying/imports/aggregates. For an existing object or graph use `jimmer-save-modes`. Verify the installed factories, dialect and transaction boundary. Use `BINLOG_ONLY`; transaction-trigger modes are deprecated.

## Choose by observable behavior

| Required behavior | Operation | Condition that must accompany the choice |
|---|---|---|
| Every source row must insert | `createInsert` | A conflicting constraint is an error |
| Existing target must remain untouched | `createInsert` + `onConflictDoNothing` | Skipped rows are absent from returning |
| Existing target may change | `createUpsert` | Declare the conflict key and each branch's assignments |
| Only eligible existing rows may change | upsert + `updateWhere` | Rejection is normal; a missing row can still insert |
| Source must never enter application memory | verified native insert-select plan | The API alone cannot promise this; unsupported shapes/dialects can materialize |

Treat SQL shape, write effects and returned rows as separate contracts. In a recommendation, carry the relevant condition alongside the code rather than leaving it as an unrelated footnote. If the dialect is unknown, leave execution-plan claims conditional.

## Typed source → separate physical target

```java
WarehouseTable warehouse = Tables.WAREHOUSE_TABLE;
WarehouseArchiveTable archive = Tables.WAREHOUSE_ARCHIVE_TABLE;

WarehouseTable source = sqlClient.createBaseQuery(warehouse)
    .where(warehouse.active().eq(true))
    .select(warehouse)
    .asBaseTable();

List<Long> insertedIds = sqlClient.createInsert(archive, source)
    .set(archive.id(), source.id())
    .set(archive.name(), source.name())
    .onConflictDoNothing(archive.id())
    .returning(archive.id())
    .execute();
```

This is the insert-if-absent branch: conflicting rows are untouched and not returned. On a supporting dialect it runs natively; inspect the actual plan before relying on bounded application memory.

Assumes compatible Long ID/name columns on synthetic source/target entities. Direct base-query `select(table)` preserves the table type: access `source.id()`, not `get_1().id()`. Only needed columns propagate into the source SQL. The exported source is not a mutation target; keep a separate ordinary target table.

Mappings may navigate source associations (`selectedStores.asTableEx().books().name()`, Kotlin `sourceTable.asTableEx().books.name`): the join is added to the consuming statement, the source exports the needed FK/ID columns, and a to-many join yields one source row per match — duplicates then hit the conflict/uniqueness rules below.

For expression/aggregate sources, use a positional base table or `@TypedTuple` mapper. Joins/filtering/grouping belong in the source query. A raw table singleton must first be exported by a base query to become a source.

## Insert and conflict handling

- `createInsert(...).set(targetProp, sourceExpression)` maps physical columns. Insert expressions cannot read an existing target row.
- Insert is strict by default. `onConflictDoNothing(keyProps...)` skips a conflict on **one complete** ID or metadata-declared key group, whose columns must have insert assignments.
- The no-argument overload infers ID first, then eligible metadata keys. It does not mean “ignore every unique constraint”; an explicitly empty array is invalid.
- Omitted columns need a generated/framework/database default or nullable schema; mandatory missing data still fails.

## Upsert branch controls

| Call | Insert | Accepted conflicting update |
|---|---|---|
| `key(prop, source)` | write matching key | preserve key |
| `insert(prop, source)` | write value | preserve value |
| `update(prop, expression)` | default/initialization | compute value |
| `merge(prop, source)` | write source | write source |
| `merge(prop, insertExpr, updateExpr)` | insert expression | update expression |

For conditional upsert, a scalar source makes the incoming columns explicit. Using the same ordinary warehouse/archive tables, synchronize names while skipping unchanged conflicts:

```java
var changes = sqlClient.createBaseQuery(warehouse)
    .where(warehouse.active().eq(true))
    .addSelect(warehouse.id())
    .addSelect(warehouse.name())
    .asBaseTable();

List<Long> changedIds = sqlClient.createUpsert(archive, changes)
    .key(archive.id(), changes.get_1())
    .merge(archive.name(), changes.get_2())
    .updateWhere(archive.name().ne(changes.get_2()))
    .returning(archive.id())
    .execute();
```

Kotlin (`table` = target, `sourceTable` = source; wrap a positional base query in `baseTableSymbol { }`):

```kotlin
val changes = baseTableSymbol {
    sqlClient.createBaseQuery(Warehouse::class) {
        where(table.active eq true)
        selections.add(table.id).add(table.name)
    }
}

val changedIds: List<Long> = sqlClient
    .createUpsertReturning(WarehouseArchive::class, changes) {
        key(table.id, sourceTable._1)
        merge(table.name, sourceTable._2)
        updateWhere(table.name ne sourceTable._2)
        returning(table.id)
    }
    .execute()
```

An entity source in Kotlin is `createBaseQuery(Warehouse::class) { where(...); select(table) }.asBaseTable()` with `sourceTable.id`; positional sources use `_1`, `_2`.

Here a missing ID inserts, a different name updates, and an equal name is rejected; only accepted rows appear in `changedIds`, with no ordering promise. This non-null-name example is not a general null-safe comparison recipe. Positional `addSelect` uses `get_1/get_2`; direct `select(table)` uses entity properties. Do not interchange their accessors.

`createUpsert(target, source)` needs one complete conflict key; source rows must be unique by it. Assign each physical target column once. `updateWhere(predicate)` restricts only conflicting updates, not insertion. Update expressions can read target and source. Versions use assignment semantics: there is no implicit optimistic check/increment.

An upsert with no update values may still execute a technical self-assignment; it is not equivalent to insert-if-absent and can fire database update triggers.

## Returning, dialect and transaction checks

- Java: `.returning(target.id(), ...)`; Kotlin: `createInsertReturning`/`createUpsertReturning` with `returning(...)` in the DSL (`table` is target, `sourceTable` is source).
- Returning supports physical target columns/tuples, not fetcher graphs or joined/computed selections. It includes inserted and accepted updated rows, excludes skipped/rejected rows, and **does not guarantee order** or an inserted-versus-updated marker.
- Entity targets must map to one physical table. Single-table subtypes work; joined-inheritance targets do not. A conflicting different subtype is rejected, not converted.
- A capable dialect executes native insert-select/upsert. Otherwise Jimmer materializes the source and runs per-row save commands (`INSERT_ONLY`, `INSERT_IF_ABSENT` or `UPSERT` with `VersionMode.ASSIGNMENT`): do not promise one statement, constant memory, hidden locks or atomic select-then-write behavior. Save-pipeline fixes therefore apply to the fallback too, e.g. 0.12.4 inserts rows whose column shape is only the ID instead of skipping them (#1246).
- PostgreSQL aliases the upsert target (`insert into T as tb_N_ ... on conflict (...) do update set ... = excluded.col [where ...]`) both natively and in the save upsert. Write update expressions and `updateWhere` with typed expressions (or `%e` placeholders in `sql(...)`); a bare column name in a native fragment is ambiguous with `excluded` (`column reference ... is ambiguous`). The fork briefly dropped this alias and restored it before releasing 1.1.11, so no published fork version lacks it.
- Exercise conditional expressions on the actual fallback too. A materialized-plan `Cannot resolve the root table ... withBaseTableOwner` failure can depend on an exported entity-table expression; use an explicit scalar/typed-tuple projection as above and verify it rather than dropping the condition.
- Middle tables support insert/insert-if-absent by source-ID/target-ID pair, not association upsert.

Verify database state as well as returned values: new key, accepted conflict, rejected conflict and duplicate source keys. Check the emitted SQL on the application's dialect, transaction isolation for a materialized fallback, and CDC coverage for cache invalidation. A successful compile verifies API shape, not the chosen SQL plan.

Sources: [insert/upsert from select](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/insert-from-select), [renderers](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/dialect/InsertFromSelectRenderers.java), [Kotlin DSL](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/ast/mutation/KMutableUpsert.kt), [fork alias restore](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/dialect/PostgresDialect.java), [base queries](https://babyfish-ct.github.io/jimmer-doc/docs/query/base-query).
