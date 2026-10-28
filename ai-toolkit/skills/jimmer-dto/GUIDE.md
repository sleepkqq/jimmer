# Jimmer DTO

Use for `.dto` files: Views, Inputs, and Specifications.

## Workflow

1. Read the entity interface and related DTO files.
2. Count DTO names requested by user. Generate exactly those names.
3. Without `export`, use `src/main/dto/{entity package path}/{EntityName}.dto`. With `export`, path/name are flexible; keep project conventions. A file can also use explicit `for Entity` declarations for multiple target entities.
4. Compile with:

```bash
scripts/compile.sh /path/to/project
```

Verify DTO-only edits trigger the actual processor. A Java DTO-only consumer module may need `@EnableDtoGeneration` when its entities come from a dependency.

Kotlin/KSP2 (Gradle): register DTO files as task inputs in every module that owns `.dto` files, otherwise a DTO-only change can leave `kspKotlin` `UP-TO-DATE` with the previous generated classes:

```kotlin
import com.google.devtools.ksp.gradle.KspAATask

tasks.withType<KspAATask>().configureEach {
    val dtoDirectory = if (name == "kspKotlin") "src/main/dto" else "src/test/dto"
    inputs.files(fileTree(dtoDirectory) { include("**/*.dto") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
```

Register the directories from `jimmer.dto.dirs` / `jimmer.dto.testDirs` instead when those KSP args are set. Run the Gradle build after DTO edits; an IDE build that does not go through Gradle ignores these inputs. Without them (or for Maven/APT setups), use the DTO companion plugin or a clean rebuild of that module.

## Choose DTO Type

| User asks for | DTO type |
|---|---|
| Read projection, list/detail response | plain block (implements `View<E>`) |
| Create/update/save input | `input` |
| Force nullable entity fields non-null in Input | `unsafe input` |
| Query filter object (Super QBE) | `specification` |

## Core Syntax

```dto
export com.app.model.DomainObject -> package com.app.model.dto

DomainObjectView {
     id
     name
    relatedObject {
        id
        name
    }
}

input DomainObjectCreateInput {
    name
    id(relatedObject) as relatedObjectId
}

specification DomainObjectSpec {
    like/i(name)
    eq(status)
    ge(createdAt)
    associatedIdEq(relatedObject)
}
```

## Composition & Reuse

Two mechanisms; pick by what is being reused:

| Reuse | Mechanism | Generates a type |
|---|---|---|
| An associated object's shape across several DTOs | reusable DTO: `store -> StoreView` | yes (the referenced DTO) |
| A set of properties across DTOs of any kind | `fragment` + `#include(Name)` | no |

```dto
StoreView for BookStore { id  name }

fragment BookSummaryFields for Book { id  name  edition }

BookView for Book {
    #include(BookSummaryFields)
    store as storeInfo -> StoreView    // property type IS StoreView; no TargetOf_store generated
}
```

- `@Embeddable` properties accept a reusable DTO too (0.12.4): `contact -> ContactInfoInput`. The referenced DTO must target the embeddable type and match the kind.
- A reusable DTO from another `.dto` file is referenced by its generated type: add `import com.example.dto.ContactInfoInput`.
- Output DTO associations must reference output views; input associations reference inputs; specifications reference specifications (`store -> StoreSpecification` keeps join-vs-exists semantics from the association cardinality).
- Fetch configurations (`!fetchType`, `!batch`, `!where`, `!orderBy`) stay local to the using association — never copied into the referenced DTO.
- Fragments are DTO-kind neutral (may carry `like`/`ge` etc. — validated at include site), support macros/exclusions/nested fragments; `#types` is not allowed inside a fragment.
- Reusable DTOs may come from a compiled dependency; a module can publish its `.dto` sources as a bundle (`META-INF/jimmer/dto-bundle.properties`) so downstream modules compile against them. Cyclic reusable-DTO references are a compile error — use explicit recursive associations instead.

## Macros

| Syntax | Meaning |
|---|---|
| `#allScalars` | all scalar props including inherited |
| `#allScalars(this)` | scalars declared on this entity only |
| `#allScalars(BaseType)` | scalars from specific supertype |
| `#allScalars?` | all included scalars nullable |
| `#allScalars!` | force non-null (unsafe input) |

Props annotated `@ExcludeFromAllScalars` in the entity are skipped by `#allScalars`.

## Prop Syntax

| Syntax | Meaning |
|---|---|
| `-field` | exclude after a macro |
| `field?` | make a non-null base property optional; illegal if it is already nullable |
| `field!` | force required/non-null in allowed contexts; ordinary non-ID inputs need `unsafe`, while ID/specification rules differ |
| `field as alias` | rename |
| `id(assoc) as assocId` | FK id projection (alias mandatory for list assoc: `id(items) as itemIds`) |
| `flat(assoc) { name as assocName }` | inline association/embeddable fields into parent |
| `fold(group) { name  edition }` | inverse of flat: group own scalar props into a nested object; `fold(x)?` makes group nullable; nestable, combinable with flat |
| `children*` | recursive fetch of self-association |
| `prop -> { CONST: "value", ... }` | enum value mapping (string or int) |
| `extraProp: Type = default` | user prop with literal default; `import` custom types |
| `as(^ -> prefix) { ... }` / `as(suffix$ -> ) { ... }` | alias group: batch-rename props inside block |
| `implements a.b.Iface` | on type or prop body |

