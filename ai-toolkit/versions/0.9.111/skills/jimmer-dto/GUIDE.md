# DTO language

Use one bound entity per `.dto` file and nested association blocks; nested generated types are expected. Keep association shapes inside the nested blocks instead of referencing named DTOs.

## Shapes and property syntax

| Syntax | Purpose |
|---|---|
| `BookView { ... }` | Output `View<Book>` |
| `input BookInput { ... }` | Writable `Input<Book>` and `toEntity()` |
| `unsafe input` | Allows forcing nullable base properties non-null where ordinary input rejects it |
| `specification BookSpec` | Query predicates, not a mutation payload |
| `#allScalars` / `#allReferences` / `(this)` / `(BaseType)` | All applicable scalar props, or reference props as IDs, including/slicing inherited ones |
| `#allScalars?` / `!` | Optional/required macro subject to type rules |
| `-field` | Exclude a macro-selected field |
| `field?` | Optionalize a non-null property; invalid on an already nullable base property |
| `field!` | Require a property where DTO/input rules permit |
| `field as alias` | Rename |
| `id(store) as storeId` | Association ID projection |
| `id(authors) as authorIds` | Association ID list; give it an alias |
| `flat(store) { name as storeName }` | Flatten association/embedded fields |
| `children*` | Recursive association |
| `status -> { ACTIVE: "active", DISABLED: "disabled" }` | Enum mapping |
| `extra: String = "value"` | User property with literal default |
| `as(^ -> prefix) { ... }` | Alias group |
| `implements package.Interface` | DTO or nested type interface |

`@ExcludeFromAllScalars` excludes an entity property from macros. Prefer explicit public output fields so a new entity column cannot silently expand an API. Inputs expose only fields callers may write. Imported types, passthrough annotations and documentation comments are supported.

## Input handle modes

Apply on `input` type or individual properties. This table concerns optional/nullable properties, not permission to clear every non-null field:

| Mode | Absent JSON key | Explicit null |
|---|---|---|
| `static` (default) | Loaded null | Loaded null |
| `fixed` | Deserialization error | Loaded null if nullable |
| `dynamic` | Unloaded | Loaded null if nullable |
| `fuzzy` | Unloaded | Unloaded |

Use `dynamic` for real PATCH null-clearing. `fuzzy` cannot clear nullable state. Non-null collections need `?` when omission is allowed; `[]` remains loaded. Test through Jackson before asserting presence semantics. Standalone Jackson 2 uses `org.babyfish.jimmer.jackson.ImmutableModule`.

## Specifications

Functions: `eq`, `ne`, `lt`, `le`, `gt`, `ge`, `like`, `notLike`, `null`, `notNull`, `valueIn`, `valueNotIn`, `associatedIdEq`, `associatedIdNe`, `associatedIdIn`, `associatedIdNotIn`.

- `like/i` ignores case; `like/^` / `like/$` control starts/ends-with.
- Multi-property forms such as `like/i(firstName, lastName) as name` require an alias and produce OR matching for supported functions.
- Give aliases to `ne`, `notLike`, `valueIn`, `valueNotIn` and ambiguous repeated-property predicates.
- `associatedIdEq(store)` is the specification form; `id(store)` is a View/Input projection.
- Null specification values skip optional predicates; they do not mean SQL `IS NULL`.

Use the generated spec in Java `.where(spec)` or Kotlin `where(spec)`, then select the intended View. A user-supplied spec cannot replace required authorization predicates.

Generated Kotlin DTOs are immutable by default: construct `BookSpec(name = "guide")`, rather than assigning to `val name` in an `apply` block. Java uses `new BookSpec()` plus `setName("guide")`. Respect an existing processor configuration if the project deliberately generates mutable Kotlin DTOs.

## Association configurations

`!where(...)`, `!orderBy(name asc)`, `!filter(FilterClass)`, `!recursion(StrategyClass)`, `!fetchType(SELECT | JOIN_IF_NO_CACHE | JOIN_ALWAYS | AUTO)`, `!limit(n[, offset])`, `!batch(n)`, `!depth(n)` are supported. Compile custom filter types against the project's resolved dependency. Bound recursive depth and collection size intentionally.

Sources: [exact grammar](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-dto-compiler/src/main/antlr/org/babyfish/jimmer/dto/compiler/Dto.g4), [DTO compiler](https://github.com/babyfish-ct/jimmer/tree/v0.9.111/project/jimmer-dto-compiler/src/main/java/org/babyfish/jimmer/dto/compiler).
