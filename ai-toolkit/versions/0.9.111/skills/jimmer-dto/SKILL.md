---
name: jimmer-dto
description: Define Jimmer generated Java/Kotlin Views, Inputs and Specifications in .dto files; preserve PATCH omission/null semantics and diagnose APT/KSP generation.
metadata:
  toolkit: jimmer-ai-toolkit
  kind: task
---

# DTOs

Read the entity and actual APT/KSP version. The same `.dto` syntax generates Java or Kotlin types; do not hand-write replacement DTO mappers.

1. Choose output View, writable `input`, or `specification`. Public output fields and writable input fields are separate allow-lists.
2. Bind with `export` or use `src/main/dto/<entity package>/<Entity>.dto`. A Java module whose entities come from a dependency may need `@EnableDtoGeneration` so APT runs on a DTO-only module. The grammar is `export` plus nested blocks: `for Entity`, named `fragment`/`#include`, `association -> OtherDto`, `fold` and polymorphic `#types`/`#exhaustive` are not part of it.
3. Compile with `scripts/compile.sh /path/to/project` (relative to this skill). DTO-only changes must actually rerun APT/KSP; check generated output and force the processor task if stale.
4. For PATCH verify JSON omission, null, value and empty collection through the application's real mapper and generated `toEntity()`.

```dto
export example.Book

BookView {
    id
    name
    price
    version
    store {
        id
        name
    }
}

dynamic input BookPatch {
    id
    name?
    store {
        id
    }
}

specification BookSpec {
    like/i(name)
    ge(price) as minPrice
}
```

Assumes nullable `Book.store` and non-null `name`. Do not append `?` to an already nullable property; the compiler rejects redundant optionality. `dynamic` does not make required fields optional by itself.

Java consumer:

```java
Book patch = input.toEntity();
boolean storeWasSubmitted = ImmutableObjects.isLoaded(patch, BookProps.STORE);
var saved = sqlClient.saveCommand(input)
    .setMode(SaveMode.UPDATE_ONLY)
    .execute();
```

Kotlin consumer:

```kotlin
val patch = input.toEntity()
val storeWasSubmitted = isLoaded(patch, Book::store)
val saved = sqlClient.save(input) { setMode(SaveMode.UPDATE_ONLY) }
```

Java `ImmutableObjects` and Kotlin `org.babyfish.jimmer.kt.isLoaded` inspect presence. Never read a possibly omitted field just to check it.

| Submitted optional field | Entity state | Save effect |
|---|---|---|
| Omitted | Unloaded | Preserve stored property |
| Nullable field explicitly null | Loaded null | Clear writable nullable column/reference |
| Collection `[]` | Loaded empty | `REPLACE` removes members/links; `MERGE` retains them |
| Collection of IDs | Loaded references | Associated mode chooses replace/merge; validate scope |

For owned-child removal the owning reference's `@OnDissociate` also applies. A DTO alone does not determine collection persistence. Do not reinterpret invalid null for a non-null property as an allowed clear. See `jimmer-save-modes`.

Read [GUIDE.md](GUIDE.md) for the release's syntax and input modes.
