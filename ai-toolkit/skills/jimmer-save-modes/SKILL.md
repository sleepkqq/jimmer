---
name: jimmer-save-modes
description: Chooses Jimmer graph-save behavior - SaveMode, AssociatedSaveMode (replace, merge, append, violently replace), key matching and masks, upsert by natural key, conditional and optimistic-lock updates, atomic assignments, save-result handling. Use when the user saves an entity graph and child rows are not removed or duplicated, needs upsert via save, or a version check on save. Not for set-based insert-select (jimmer-dml).
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Save Modes

Resolve the ORM version, dialect, trigger mode and transaction boundary first; verify newer APIs in the installed source before using them. For query-derived rows use `jimmer-dml`; for ordinary object/graph input use save commands. Use `BINLOG_ONLY`; `TRANSACTION_ONLY` and `BOTH` are deprecated.

## Establish the write contract

Before choosing options, determine **identity**, **allowed changes**, and **required result**. They are independent: returning an existing object's ID is not permission to update it, and a no-op is not necessarily an exception. Follow the caller's actual requirements instead of selecting a mode from the verb “save”.

| Caller needs | Choice and consequence |
|---|---|
| New object or error | `INSERT_ONLY`; preserve constraint-conflict behavior |
| Create if missing, never update | `INSERT_IF_ABSENT`; inspect acceptance, look up an existing row only if the caller requires it |
| Insert or modify existing | `UPSERT`; even a technical self-assignment is a real UPDATE with possible database-trigger effects |
| Modify existing only | `UPDATE_ONLY`, optionally with an eligibility predicate; handle missing/rejected result |
| Reject stale input as a business no-op | `setUpdateWhere`; distinguish rejection from optimistic-lock failure |
| Unconditionally insert objects without identity | `NON_IDEMPOTENT_UPSERT`; use only for intentional non-idempotent semantics |

Explain the selected branch's observable result and side effects with its code. Do not replace a strict no-update contract with UPSERT just to simplify result retrieval.

## Identity Rules

`@Key` defines natural/business identity for save matching.

