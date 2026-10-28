---
name: jimmer-query
description: Write Jimmer Java/Kotlin typed queries with dynamic predicates, stable pagination, collection membership, aggregates, typed tuples, base tables/CTEs and bounded forEach traversal.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Typed queries

1. Establish shape, predicates, cardinality, stable ordering and whether the caller needs an exact total. Reuse an existing repository operation if it expresses the whole result.
2. Choose an explicit View or Fetcher. A to-many join can multiply root rows; for membership prefer `exists` over joining/filtering every child.
3. Use `fetchPage(pageIndex, pageSize)` for a total, `fetchSlice(limit, offset)` for has-more (`isTail`), `exists()` for a boolean. Include an ID tie-breaker. `Page` is `org.babyfish.jimmer.Page` (jimmer-core), not an `sql.ast` type.
4. Compile via `scripts/compile.sh /path/to/project` and inspect SQL/counts for multiple matching children. Scripts are relative to this skill.

## Paired search

Java (generated `BookTable`, `BookView`):

```java
var b = Tables.BOOK_TABLE;
return sqlClient.createQuery(b)
    .where(b.name().likeIf(name))
    .orderBy(b.name().asc(), b.id().asc())
    .select(b.fetch(BookView.class))
    .fetchPage(page, size);
```

Kotlin (generated property extensions plus `org.babyfish.jimmer.sql.kt.ast.expression.*`):

```kotlin
return sqlClient.createQuery(Book::class) {
    where(table.name `like?` name)
    orderBy(table.name.asc(), table.id.asc())
    select(table.fetch(BookView::class))
}.fetchPage(page, size)
```

Java `eqIf`/`likeIf`, Kotlin backticked `eq?`/`like?` conditionally skip absent operands. Skipping a predicate is not `IS NULL`; use `isNull()` intentionally. Validate required tenant/security context before building optional predicates. Empty-list filtering also needs an explicit product contract.

Read [GUIDE.md](GUIDE.md) for joins, base tables and traversal. Use `jimmer-filters` for global visibility, `jimmer-dml` for bulk writes, `jimmer-fetchers` for graph shape.
