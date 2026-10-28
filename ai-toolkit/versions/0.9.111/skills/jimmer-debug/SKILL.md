---
name: jimmer-debug
description: Diagnose Jimmer Java/Kotlin save exceptions, unloaded properties, APT/KSP failures, DTO syntax mismatches and QueryReason preselection.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Debugging

Collect exact runtime and processor coordinates, entity/DTO definitions, exception class/message/path, dialect, transaction scope and relevant SQL/bindings. Reproduce the failing flow before changing annotations or save mode. Use `scripts/compile.sh /path/to/project` relative to this skill for the affected module.

| Symptom | First check |
|---|---|
| `NeitherIdNorKey` | Correct ID/key and root/associated mode; wild child objects are not idempotent upserts |
| `CannotDissociateTarget` | Loaded collection, `REPLACE`, owning reference's `@OnDissociate` |
| `NotUnique` | Real unique constraint and create-vs-upsert contract |
| `IllegalTargetId` | Referenced row existence; this is not authorization validation |
| `TargetIsNotTransferable` | Intentional and authorized child transfer |
| `IncompleteProperty` | Missing embedded/composite-ID components |
| `OptimisticLockError` | Missing/stale version or custom optimistic predicate |
| `UnloadedPropertyException` | Fetcher/View omitted field or partial save/input result |
| Missing draft/fetcher/DTO | Runtime/APT/KSP alignment, module inputs, actual processor execution |
| KSP says a file declares several immutable types | Split each model interface into its own Kotlin file |
| `isAccepted`, `setUpdateWhere`, `VersionMode`, `returning` appear in code | Not part of the save/bulk API; replace with the result/count forms (`jimmer-save-modes`) and `createUpdate` (`jimmer-dml`) |
| DTO `fragment`, `fold`, `for`, `#types` rejected | Use `export` plus nested blocks and the documented input modes |
| Extra save SELECT | Read `QueryReason`, do not guess from statement count alone |

Java loaded-state check:

```java
if (ImmutableObjects.isLoaded(book, BookProps.STORE)) {
    useStore(book.store()); // still nullable if mapping says so
}
```

Kotlin equivalent:

```kotlin
if (isLoaded(book, Book::store)) {
    useStore(book.store)
}
```

Fetching the required shape at the query boundary is usually better than sprinkling loaded-state guards everywhere. Guards are appropriate when partial state is the intended contract.

## QueryReason and exception handling

`INTERCEPTOR` means original-state logic required preselection; use a `DraftPreProcessor` only if it preserves semantics. `TRIGGER` is expected for transaction-trigger needs. `KEY_UNIQUE_CONSTRAINT_REQUIRED`, `NULL_NOT_DISTINCT_REQUIRED`, `NO_MORE_UNIQUE_CONSTRAINTS_REQUIRED`, `UPSERT_NOT_SUPPORTED`, `OPTIMISTIC_LOCK` explain native-upsert fallbacks. `INVESTIGATE_CONSTRAINT_VIOLATION_ERROR` means diagnostic querying after a real violation.

Spring defaults `constraint-violation-translatable=true`. Typed `SaveException` handling differs from raw driver exceptions; inspect which path is actually enabled. `ExceptionTranslator<SaveException.NotUnique>` can return a domain exception, or null when unhandled. Register via the existing DI/client or per-command `addExceptionTranslator`; declare the generic exception type.

Keep unique constraints, optimistic checks, dissociation protection and transaction scope intact. An affected-row count of zero is not universally a thrown exception; there is no acceptance flag. Verify the original failure plus a neighboring success/conflict branch, including stored state.

Source: [SaveException](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/exception/SaveException.java), [QueryReason](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/mutation/QueryReason.java).
