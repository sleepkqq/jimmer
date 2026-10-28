# Jimmer Query

Use for typed Jimmer queries, filters, pagination, aggregates, tuple projections, base tables/window functions, and bulk update/delete.

## Workflow

1. Clarify data shape, filters, sorting, pagination, and return type.
2. Run project scan when package/style unknown:

```bash
scripts/scan-project.sh /path/to/project
```

3. Choose approach from table below.
4. Write only query methods called by current task.
5. Compile:

```bash
scripts/compile.sh /path/to/project
```

## Approach Table

| Need | Approach |
|---|---|
| Scalar/FK filters | `createQuery` + `TABLE` |
| Root has a matching to-many child | EXISTS/implicit subquery; `TABLE_EX` for an intentional collection join |
| Return entity/view | `.select(t.fetch(ViewClass.class))` or `.select(t)` |
| Aggregate/subquery/expression not entity property | `@TypedTuple` class or `Tuple2..TupleN` |
| Window functions / reuse of query result columns | `createBaseQuery` + `asBaseTable` |
| Nonstandard join condition | `WeakJoin` (`t.asTableEx().weakJoin(...)`) |
| Bulk update/delete | `createUpdate` / `createDelete` |
| Insert/upsert rows selected by a query | `createInsert` / `createUpsert` — see `jimmer-dml` |
| Page without exact count (scroll/typeahead) | `fetchSlice(limit, offset)` → `isTail` |
| Existence check | `exists()` — never `count() > 0` |
| Export/ETL over a large result (must not hold all rows) | `.stream()` (JDBC cursor) in try-with-resources/`use`; tune `jdbcFetchSize(n)`/`jdbcQueryTimeout(s)` per query |
| Bulk update whose new values are needed | `returning(...)` on the update (see jimmer-performance) |

Streaming constraint: a fetcher may use join-loaded associations, but an association needing a secondary select after root rows is rejected for `stream()` — such shapes need `execute()`.

## Java DSL Rules

- Method order: `.where()` -> `.groupBy()` -> `.orderBy()` -> `.select()`. `select()` is last.
- `.as("name")` does not exist inside Java DSL `select()`.
- Extract a table variable when a table constant is used more than once.
- Dynamic predicates: `eqIf`, `likeIf`, `geIf`, ... skip null **and empty string** operands; `whereIf(cond, ...)` and `orderByIf(cond, ...)` for conditional clauses.
- Use `LikeMode.ANYWHERE`, `START`, `END`, `EXACT` intentionally.

```java
var t = DOMAIN_OBJECT_TABLE;
return sql().createQuery(t)
    .where(t.relatedObject().name().eqIf(relatedObjectName))
    .where(t.status().eqIf(status))
    .orderBy(t.createdAt().desc())
    .select(t.fetch(DomainObjectListView.class))
    .fetchPage(page, size);
```

Intentional collection join (use EXISTS instead when only testing membership; a join can duplicate roots):

```java
var t = DOMAIN_OBJECT_TABLE_EX;
return sql().createQuery(t)
    .where(t.labelObjects().id().in(labelObjectIds))
    .select(t.fetch(DomainObjectListView.class))
    .distinct()
    .fetchPage(page, size);
```

Subquery: `sql().createSubQuery(otherTable)` inside predicates (`exists`, `in`, scalar compare).

## Base Tables (window functions, column reuse)

`createBaseQuery` builds a reusable inner query. Positional `addSelect` exposes `get_1()`, `get_2()`, ...; direct `.select(table).asBaseTable()` preserves the entity table type with direct property access. A typed-tuple mapper gives named columns. Fetchers still work on entity columns.

```java
var store = DOMAIN_OBJECT_TABLE;
BaseTable2<DomainObjectTable, NumericExpression<Integer>> baseTable = sql()
    .createBaseQuery(store)
    .addSelect(store)
    .addSelect(Expression.numeric().sql(
        Integer.class, "dense_rank() over(order by %e desc)", someExpression))
    .asBaseTable();

return sql().createQuery(baseTable)
    .where(baseTable.get_2().le(rankLimit))
    .select(baseTable.get_1().fetch(DomainObjectListView.class))
    .fetchPage(page, size);
```

Base queries support unions and pagination over the union.

Use `asCteBaseTable()` when a CTE is actually useful; check recursive/set-operation API and dialect support. Exported columns propagate from consumers. Do not confuse a base-table symbol with a loaded entity or a writable mutation target.

## Typed Tuple Rule

Use existing `Tuple2..TupleN` for small local projections; use `@TypedTuple` when a named reusable result/base-table contract improves the caller. Do not add a mapper for one scalar.

## Kotlin Note

In Kotlin DSL, `table` supports collection joins without `TableEx`; null-safe operators spelled ``eq?``, ``like?``, etc.; view type passed as `KClass<V>`. See `jimmer-kotlin`.

## Streaming and pagination checks

Close streams with Java try-with-resources or Kotlin `use`; do not return an open JDBC stream after the service transaction closes. Driver fetch size is a hint, not a bound on total memory for arbitrary shapes. Test early close. Use a unique order tie-breaker for page/slice; handle empty filters deliberately (skipping a null predicate differs from `IS NULL`).

Sources: [query usage/streaming](https://babyfish-ct.github.io/jimmer-doc/docs/query/usage), [base queries](https://babyfish-ct.github.io/jimmer-doc/docs/query/base-query), [implicit subqueries](https://babyfish-ct.github.io/jimmer-doc/docs/query/implicit-subquery).
