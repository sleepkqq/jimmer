# Jimmer Kotlin

Use when target project uses Kotlin with Jimmer.

## Entity

```kotlin
@Entity
interface DomainObject {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    val name: String

    @ManyToOne
    @JoinColumn(name = "related_object_id")
    val relatedObject: RelatedObject
}
```

Nullability via Kotlin `T?`, not annotations.

## Creation DSL

Use generated DSL:

```kotlin
val domainObject = DomainObject {
    name = "value"
    relatedObject = RelatedObject { id = relatedObjectId }
}
```

Prefer generated `Entity { ... }` when available; `new(Entity::class).by { ... }` is also an upstream API, not an error merely because another syntax is shorter.

## KRepository Query

```kotlin
interface DomainObjectRepository : KRepository<DomainObject, UUID> {
    fun <V : View<DomainObject>> search(
        nameQuery: String?,
        page: Int,
        size: Int,
        viewType: KClass<V>,
    ): Page<V> =
        sql.createQuery(DomainObject::class) {
            where(table.name `like?` nameQuery)
            orderBy(table.createdAt.desc())
            select(table.fetch(viewType))
        }.fetchPage(page, size)
}
```

## Kotlin Rules

- `table` supports collection joins; no `TableEx` needed.
- Null-safe operators: ``eq?``, ``ne?``, ``gt?``, ``ge?``, ``lt?``, ``le?``, ``like?``, ``valueIn?`` — null (and empty string for like) skips the predicate.
- Use `KClass<V>` instead of `Class<V>`.
- Save options via DSL lambda: `sql.save(entity) { setMode(...); setAssociatedModeAll(...) }`; use the relevant View/fetcher overload and check `isAccepted` before consuming `.modifiedView`/`.modifiedEntity`.
- Config must set `jimmer.language: kotlin` (or `quarkus.jimmer.language: kotlin`).
- KSP dependency must be present; DTO/draft generation runs through KSP.

## DraftInterceptor vs DraftPreProcessor

`DraftInterceptor` can require preselection (`QueryReason.INTERCEPTOR`) to obtain original state. For defaults independent of existence/original values prefer `DraftPreProcessor`; it avoids that lookup but does not guarantee every other native-upsert prerequisite. Declared dependencies control which original properties are loaded. Do not overwrite a creation timestamp on every update by blindly assigning it in a preprocessor.

```kotlin
@ApplicationScoped
class ModelDraftInterceptor : DraftInterceptor<Model, ModelDraft> {
    override fun beforeSave(draft: ModelDraft, original: Model?) {
        draft.updatedAt = Instant.now()
        if (original == null && !isLoaded(draft, Model::version)) {
            draft.version = 0
        }
    }

    // batch variant to avoid N+1 in interceptor logic:
    // override fun beforeSaveAll(items: Collection<DraftInterceptor.Item<Model, ModelDraft>>)

    // props of `original` to load beyond id/key:
    // override fun dependencies(): Collection<TypedProp<Model, *>>
}
```

Sources: [Kotlin DSL](https://babyfish-ct.github.io/jimmer-doc/docs/query/dynamic-join/kotlin-join), [draft interceptor](https://babyfish-ct.github.io/jimmer-doc/docs/mutation/draft-interceptor), [Kotlin resolver API](https://github.com/babyfish-ct/jimmer/blob/v0.12.2/project/jimmer-sql-kotlin/src/main/kotlin/org/babyfish/jimmer/sql/kt/KTransientResolver.kt).

```kotlin
@ApplicationScoped
class BookNamePreProcessor : DraftPreProcessor<BookDraft> {
    override fun beforeSave(draft: BookDraft) {
        if (isLoaded(draft, Book::name)) draft.name = draft.name.trim()
    }
}
```
