---
name: jimmer-advanced-mappings
description: Map Jimmer formulas and transient resolvers, soft deletion, embedded/JSON values, ID views, MapsId and nonstandard associations; align their loaded-state, save and cache behavior.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: reference
---

# Jimmer Advanced Mappings

Choose the mapping from the schema and required read/write behavior, not from naming. Verify the annotation/API in the installed runtime and processor.

- `@Formula(dependencies=...)` computes from loaded dependencies; SQL formulas are needed when the computation must participate in SQL predicates/order. Neither is a writable stored field.
- `@IdView` exposes association IDs; `@ManyToManyView` is a read-only shortcut through an association entity.
- `@LogicalDeleted` adds ORM visibility/delete semantics. Unique-key/upsert behavior must match the flag strategy, real index and dialect; a boolean flag cannot hold unlimited deleted duplicates under a simple `(key, deleted)` unique constraint.
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
