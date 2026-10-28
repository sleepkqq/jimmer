---
name: jimmer-advanced-mappings
description: Maps advanced Jimmer properties - @Formula, transient resolvers, @IdView, @ManyToManyView, @LogicalDeleted soft delete, @Embeddable and @Serialized JSON values, @MapsId and nonstandard associations - and aligns their loading, saving and caching. Use when the user adds a computed field, an ID view, soft delete, an embedded or JSON column. Not for plain fields and associations (jimmer-entity).
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Advanced Mappings

Choose the mapping from the schema and required read/write behavior, not from naming. Verify the annotation/API in the installed runtime and processor.

- `@Formula(dependencies=...)` computes from loaded dependencies; SQL formulas are needed when the computation must participate in SQL predicates/order. Neither is a writable stored field. In Kotlin (0.12.4+) an implemented getter **without** `@Formula` is not a Jimmer property at all (no fetcher field, no column).
- `@IdView` exposes association IDs; `@ManyToManyView` is a read-only shortcut through an association entity.
- `@LogicalDeleted` adds ORM visibility/delete semantics. Unique-key/upsert behavior must match the flag strategy, real index and dialect; a boolean flag cannot hold unlimited deleted duplicates under a simple `(key, deleted)` unique constraint.
- Saves (0.12.4+) keep a loaded **live** flag value (e.g. enum `RUNNING` with `@LogicalDeleted("DELETED")`), initialize an unloaded flag only on insert, and reject a loaded deleted value — delete through a delete command. Enum/int flags with several live values join business-key matching.
- `@MapsId` maps real FK/PK column identity. Keep an ordinary `@Id`; do not annotate the ID as an `@IdView`.
- Batch `TransientResolver.resolve(ids)` work and explicitly invalidate dependent calculated caches. Shared resolver context is an opt-in API: verify the installed signature.
- For polymorphic entities use `jimmer-inheritance`; generic mapped superclasses only share fields/association contracts.

Compile the mapping and verify one load/save against the schema. Computed mappings also need a missing-dependency or invalidation check.

## Reference Routing

[Domain guide](GUIDE.md): read the section relevant to the behavior being changed, not the whole file by default. Existing scripts and relative resources remain beside this skill.

- @Formula — calculated scalar
- @IdView — FK as scalar
- @ManyToManyView — deep association shortcut
- @LogicalDeleted — soft delete
- @Embeddable + @PropOverride
- @Serialized — JSON column
- @MapsId — target id inside own id
- @Transient + TransientResolver — computed association/aggregate
- Generic mapped superclass
- Enum mapping
- Misc
