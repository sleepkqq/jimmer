---
name: jimmer-kotlin
description: Writes Kotlin-specific Jimmer code - entity and Draft usage, the Kotlin query and save DSL with null-aware predicates, KSP generation setup, KRepository contracts, DraftInterceptor versus DraftPreProcessor. Use when the user writes Jimmer code in Kotlin and the question is about Kotlin DSL, KSP or Kotlin API differences. Not for Java/APT-only questions.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Kotlin

Read the Kotlin compiler/KSP setup and one neighboring generated DSL use before editing. Keep runtime and processor coordinates aligned; for Quarkus use the unified fork described in `jimmer-quarkus`.

- Entity properties are `val`; Kotlin `T?` expresses nullability, not whether a property is loaded. Use the loaded-state API before reading an optional projection.
- Prefer the project's generated entity DSL; preserve partial shape and association references. Use generated input `toEntity()` to retain PATCH presence.
- Queries use entity `KClass`, `table`, and typed operators such as ``eq?``/``like?``. Predicate skipping is not `IS NULL`; ``like?`` also skips an empty string, but ``valueIn?`` skips only `null` and renders an empty collection as `1 = 0`.
- A property with an implemented getter and no `@Formula` is plain interface code since 0.12.4: no metadata, draft setter, fetcher field or column, and persistence annotations on it are rejected. Use `@Formula(dependencies = [...])` when it must be fetchable.
- Save result consumption must honor `isAccepted`; request a Fetcher/View for a different output shape. `setVersionMode(ASSIGNMENT)` is root-only; use `jimmer-save-modes` for external-version policies.
- Keep a `DraftInterceptor` when logic needs original values; a `DraftPreProcessor` can avoid a lookup only for unconditional input/default logic.

- Spring multiple datasources: `TransactionalSqlClients.kotlin()` routes to the `KSqlClient` passed to the active `JimmerTransactionManager` since 0.12.4, so build managers from `KSqlClient` when Kotlin-side configuration matters.

Compile through the application's actual KSP task and test the affected null/loaded-state behavior. Do not fix missing generated code by editing generated files. After upgrading to 0.12.4 (or fork 1.2.0) regenerate every model: the code-generation version is 0.12.4, and `.dto`-only edits need the DTO task inputs from the guide.

## Reference Routing

[Domain guide](GUIDE.md): read the section relevant to the behavior being changed, not the whole file by default. Existing scripts and relative resources remain beside this skill.

- Entity
- KSP setup
- Creation DSL
- KRepository Query
- Kotlin Rules
- DraftInterceptor vs DraftPreProcessor
