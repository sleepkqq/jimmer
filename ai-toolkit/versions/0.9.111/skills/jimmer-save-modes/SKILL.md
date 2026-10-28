---
name: jimmer-save-modes
description: Choose Jimmer graph-save modes, natural keys, association replacement, masks, optimistic locks and partial save results in Java and Kotlin.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Graph saves

Establish identity, allowed changes, transaction scope and required return shape separately. Match the save API used by the project's resolved Jimmer dependencies.

| Root mode | Contract |
|---|---|
| `INSERT_ONLY` | Insert or constraint error; do not silently turn create into upsert |
| `INSERT_IF_ABSENT` | Insert if missing, do not update conflicting root |
| `UPDATE_ONLY` | Update existing root; verify missing-row behavior required by the caller |
| `UPSERT` | Insert/update by ID or key; can select first if native upsert is unavailable |
| `NON_IDEMPOTENT_UPSERT` | Also inserts objects with neither ID nor key; only for intentional non-idempotency |

Root mode does not select child behavior. For loaded collections:

| Associated mode | Effect |
|---|---|
| `REPLACE` | Insert/update supplied targets and dissociate missing members |
| `MERGE` | Insert/update supplied targets, retain missing members |
| `APPEND` | Insert supplied targets; existing-target conflicts are errors |
| `APPEND_IF_ABSENT` | Insert missing targets |
| `UPDATE` | Update existing targets |
| `VIOLENTLY_REPLACE` | Clear/reinsert; destructive semantics, extra work and trigger events |

Omitted collection = untouched; `[]` = loaded empty collection. `REPLACE` with `[]` removes links/members; `MERGE` does not clear them. Dissociating a removed child requires a dissociation policy on the owning reference: a nullable owning FK is not enough: the save detach path treats `NONE` as `CHECK` while `default-dissociation-action-checkable` is `true` and throws `SaveException.CannotDissociateTarget`. Annotate the owning reference with `@OnDissociate(SET_NULL)` (or `DELETE` when the child row should go), or override per command with `setDissociateAction`; owned-child deletion needs the same annotation. Removing many-to-many links does not delete shared targets. Set the mode explicitly when it matters: convenience overloads taking `SaveMode` can default associations to `MERGE`, unlike an unconfigured command's `REPLACE`.

## Same operation in both languages

Java, a generated Input or immutable entity:

```java
var result = sqlClient.saveCommand(input)
    .setMode(SaveMode.UPDATE_ONLY)
    .setAssociatedModeAll(AssociatedSaveMode.REPLACE)
    .execute();
Book saved = result.getModifiedEntity();
```

Kotlin:

```kotlin
val result = sqlClient.save(input) {
    setMode(SaveMode.UPDATE_ONLY)
    setAssociatedModeAll(AssociatedSaveMode.REPLACE)
}
val saved = result.modifiedEntity
```

`SimpleSaveResult` / `KSimpleSaveResult` expose modified/original entities and affected counts. **There is no acceptance flag**: verify the specific root via its affected-table count when the driver/operation's semantics permit, and test conflicts on the actual dialect.

Java and Kotlin result APIs are not identical:

| API | Java `SimpleSaveResult` | Kotlin `KSimpleSaveResult` | Use |
|---|---|---|---|
| Modified entity | `result.getModifiedEntity()` | `result.modifiedEntity` | Saved immutable instance; may be partial, not a full row |
| Original entity | `result.getOriginalEntity()` | `result.originalEntity` | Pre-save state when the caller needs it |
| `isModified` | `result.isModified()` | `result.isModified` | Original and modified instances differ (generated IDs, versions and back-references can trigger this); not persistence success |
| Root-table count | `result.getAffectedRowCount(Book.class)` | `result.affectedRowCount(Book::class)` | Rows changed for this entity table, not the whole graph; zero is meaningful |
| `isRowAffected` | — | `result.isRowAffected` | `affectedRowCountMap.isNotEmpty()`, including graph effects |

