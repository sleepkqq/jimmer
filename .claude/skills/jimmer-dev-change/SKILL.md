---
name: jimmer-dev-change
description: Fix a defect or add behaviour in the Jimmer ORM the upstream maintainer's way. Use when the owner says "почини", "исправь баг в jimmer", "добавь фичу в форк", "доработай ORM", or asks to change query/save/fetcher/dialect/processor behaviour. Not for reviewing an existing diff (jimmer-dev-review) or writing only tests (jimmer-dev-tests).
---

# Change workflow (Jimmer fork)

Work from `project/` for Gradle. Read `AGENTS.md` first (plus any module `AGENTS.md`, e.g.
`project/jimmer-ksp`, `project/jimmer-ddl-compiler`); it holds the durable SQL invariants.
Load `jimmer-dev-cache-internals` before touching caches, `EntitiesImpl` or loaders. When a
change establishes a durable invariant, record it in `AGENTS.md` or the matching skill, and
user-visible behaviour in `FORK.md` and in the matching consumer skill under `ai-toolkit/`,
which always tracks the fork's current state.
No commit, push, tag or release without explicit owner authorization.

## 1. Reproduce before fixing

Add the failing case to the test class that already owns the layer, then fix. Never fix first and
back-fill a test.

| Layer | Owning test home |
|---|---|
| Immutable model, drafts, metadata | `project/jimmer-core/src/test/java/org/babyfish/jimmer/` |
| Query AST, fetchers, loaders, save, cache, dialect | `project/jimmer-sql/src/test/java/org/babyfish/jimmer/sql/{query,mutation,cache,json}/` |
| Kotlin DSL / client / fetcher | `project/jimmer-sql-kotlin/src/test/kotlin/org/babyfish/jimmer/sql/kt/` |
| Shared DTO language | `project/jimmer-dto-compiler/src/test/java/org/babyfish/jimmer/dto/compiler/` |
| KSP generation | `project/jimmer-ksp/src/test/kotlin/org/babyfish/jimmer/ksp/dto/` |
| DDL generation | `project/jimmer-ddl-compiler/src/test/kotlin/org/babyfish/jimmer/ddl/compiler/` |
| Quarkus extension | `project/jimmer-quarkus/integration-tests/src/test/java/io/quarkiverse/jimmer/it/` |

Fixtures live in `project/jimmer-sql-test/jimmer-sql-test-model` (and `-model-kotlin`); add a new
entity rather than distorting an existing one. `jimmer-apt` has no test source set — Java
generation is exercised by compiling those fixtures.

Type-inference, temporal, JSON and dialect defects need a real database reproduction
(`NativeDatabases`, see `jimmer-dev-tests`), not only pinned SQL or an H2 run.

## 2. Find the shared layer

Trace the failing path end to end before editing. Symptom is rarely where the fix belongs.

| Symptom | Start here |
|---|---|
| Wrong SQL/text/parameter order | `ast/impl/query/Queries.java`, `QueryAnalyzer.java`, `QueryRenderContext.java` |
| Wrong rows, joins, filters | `ast/impl/query/MutableRootQueryImpl.java`, `ConfigurableRootQueryImpl.java` |
| Wrong persisted state, ids, events | `ast/impl/mutation/Mutations.java`, `Saver.java`, `SaveContext.java`, `SaveReturningExecutor.java` |
| Missing/extra entity data on read | `fetcher/impl/FetcherImpl.java`, `loader/AbstractDataLoader.java` |
| Dialect syntax | `dialect/<Vendor>Dialect.java`, extend `Dialect` |
| Generated code or DTO error | `jimmer-dto-compiler`, `jimmer-apt`, `jimmer-ksp` |
| Kotlin wrapper divergence | `jimmer-sql-kotlin/.../impl/`, then delegate to the Java path |

Fix the shared abstraction, not the caller: `AbstractMutableStatementImpl.visitSubQuery` is the
right place for a predicate that all subqueries must inherit. Check every sibling caller of the
method you are about to touch.

## 3. Shape the change

- Smallest correct diff. Typical fixes are tens of production lines; tests are 2–3× larger.
- Reuse before writing: `Keys.matchingKeyOf`, `KeyMatcher`, `FetcherUtil.fetch`,
  `ImmutableObjects.makeIdOnly`, `JoinFetchFieldVisitor.hasTableFields`, existing SQL builders.
- Delete the duplicate instead of keeping both paths. If two methods share a prelude, lift one.
  A bespoke helper that stdlib already covers is deleted, not maintained.
- Prefer a narrow hook in an upstream class: widen an existing guard, extend an existing
  dispatch table, add a default method to an existing public interface. Keep fork-only logic in
  fork-owned classes so upstream merges stay trivial.
