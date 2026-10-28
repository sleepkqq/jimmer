---
name: jimmer-debug
description: Diagnoses a concrete Jimmer failure - SaveException variants (NotUnique, rejected or unsupported mutations), UnloadedException, generated Draft/KSP/APT compile errors, SQLState translation in the fork, QueryReason fallbacks. Use when the user shows a Jimmer stack trace, exception or failing build and asks why. Not for slowness without an error (jimmer-performance) or general Kotlin build issues.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Jimmer Debug

Use for Jimmer save/query/runtime/generated-code errors.

## Workflow

1. Collect resolved runtime/processor coordinates, dialect/framework, exception class/message, entity, save/query code, SQL logs, acceptance flags and `QueryReason` if present. Do not assume every unsuccessful write throws.
2. Match quick table.
3. Inspect affected entity path, especially `exportedPath` in `SaveException`.
4. Check `@Key`, `@KeyUniqueConstraint`, `@OnDissociate`, nullability, FK constraints, and loaded properties.
5. Apply minimal fix and compile:

```bash
scripts/compile.sh /path/to/project
```

## Quick Fix Table

`SaveException` subtypes (`SaveException.NotUnique` etc.):

| Error | First check |
|---|---|
| `NeitherIdNorKey` | supply intended ID/key; insert-only/destructive replacement only if that matches the write contract |
| `CannotDissociateTarget` | inspect loaded collection, associated mode and `@OnDissociate` on the owning child reference |
| `NotUnique` | key/unique constraint conflict — preserve create-vs-upsert intent; do not silently change INSERT_ONLY to UPSERT |
| `NoKeyProp` / `NoVersion` | save matched by key without `@Key`, or optimistic mode without `@Version` |
| `IllegalTargetId` | referenced association id does not exist (`setAutoIdOnlyTargetChecking`) |
| `TargetIsNotTransferable` | child moved to another parent — authorize the transition before enabling transfer |
| `IncompleteProperty` | partial embeddable/composite value in save |
| `OptimisticLockError` | include `@Version`, re-read, or handle conflict |
| `KEY_UNIQUE_CONSTRAINT_REQUIRED` (QueryReason) | add `@KeyUniqueConstraint` and DB unique constraint |
| `UnloadedException` (`The property "X.y" is unloaded`) | add field to View/Fetcher or guard with `ImmutableObjects.isLoaded()` (Kotlin `isLoaded(obj, Prop)`) |
| `isAccepted=false` | skipped INSERT_IF_ABSENT, failed update condition, or incompatible subtype; not automatically an exception |
| Unsupported mutation with transaction triggers | `TRANSACTION_ONLY`/`BOTH` are deprecated; migrate listeners/caches to BINLOG_ONLY plus actual CDC |
| Missing Draft/Fetcher/DTO classes | aligned runtime + APT/KSP, source sets, processor execution and Jandex visibility for Quarkus |
| `The version of the ... cannot be less than "0.12.4" which is the last code generation version` | generated code is older than the runtime; align processor and runtime, clean and regenerate |
| `NoClassDefFoundError: org/babyfish/jimmer/runtime/NonSharedList` | 0.12.4 removed `NonSharedList` (#1055); stale generated classes or a mixed processor version, so regenerate. Immutable lists are now standard unmodifiable lists, and mutating one throws `UnsupportedOperationException` |
| `Cannot resolve the draft object because it belong to another draft context` | a cached/deserialized immutable graph (ID views) reused inside a draft before 0.12.4 (#1274); upgrade, and otherwise do not pass drafts across `produce` contexts |
| `Cannot match entity type ... by key: expected exactly one key group` | `matchByKey()` on a type with zero or several key groups; add `setKeyProps` for that root type |
| `The transactional sql client is used, however, there is no AOP transaction...` | Spring `TransactionalSqlClients` used outside a `JimmerTransactionManager` transaction |

## Common Diagnoses

`NeitherIdNorKey`: save mode must identify child records. Add natural `@Key`, pass id, or use `AssociatedSaveMode.VIOLENTLY_REPLACE` for delete-all/reinsert semantics.

`CannotDissociateTarget`: `REPLACE` found DB children absent from a loaded new collection. Confirm replacement was intended, then set `@OnDissociate(DELETE)` or nullable `SET_NULL` on the owning reference. The inverse collection cannot carry the annotation; database ON DELETE is a separate policy.

`UnloadedException`: generated immutable object has unloaded field. Query must fetch it via View/Fetcher, or code must test loaded state.

`OptimisticLockError`: partial update missing or stale version. Include version field in update input/view where needed.

Unexpected extra SELECTs before save: read `QueryReason` in SQL log. `INTERCEPTOR` means a `DraftInterceptor` disabled SQL-level upsert — use `DraftPreProcessor` if existence check not needed. `INVESTIGATE_CONSTRAINT_VIOLATION_ERROR` means Jimmer re-queried to translate a constraint violation into a typed error.

## ExceptionTranslator

Identify the configured translation path before choosing the exception type. In the unified Quarkus module, `constraint-violation-translatable=false` and `sql-state-exception-translator=true` default to SQLState-based `JimmerDataAccessException` subtypes. A `SaveException.NotUnique` translator is for the typed constraint-investigation path; it is not guaranteed to receive the default Quarkus error.

Fork SQLState mapping (`SqlStateExceptionTranslator`, `io.quarkiverse.jimmer.runtime.exception`), applied only when Jimmer did not already translate the failure:

| SQLState | Exception |
|---|---|
| `23505` | `DuplicateKeyException` |
| `23503` | `ForeignKeyViolationException` |
| `23502` | `NotNullViolationException` |
| `23514` | `CheckViolationException` |
| other `23xxx` (MySQL `23000`) | `DataIntegrityViolationException` |
| `40001` / `40P01` | `SerializationFailureException` / `DeadlockException` (both `TransientDataAccessException`, retry candidates) |

All extend `JimmerDataAccessException` (`getSqlState()`, `getSqlException()`). On PostgreSQL `getConstraintName()`, `getTableName()`, `getDetail()` and `getImmutableType()` (resolved to the single-table subtype for inserts) identify the violated rule; branch on the constraint name, not the message text. Unmapped states pass through to user translators unchanged.

When typed constraint translation is enabled:

```java
@ApplicationScoped // or @Component; also: sqlClient builder / per-command addExceptionTranslator
public class NotUniqueTranslator implements ExceptionTranslator<SaveException.NotUnique> {
    @Override
    public Exception translate(SaveException.NotUnique ex, Args args) {
        if (ex.isMatched(DomainObjectProps.NAME)) {
            return new ConflictException("Name already taken: " + ex.getValue(DomainObjectProps.NAME));
        }
        return null; // not handled — try other translators
    }
}
```

Generic type argument is mandatory. Registration: per save command (highest priority), sql client builder, or DI bean.

## Rule

Fix root annotation/query/save-mode mismatch. Do not hide Jimmer errors with broad catch blocks or re-query loops.

Verify the original failing operation and one neighboring mode/path; do not weaken ownership, uniqueness or optimistic-lock checks to make the exception disappear. Sources: [save investigation](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/investigation), [Quarkus exceptions](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/exception), [generator version check](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-core/src/main/java/org/babyfish/jimmer/meta/GeneratorVersionChecker.java), [save acceptance](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/returning).
