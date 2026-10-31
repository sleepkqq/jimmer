---
name: jimmer-dto
description: Writes Jimmer .dto files - read Views, writable Inputs and query Specifications, PATCH presence and null semantics, nested association DTOs and fragments - and fixes DTO generation failures. Use when the user adds or edits a .dto file, needs a view, input or specification for an entity, or partial-update semantics. Not for Fetcher-based read shapes (jimmer-fetchers) or save behavior of an Input (jimmer-save-modes).
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# Jimmer DTO

1. Read the entity, nearby DTOs and resolved APT/KSP version. Generate the requested types only.
2. Choose a read View, writable `input`, or query `specification`; a read shape is not a write allow-list. Keep public read fields explicit when schema additions must not silently expand the API.
3. For PATCH, use `dynamic input`: omitted optional fields are **unloaded**, explicit null clears a nullable field, and `[]` is a loaded empty association. `dynamic` does not make required properties optional by itself. In particular, a non-null collection needs `?` if it may be omitted. Adding `?` to an already nullable property is a compiler error. `fuzzy` cannot express an explicit null clear.
4. Follow the input through generated `toEntity()` into the actual save call. A DTO describes submitted state; it does not choose persistence policy. Use the state-to-effect table below before claiming what a PATCH will change.
5. Run the affected DTO processor/compile task through Gradle/Maven and verify regenerated output. Kotlin/KSP2 modules need the `.dto` trees registered as `KspAATask` inputs (GUIDE, Workflow); without them a DTO-only edit leaves `kspKotlin` `UP-TO-DATE` with stale classes. An IDE build that bypasses Gradle never sees those inputs.

```dto
export com.example.catalog.Book

dynamic input BookPatch {
    id
    name?
    store {
        id
    }
    authors? {
        id
    }
}
```

The example assumes `Book.name` and its authors list are non-null, while `Book.store` is nullable. Include the target binding (`export` or the conventional entity-matching file path) in a standalone DTO.

## Follow presence through persistence

| Submitted state | Generated entity state | Save consequence |
|---|---|---|
| Optional field omitted | Unloaded | Preserve that property |
| Nullable field explicitly null | Loaded null | Clear it if writable and schema permits |
| Collection explicitly empty | Loaded empty collection | `REPLACE` removes links/members; `MERGE` does not clear them |
| Collection has IDs | Loaded references | Associated mode determines replace/merge; validate reference scope |

For owned children, removing members must also agree with `@OnDissociate` on the owning child reference. For shared many-to-many targets, removing links is not deleting target rows. Keep this persistence condition beside any proposed collection-clearing contract; the DTO declaration alone cannot guarantee it. Use `jimmer-save-modes` for mode selection.

Deserialize omission, null and a real value through the application's JSON stack and check `toEntity()` loaded state; include `[]` for lists. Then verify the intended database effect with the chosen save mode. Making a non-null field optional for omission does not authorize clearing it to null.

## DTO compiler changes since 0.12.2

| Written | Result |
|---|---|
| `!where(...)`/`!batch(...)`/any `!config` before `id(assoc)`, or any config except `!fetchType` before `flat(assoc)` | error: configuration cannot apply to properties produced by `id`/`flat` (#1482); configure a real association block instead. Fork 1.2.6+ accepts `!fetchType(...)` on `flat(assoc)` to join-fetch the flattened reference |
| User prop `name: Int` whose name equals a macro-produced alias (`#allScalars`, `as(...)` group) | error: duplicated property alias (#1056). An explicit entity prop (`name?`) still overrides the macro copy |
| `contact -> ContactInfoInput` on an `@Embeddable` prop | now legal (#1168); kind must match (input → input DTO) and the DTO must target the embeddable type |
| `@Anno(1, 2)`, `@Anno([])`, `value = [...]` | legal (#1499): several positional values become the `value` array; mixing `[...]` with extra positional values is rejected |

## Reference Routing

[Syntax guide](GUIDE.md): read the relevant section for macros, reuse, input modes or configurations. For polymorphic `#types`/`#exhaustive`, use `jimmer-inheritance`. Scripts are relative to this skill's directory.

- Workflow
- Choose DTO Type
- Core Syntax
- Composition & Reuse
- Macros
- Prop Syntax
- Input Handle Modes
- Specification Functions
- Configurations
- Rules