DTO types and props accept passthrough annotations (`@com.x.Anno(...)`, or the simple name after `import`) and doc comments. Array arguments (0.12.4): `@Anno(1, 2)` and `@Anno([1, 2])` both set `value`; `[]`/`{}` is an empty array; a single value widens to an array/vararg parameter. Do not combine an array literal with further positional values.

A user property (`name: Int`) may not reuse an alias produced by a macro or alias group (`#allScalars`, `as($ -> file) { ... }`); that is a compile error. Redeclaring the entity property itself (`name?`) is the supported override of the macro copy.

## Input Handle Modes

Type-level or per-prop modifiers, `input` only: `fixed`, `static`, `dynamic`, `fuzzy`.

| Mode | Prop absent in JSON | Prop null in JSON |
|---|---|---|
| `fixed` | rejected by deserialization (must be explicit) | loaded null for a nullable property |
| `static` (default) | treated as null | set DB column to null |
| `dynamic` | prop not loaded — column untouched | set DB column to null |
| `fuzzy` | prop not loaded | prop not loaded (can never null out) |

This table describes optional/nullable input properties; it does not permit null for every non-null entity field. HTTP status depends on the framework's exception handling. `dynamic`/`fuzzy` leave omitted values unloaded, but only `dynamic` preserves explicit null. Mark otherwise required properties optional (`?`) when omission is allowed; leave already nullable fields unmodified. For to-many associations distinguish omitted, empty, and invalid null; do not equate `[]` to omission.

## Specification Functions

Only inside `specification`: `eq`, `ne`, `lt`, `le`, `gt`, `ge`, `like`, `notLike`, `null`, `notNull`, `valueIn`, `valueNotIn`, `associatedIdEq`, `associatedIdNe`, `associatedIdIn`, `associatedIdNotIn`.

- `like` flags: `like/i` insensitive, `like/^` prefix match, `like/$` suffix match, combinable (`like/i^`).
- Multi-prop OR form allowed for `eq`, `like`, `null`, `notNull`, `valueIn`, `associatedIdEq`, `associatedIdIn`: `like/i(firstName, lastName) as name`. Alias mandatory with multiple args.
- Alias mandatory for `ne`, `notLike`, `valueIn`, `valueNotIn`.
- Use `associatedIdEq(assoc)` for FK filters; plain `id(assoc)` is forbidden in specification.
- Null args are skipped at query time (dynamic predicates).
- `like` values are passed as raw LIKE patterns: `%`/`_` typed by the caller stay wildcards. Escape them with the database's escape character when the API promises literal matching; a non-exact mode still appends its own `%` after an escaped trailing `\%` (fixed in 0.12.4, #1383).

## Configurations

Annotation-like options written **before** an association prop, only in output DTOs (not input/specification):

```dto
BookStoreView {
    !where(edition >= 2) !orderBy(name asc) !batch(16)
    books {
        id
        name
    }
}
```

Configurations on `id(assoc)` or `flat(assoc)` are rejected (0.12.4): use a real association block when the association needs filtering/fetch settings.


| Config | Purpose |
|---|---|
| `!where(a = 1 and b <> 'x')` | filter associated rows (SQL-ish predicate, `is null` / `is not null` supported) |
| `!orderBy(name asc, createdAt desc)` | sort associated rows |
| `!filter(FilterClass)` | custom `FieldFilter`, replacing declarative association where/order settings |
| `!recursion(StrategyClass)` | custom recursion strategy for `*` props |
| `!fetchType(SELECT \| JOIN_IF_NO_CACHE \| JOIN_ALWAYS \| AUTO)` | reference association fetch strategy |
| `!limit(n[, offset])` | limit associated collection |
| `!batch(n)` | batch size for this association load |
| `!depth(n)` | recursion depth limit for `*` props |

## Rules

- Allow-list stable public response fields. `#allScalars` is useful for intentional internal/full shapes, but a new entity scalar then expands the DTO automatically.
- Inputs include only writable fields; never manually map input to entity — use generated `toEntity()` or `saveCommand(input)`.
- Nested block: one field per line.
- Enum mapping belongs in DTO only when API shape differs from entity enum.

Sources: [DTO language](https://babyfish-ct.github.io/jimmer-doc/docs/object/view/dto-language), [KSP DTO inputs](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-ksp/README.md), [DTO grammar](https://github.com/sleepkqq/jimmer/blob/main/project/jimmer-dto-compiler/src/main/antlr/org/babyfish/jimmer/dto/compiler/Dto.g4), [composition](https://babyfish-ct.github.io/jimmer-doc/docs/object/view/composition), [input null handling](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command/input-dto/null-handling).
