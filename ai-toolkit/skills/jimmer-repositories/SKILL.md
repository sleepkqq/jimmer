---
name: jimmer-repositories
description: Chooses between Jimmer repository built-ins, derived query methods and typed DSL queries in JRepository or KRepository (Spring or the Quarkus fork), keeps transaction ownership in services, and shapes save-result return contracts. Use when the user adds a repository method, asks where a query belongs, or returns results from saveCommand. Not for writing the query body itself (jimmer-query).
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Repositories

Inspect the existing architecture and actual repository implementation first. Spring repositories and the unified Quarkus module have related APIs but different generation/integration mechanisms. Direct SQL-client usage is also valid; do not create a repository layer solely for uniformity.

## Architecture

- REST/resource layer delegates to service.
- Service layer owns business logic and save mode choices.
- Repository layer owns `sql()` queries and data access.
- Match existing SQL-client/repository placement; keep the service's transaction and business-decision boundary explicit.
- Spring multiple datasources: each `JimmerTransactionManager` owns its client and the injected `JSqlClient`/`KSqlClient` bean is `TransactionalSqlClients.java()`/`kotlin()`. Since 0.12.4 the Kotlin client routes to the `KSqlClient` given to the active manager (nested transactions restore the outer one); outside a Jimmer-managed transaction the call fails with `IllegalStateException`, so repository use must stay inside the service transaction.

## Repository Size Rule

Every repository starts empty:

```java
public interface DomainObjectRepository extends JRepository<DomainObject, UUID> {
}
```

Add custom method only when code in current task directly calls it and built-ins cannot do it.

## Reuse Ladder

For each data need, take the FIRST rung that expresses it — and stop there:

1. **Built-in** `JRepository`/`KRepository` method (`findNullable`, `viewer(...)`, `findAll`, `save*`, `deleteById`, ...).
2. **Derived query method** — a supported signature with no body, for example `findByName(...)`. Quarkus discovers interfaces and generates implementations at build time; Spring uses its own integration. Verify projection/return-type support in the installed implementation.
3. **Custom DSL method** — only when the query needs predicates/subqueries/tuples a name cannot express.

Choose the approach for the whole result, and inspect the resulting SQL:

- Never reimplement rung 1–2 as rung 3 (duplicate of an existing/derivable method).
- Avoid an accidental chain of independent lookups for one result. Use an appropriate View/Fetcher or DSL query/subquery; batched graph loading can intentionally use several statements. One repository call does not guarantee one SQL statement or snapshot atomicity.

## Built-ins Not To Reimplement

```java
repository.findNullable(id);
repository.findNullable(id, DOMAIN_OBJECT_FETCHER.name().relatedObject());
repository.viewer(DomainObjectDetailView.class).findNullable(id);
repository.viewer(DomainObjectListView.class).findAll(page, size);
repository.save(entity, SaveMode.INSERT_ONLY);
repository.saveCommand(input).setMode(SaveMode.INSERT_ONLY).execute(DomainObjectDetailView.class).getModifiedView();
repository.deleteById(id);
```

## Custom Query Method

```java
default <V extends View<DomainObject>> Page<V> search(
    @Nullable String nameQuery,
    @Nullable UUID relatedObjectId,
    int page,
    int size,
    Class<V> viewType
) {
    var t = DOMAIN_OBJECT_TABLE;
    return sql().createQuery(t)
        .where(t.name().likeIf(nameQuery, LikeMode.ANYWHERE))
        .where(t.relatedObject().id().eqIf(relatedObjectId))
        .orderBy(t.createdAt().desc())
        .select(t.fetch(viewType))
        .fetchPage(page, size);
}
```

## Service Save Pattern

```java
return repository.saveCommand(input)
    .setMode(SaveMode.INSERT_ONLY)
    .execute(DomainObjectDetailView.class)
    .getModifiedView();
```

For accepted writes use the requested modified entity/View instead of an unconditional re-query. Check `isAccepted` for conditional saves: a skipped `INSERT_IF_ABSENT` root does not return the conflicting database row. If that row is required without update, an explicit key lookup and concurrency policy are valid (`jimmer-save-modes`).

Verify the generated method, emitted SQL and transaction scope. Sources: [Spring repository API](https://babyfish-ct.github.io/jimmer-doc/docs/spring/repository/), [multiple datasources](https://babyfish-ct.github.io/jimmer-doc/docs/configuration/multi-datasources), [Quarkus repositories](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/repository).