The Kotlin result is a separate interface, not the Java result exposed with property syntax: call `affectedRowCount(Book::class)`, not Java `getAffectedRowCount(...)`, and do not treat `isModified`/`isRowAffected` as acceptance signals.

## Result shape

`modifiedEntity` is not automatically a full database row. Generated IDs and known save values may be present; unloaded properties still throw. Fetcher/View execution overloads already exist:

```java
BookView view = sqlClient.saveCommand(input)
    .setMode(SaveMode.INSERT_ONLY)
    .execute(BookView.class)
    .getModifiedView();
```

```kotlin
val view = sqlClient.saveCommand(input) {
    setMode(SaveMode.INSERT_ONLY)
}.execute(BookView::class).modifiedView
```

Use these for the required successful-save shape; they may run a follow-up fetch. A conflicting `INSERT_IF_ABSENT` is not a general existing-row fetch: do not convert a partial conflict result blindly to a required-ID DTO. If the caller needs the unchanged existing row, use an explicit complete-key lookup and transaction/isolation policy, verified under concurrency. Never replace a strict no-update contract with `UPSERT` merely to retrieve an ID.

## Keys and options

- `@Key` / `setKeyProps([group,] props...)` choose natural identity; ID matching needs no natural key. `@KeyUniqueConstraint` must match real uniqueness. Null semantics (`isNullNotDistinct`) and MySQL's other unique constraints (`noMoreUniqueConstraints`) can affect native upsert.
- `setUpsertMask` limits insert/update columns (`UpsertMask.of(Book.class)` builds one); it does not authorize fields. Inputs should contain only caller-writable fields.
- `setIdOnlyAsReference[All]`, `setKeyOnlyAsReference[All]`, `setAutoIdOnlyTargetChecking[All]` control short references and checks. Reference existence is not tenant authorization.
- `setDissociateAction`, `setTargetTransferMode[All]`, `setDeleteMode` govern association changes; the dissociation action is declared on the owning reference prop (for example `Book::store`), not on the inverse collection. Child transfer must be intentional.
- `setPessimisticLock`, `setOptimisticLock`, `setTransactionRequired` preserve concurrency/transaction contracts. Do not remove a lock just to reduce selects.
- `setDumbBatchAcceptable`, `setMaxCommandJoinCount`, `setConstraintViolationTranslatable`, `addExceptionTranslator` are available; tune only for an observed need.

## Conditional writes

Use `@Version` or `setOptimisticLock` when mismatch must throw a conflict. For a business predicate that should report zero changed rows, use `createUpdate(...).where(...)` and inspect its count (`jimmer-dml`). Include identity and authorization in that predicate. Save assignment expressions, `setUpdateWhere` and `VersionMode` are not part of the graph-save API: atomic arithmetic belongs in typed bulk update. Updating an externally assigned revision that way needs explicit comparison/assignment in SQL and intentional bypass of graph-save semantics.

## Verify

Test insert, duplicate key, missing update, stale version, omitted vs empty association and child transfer. Inspect `QueryReason`: `INTERCEPTOR`, `TRIGGER`, `OPTIMISTIC_LOCK`, `KEY_UNIQUE_CONSTRAINT_REQUIRED`, `UPSERT_NOT_SUPPORTED` explain extra preselection. `DraftPreProcessor` avoids original-state lookup only when it actually fits the business logic.

Sources: [save command](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/mutation/SimpleEntitySaveCommand.java), [result](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/mutation/SimpleSaveResult.java), [Kotlin operations](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/ast/mutation/KSaveOperations.kt).

Result contracts: [shared MutationResultItem](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/mutation/MutationResultItem.kt), [KSimpleSaveResult](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/ast/mutation/KSimpleSaveResult.kt), [KMutationResult](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/ast/mutation/KMutationResult.kt).