- Name the concept when a real one appears (compare `LikePattern`), not for one call site.
- Java/Kotlin parity, always: shared behaviour lives once and the Kotlin wrapper delegates;
  a Java test fix gets a Kotlin mirror when that path exists. APT changes get KSP counterparts.
- `jimmer-core`, `jimmer-sql` and processors compile as Java 8: no `var`, records, `List.of`,
  `Map.of`, `String.isBlank`, `Optional.isEmpty`; use `Arrays.asList`, `Collections.emptyList()`,
  explicit `LinkedHashMap`. The Quarkus extension targets 25.

Shape of code:

```java
public static String of(String pattern, LikeMode mode) {
    if (!mode.isStartExact() && !pattern.startsWith("%")) {
        pattern = '%' + pattern;
    }
    if (!mode.isEndExact()) {
        // An existing trailing '%' can be escaped. Appending another wildcard
        // also preserves the meaning of an unescaped '%' without assuming an escape character.
        pattern += '%';
    }
    return pattern;
}
```
(`project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/impl/LikePattern.java` — invariant
comment, no narration.)

- Package-private `final` implementation classes; small `private static` helpers; early returns.
- Extract long inline conditions into named predicates on the owner class, e.g.
  `isOptimisticLockActive(Shape)` and `isVersionUpdateRequired(...)` in
  `ast/impl/mutation/Operator.java`.
- Immutable data with copy-on-change: return `this` when nothing changes, a new instance otherwise.
- Throw JDK exceptions whose message names the offending property/type, not a generic "invalid
  argument".
- One comment per non-obvious invariant. Javadoc on public API/SPI contracts, stating semantics
  and thrown exceptions; implementation types belong in `.impl` packages, never in API packages.

## 4. Verify focused

Run the owning module's class, plus the neighbouring classes the change can reach:

```bash
./gradlew :jimmer-sql:test --tests 'org.babyfish.jimmer.sql.query.SubQueryGlobalFilterTest'
./gradlew :jimmer-sql:test --tests 'org.babyfish.jimmer.sql.mutation.ModifiedFetcherTest' \
  --tests 'org.babyfish.jimmer.sql.mutation.ModifiedAssociationFetcherTest' \
  --tests 'org.babyfish.jimmer.sql.mutation.SaveKeyPropsTest'
./gradlew :jimmer-sql-kotlin:test --tests '*VirtualPredicateTest'
./gradlew :jimmer-dto-compiler:test --tests 'org.babyfish.jimmer.dto.compiler.DtoCompilerTest'
./gradlew :jimmer-ksp:test --tests 'org.babyfish.jimmer.ksp.dto.DtoGeneratorTest'
./gradlew :jimmer-quarkus:integration-tests:test
```

Native cases:

```bash
env jimmer-sql-test-native-database=postgres ./gradlew :jimmer-sql:test --tests '...'
```

At most two heavy Gradle runs at a time. Record what actually ran. Skipped native cases are
`skipped`, never `passed`; state which database was not run.

## 5. Commit

One commit = one behaviour. Never mix build/CI/tooling into a feature commit; a version or
code-generation contract change goes in the commit that needs it and says so.

```
Preserve explicit live logical-delete values on save (#1359)

<what changed and why; the contract and invariants that must survive>

Validation: 27 new H2 cases and 144 neighbouring tests passed.
PostgreSQL regression added but not run locally.
```

- Subject: imperative, capitalised, ≤ ~72 chars, no `feat:`/`fix:` prefix, no trailing period,
  issue in parentheses when it came from a report.
- Fix subjects read `Fix X in/for Y`; features read `Support …`/`Add …`.
- Body: two to four short paragraphs, then the validation ledger with counts, classes and the
  native databases that were **not** run. Omit the body only for mechanical sequences.

## Done checklist

- [ ] Failing test existed first in the owning module's existing class/fixture.
- [ ] Root cause fixed in the shared layer; sibling callers checked.
- [ ] No parallel copy of an existing path; superseded code deleted.
- [ ] Upstream hooks are minimal (guard/dispatch/default method), not a fork branch.
- [ ] Java↔Kotlin and APT↔KSP parity done, or the absence of that path is stated.
- [ ] Java 8 APIs only in core/SQL/processors.
- [ ] Comments state invariants; public contracts documented; exceptions name the property.
- [ ] Focused tests run, counts recorded, skipped native tests named.
- [ ] Diff reviewed against `git diff`; no unrelated files, no secrets, no private names.
- [ ] Commit subject/body follow the format above; authorization obtained before any git write.

For the harness details, commands per module and the executed-vs-skipped rule, see
`jimmer-dev-tests`.