- Key groups: `@Key(group = "a")` defines an independent matching key. `setKeyProps([group, ] props...)` configures the command's key group for that type throughout the graph; supply the actual properties, not a group name alone. A supplied ID still wins over a key.
- `matchByKey()` makes **root** types match by key even when an ID is supplied; the supplied ID is used only for an insert and never replaces an existing row's ID. Without `setKeyProps` the model must declare exactly one key group, otherwise execution fails. Since 0.12.4 an explicit `setKeyProps` for a `matchByKey` root replaces the annotated groups instead of competing with them. The key must be loaded; there is no fallback to the ID.
- Duplicate new objects with the same key in one save (for example the same new tag under several parents) share one generated ID and insert once since 0.12.4 (#1076); explicit IDs are untouched. Older versions allocated distinct IDs and could hit `NotUnique`.
- Fork `1.1.10+`: when several key groups are loaded, matching prefers a group without nullable properties, then the larger group; a nullable group is used only when no non-nullable one is loaded.
- Key-based saves without `@KeyUniqueConstraint` may select before writing. ID-based upserts do not require a natural-key annotation.
- `@KeyUniqueConstraint` plus a matching DB constraint enables native key upsert when the dialect, null/logical-delete semantics and other options allow it. It does not guarantee one statement for a graph. Read `QueryReason` for fallback reasons.
- `@KeyUniqueConstraint(isNullNotDistinct = true)` (Postgres `NULLS NOT DISTINCT`) keeps SQL upsert when key props may be null; `noMoreUniqueConstraints = true` helps MySQL id-less upserts.
- Composite keys can mix scalar and FK fields.
- Root `SaveMode` does not choose child behavior. Ordinary `UPSERT` needs an ID or matching key; a wild object without either raises `NeitherIdNorKey` rather than silently becoming a non-idempotent insert.

## AssociatedSaveMode (associated objects)

| Mode | Insert | Update | Delete missing |
|---|---|---|---|
| `REPLACE` | yes | yes | yes — dissociation applies, targets need id or key |
| `MERGE` | yes | yes | no |
| `APPEND` | yes | no | no |
| `APPEND_IF_ABSENT` | skip existing | no | no |
| `UPDATE` | no | yes | no |
| `VIOLENTLY_REPLACE` | fresh targets | no in-place update | reset old associations/owned targets; destructive replacement must be intentional |

## Save Command Options

```java
sql.saveCommand(domainObject)
    .setMode(SaveMode.UPSERT)
    .setAssociatedModeAll(AssociatedSaveMode.REPLACE)
    .setAssociatedMode(DomainObjectProps.CHILDREN, AssociatedSaveMode.MERGE)
    .execute();
```

| Option | Purpose |
|---|---|
| `setKeyProps([group, ] props...)` | override/choose matching key (does not enable `matchByKey`) |
| `matchByKey()` | root objects match by key even when an ID is supplied |
| `setUpsertMask(props... \| UpsertMask)` | restrict which columns an upsert may update (and insert, via `UpsertMask.of(...)`) |
| `forbidUpdate()` | empty update mask; `UPSERT` still accepts the existing row (not `INSERT_IF_ABSENT`) |
| `setIdOnlyAsReference(prop, bool)` / `...All` | treat id-only targets as references (default true); for an inverse FK association they only update the child FK, never insert (0.12.4, #1095); any other loaded non-id property makes the target an entity |
| `setKeyOnlyAsReference(prop)` / `...All` | treat key-only targets as reference (default false) |
| `setAutoIdOnlyTargetChecking(prop)` / `...All` | verify referenced target ids exist (clearer error than FK violation) |
| `setDissociateAction(prop, action)` | runtime override of `@OnDissociate` |
| `setTargetTransferMode(prop, AUTO\|ALLOWED\|NOT_ALLOWED)` | allow moving child to another parent |
| `setDeleteMode(PHYSICAL\|LOGICAL\|AUTO)` | delete flavor for dissociated children |
| `setPessimisticLock(...)` | `select ... for update` during save checks |
| `setOptimisticLock(...)` | user optimistic lock predicate/value |
| `setMaxCommandJoinCount(n)` | tune join depth of internal queries |
| `setDumbBatchAcceptable()` | allow JDBC batches without generated-key return |
| `setSaveReturningEnabled(bool)` | DML `RETURNING` for save results (default true) |
| `setSaveResultReadsAllProperties(bool)` | read requested values back instead of copying input (DB-rewritten values) |
| `set(prop) { ... }` / `setNullable` (Kotlin) | assignment expression for the update branch |
| `setConstraintViolationTranslatable(bool)` | toggle translation of constraint violations into typed `SaveException` |
| `addExceptionTranslator(t)` | command-scoped `ExceptionTranslator` (see jimmer-debug) |
| `setTransactionRequired(bool)` | require existing transaction |
| `setDissociationLogicalDeleteEnabled(bool)` | logical-delete dissociated children instead of physical |

## Acceptance and result shape

Check `isAccepted()` (Kotlin `isAccepted`) before treating an item as a successful write. It is **not** inferred from affected-row count and does not summarize every child in a graph. `INSERT_IF_ABSENT` conflicts, failed update conditions and incompatible subtype matches can be rejected normally.

**Check acceptance before DTO conversion for rejectable commands.** `execute(View.class)` and `toView(converter)` convert eagerly, even for a rejected partial root. A required DTO property such as a generated ID can still be unloaded, so conversion can throw before the caller reaches `isAccepted()`. Request the entity Fetcher, inspect acceptance, then construct the View only for an accepted root:

```java
var result = sqlClient.saveCommand(incoming)
    .setMode(SaveMode.INSERT_IF_ABSENT)
    .execute(DomainObjectDetailView.METADATA.getFetcher());
if (result.isAccepted()) {
    return new DomainObjectDetailView(result.getModifiedEntity());
}
// If the caller needs the existing row, look it up by the complete unique key.
```

Plain `execute()` also avoids eager DTO conversion when no richer accepted result is needed. A rejected entity can remain partial even when a Fetcher was requested; do not convert or read its unloaded properties as if it were the stored row.

For an accepted save, use `modifiedEntity`, or request a fetcher/View instead of an unconditional save-then-find:

```java
DomainObjectDetailView view = repository.saveCommand(input)
    .setMode(SaveMode.INSERT_ONLY)
    .execute(DomainObjectDetailView.class)
    .getModifiedView();
```

The result is not automatically a fully loaded entity. Jimmer copies known values, uses supported DML returning for unresolved local columns, then fetches the residual shape (associations/formulas or unsupported returning). For database-rewritten values, use `setSaveResultReadsAllProperties(true)` and request the fields needed. Test the rejected branch separately: a useful-looking input object or affected-row count is not an acceptance flag.

**Conflict exception to the no-requery guideline:** `INSERT_IF_ABSENT` does not return the conflicting root's generated ID/full row, even with `execute(View.class)`. If the API requires that row without updating it, check rejection then query by the unique key with the intended isolation/retry policy. The insert and lookup are separate statements. `UPSERT` can retrieve an existing row using a technical `SET col = col`; this is still an UPDATE, may fire database triggers, and is not a substitute for a strict no-update requirement.

| Situation (0.12.4) | Result contract |
|---|---|
| `UPSERT` + `forbidUpdate()` + supplied ID + requested Fetcher/View | existing row is accepted and the result carries the **stored** values, not the input; with dialect returning this is a fake update (affected rows 1, triggers fire), otherwise a follow-up select |
| Same with `INSERT_IF_ABSENT` | conflicting root rejected; no stored values returned |
| Accepted `UPSERT` with nothing to update | can be accepted with zero affected rows for its own table; an id-only root with an associations collection may never query or lock the root row |
| Key-matched returning with converted key values (enum, scalar provider) | returned rows are matched after normalizing key values (0.12.4) |

`UPSERT` and acceptance do not lock the root row for the transaction; when another transaction must not delete it before its associations are saved, lock explicitly (`setPessimisticLock` or a prior `forUpdate` read) and verify the emitted SQL actually covers that row. In the fork (`1.0.5+`), residual result fetching skips satisfied to-one ID views and seeds a known subtype discriminator instead of rereading the whole polymorphic object.

Logical-delete flags on save (0.12.4): an explicitly loaded **live** value (for example `RUNNING` when `DELETED` marks deletion) is preserved; an unloaded flag is initialized only on insert, never reset by an update; a loaded **deleted** value is rejected, so use a delete command. Key matching uses the exact live enum/int value.

## Conditional writes and external versions

Use `setUpdateWhere` for ordinary eligibility/rejection; use `setOptimisticLock` when a failed check must raise a conflict. The condition applies only to the update branch, never suppresses a missing row's insertion, and reads local physical columns (no joins). Referenced incoming values must be loaded.

```kotlin
val result = sqlClient.save(incoming) {
    setMode(SaveMode.UPSERT)
    setVersionMode(VersionMode.ASSIGNMENT)
    setUpdateWhere(CatalogRevision::class) {
        newNonNull(CatalogRevision::version) gt table.version
    }
}
if (result.isAccepted) {
    consume(result.modifiedEntity)
}
```

`VersionMode.OPTIMISTIC_LOCK` is the default. `ASSIGNMENT` writes the supplied version without implicit checking/increment; use it for externally owned versions, not to silence stale-write errors. **Version mode is root-only**, not propagated to associations.

With an effective update condition, mutable owning-side reference objects are rejected before SQL; use id-only/null references. A rejected owner skips post-associations. Native conditional upsert is dialect-dependent; select-then-write fallback needs explicit isolation/locking for concurrency guarantees.

For atomic column math use save `set(...)` assignment expressions (`jimmer-performance`), with the target loaded and included by the mask. Eligibility (`setUpdateWhere`) and the value assigned (`set`) are independent.

## QueryReason

SQL log tags extra selects with `QueryReason` — why the SQL-level upsert degraded. Frequent causes: `INTERCEPTOR` (a `DraftInterceptor` is registered — use `DraftPreProcessor` when existence check is not needed), `TRIGGER`, `OPTIMISTIC_LOCK`, `KEY_UNIQUE_CONSTRAINT_REQUIRED`, `NULL_NOT_DISTINCT_REQUIRED`, `NO_MORE_UNIQUE_CONSTRAINTS_REQUIRED`, `UPSERT_NOT_SUPPORTED`, `INVESTIGATE_CONSTRAINT_VIOLATION_ERROR`.

## Association decision

First check whether the association is loaded. An omitted association is not an empty collection. For a loaded collection, `REPLACE` can remove missing members; `MERGE` keeps them. Child-row deletion additionally needs the owning reference's dissociation policy; clearing a many-to-many relation normally removes links, not shared target rows. Use destructive replacement only when deleting/reinserting is itself the required behavior.

Verify insert, existing/conflicting row, rejected condition and stale-version behavior, including batch item acceptance. Sources: [SaveMode contract](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/mutation/SaveMode.java), [returning](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/returning), [update conditions](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/update-where), [version mode](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/version-mode), [matchByKey/forbidUpdate contract](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/mutation/AbstractEntitySaveCommand.java), [upsert concurrency](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/save-mode), [fork save-result notes](https://github.com/sleepkqq/jimmer/blob/main/FORK.md).
