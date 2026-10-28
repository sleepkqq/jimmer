# Query reference

## Java and Kotlin boundaries

Java uses the APT-generated `Tables.BOOK_TABLE` / `TableExes.BOOK_TABLE_EX` constants (`BookTable.$` / `BookTableEx.$` are the equivalent `$` style). `TableEx` permits intentional collection joins. Kotlin uses `table` and generated extension properties, without `TableEx` at call sites. Use `Class<V>` vs `KClass<V>` for Views. Put predicates/group/order before the final `select`. Java select expressions have no arbitrary `.as("alias")` API.

For to-many membership, an implicit subquery avoids duplicate parent rows:

```java
var s = Tables.BOOK_STORE_TABLE;
return sqlClient.createQuery(s)
    .where(s.books(b -> b.name().eq(name)))
    .orderBy(s.id())
    .select(s)
    .execute();
```

```kotlin
return sqlClient.createQuery(BookStore::class) {
    where(table.books { name eq nameQuery })
    orderBy(table.id)
    select(table)
}.execute()
```

If the actual result is one row per child, use a join instead. If distinct roots are needed after a collection join, check `.distinct()` and the count query rather than assuming paging removes duplicates.

## Aggregation and reusable columns

Use `groupBy`/`having` and `Tuple2..TupleN` for projections. `@TypedTuple` on a Java/Kotlin class is also supported; APT/KSP generate a `<Name>Mapper` with exactly one fluent method per declared component (no separate root entry point), and the chain starts with the static method named after the first declared component. Annotate the class, then put the mapper chain into `select(...)`; grouping, ordering and the client stay on the query:

```java
var b = Tables.BOOK_TABLE;
return sqlClient.createQuery(b)
    .groupBy(b.storeId())
    .orderBy(b.storeId().asc())
    .select(
        StoreAggregateMapper
            .storeId(b.storeId())
            .bookCount(Expression.rowCount())
            .minPrice(b.price().min())
    )
    .execute();
```

Exporting selected columns from a base query is different from a loaded entity or mutation target.

Java base query:

```java
var b = Tables.BOOK_TABLE;
var source = sqlClient.createBaseQuery(b)
    .addSelect(b.id())
    .addSelect(b.name())
    .asBaseTable();
return sqlClient.createQuery(source)
    .where(source.get_2().like("Guide"))
    .select(source.get_1(), source.get_2())
    .execute();
```

Kotlin:

```kotlin
val source = baseTableSymbol {
    sqlClient.createBaseQuery(Book::class) {
        selections.add(table.id).add(table.name)
    }
}
return sqlClient.createQuery(source) {
    where(table._2 like "Guide")
    select(table._1, table._2)
}.execute()
```

Kotlin imports `org.babyfish.jimmer.sql.kt.ast.query.baseTableSymbol` and `org.babyfish.jimmer.sql.kt.ast.table.*`. Java uses `asCteBaseTable()` for a CTE; check the Kotlin symbol overload and recursive/union shape against the project's tests, and compile base-table syntax before relying on it. `WeakJoin` is available for a nonstandard relationship; parameterize expressions instead of interpolating input into SQL.

## Bounded traversal

Scan large results with Java `query.forEach(128, consumer)` or Kotlin `query.forEach(batchSize = 128) { row -> ... }`; there is **no JDBC `stream()` query API**. `forEach` processes results in fetch batches and returns only after consumption — keep the connection/transaction alive for the call. Batch size does not bound a single root's enormous collection, and fetcher batching is distinct from JDBC driver fetch size. A bounded, ID-keyset page loop is another option when the operation needs resumability; do not call `.execute().stream()` and claim bounded database consumption.

Count actual statements and inspect plans for correlated subqueries/deep offsets. A language-level reused expression need not be evaluated once by the database.

Sources: [Java root query](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/query/TypedRootQuery.java), [Kotlin base-query tests](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql-kotlin/src/test/kotlin/org/babyfish/jimmer/sql/kt/query/base/BaseQueryTest.kt), [TypedTuple](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-core/src/main/java/org/babyfish/jimmer/sql/TypedTuple.java), [tuple tests](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/tuple/TypedTupleTest.java).
