---
name: jimmer-kotlin
description: Implement Kotlin Jimmer entity/draft and query/save DSL code; resolve null-aware predicates, KSP generation, KRepository contracts, and interceptor versus preprocessor behavior.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Kotlin

Read the Kotlin compiler/KSP setup and one neighboring generated DSL use before editing. Keep runtime and processor coordinates aligned; for Quarkus use the unified fork described in `jimmer-quarkus`.

- Entity properties are `val`; Kotlin `T?` expresses nullability, not whether a property is loaded. Use the loaded-state API before reading an optional projection.
- Prefer the project's generated entity DSL; preserve partial shape and association references. Use generated input `toEntity()` to retain PATCH presence.
- Queries use entity `KClass`, `table`, and typed operators such as ``eq?``/``like?``. Predicate skipping is not `IS NULL`; check empty input behavior intentionally.
- Save result consumption must honor `isAccepted`; request a Fetcher/View for a different output shape. `setVersionMode(ASSIGNMENT)` is root-only; use `jimmer-save-modes` for external-version policies.
- Keep a `DraftInterceptor` when logic needs original values; a `DraftPreProcessor` can avoid a lookup only for unconditional input/default logic.

Compile through the application's actual KSP task and test the affected null/loaded-state behavior. Do not fix missing generated code by editing generated files.

## Reference Routing

[Domain guide](GUIDE.md): read the section relevant to the behavior being changed, not the whole file by default. Existing scripts and relative resources remain beside this skill.

- Entity
- Creation DSL
- KRepository Query
- Kotlin Rules
- DraftInterceptor vs DraftPreProcessor
