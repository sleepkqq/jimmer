---
name: jimmer-dev-review
description: Review a diff, branch or PR in the Jimmer fork against the upstream maintainer's standard. Use when the owner says "сделай ревью", "проверь качество кода", "review this branch", or asks whether a change is mergeable. Not for implementing the fix (jimmer-dev-change) or for writing tests (jimmer-dev-tests).
---

# Review (Jimmer fork)

Read-only: do not edit sources, tests or config. Reconstruct the change from Git and source before
judging; a green test run is not a verdict. Never write to third-party repositories.

## 1. Reconstruct

```bash
git status --short
git merge-base HEAD main                 # or the stated base
git diff --stat <base>...HEAD
git diff --numstat <base>...HEAD
git log --format=full <base>..HEAD
git diff <base>...HEAD -- project/jimmer-sql/src/main
```

Include uncommitted work if the owner asks to review the current state. Identify every behaviour
in the diff; if there are several unrelated behaviours, say so — it is already a finding.

## 2. Root-cause layer

- Does the fix live where the invariant is owned: query AST (`ast/impl/query`), save pipeline
  (`ast/impl/mutation`), fetcher/loader (`fetcher/impl`, `loader`), dialect (`dialect`), processor
  (`jimmer-dto-compiler`/`jimmer-apt`/`jimmer-ksp`), cache (`sql/cache`, `ast/impl`)?
- Or is it a guard in one caller / service method / test-only workaround? Read sibling callers of
  the touched method; an unfixed sibling is `[BUG]` or `[RISK]` depending on reachability.
- Does it preserve the documented invariants in `AGENTS.md` (SQL type rendering, key-group order,
  save-returning shapes, cache admission, authorization)? Load
  `jimmer-dev-cache-internals` before judging cache changes.

## 3. Size and duplication

- Production diff proportional to the behaviour? Typical fix: tens of main lines, tests 2–3×
  larger. Large coherent API work is the exception, not accumulated cleanup.
- New parallel path where one exists? Look for a second eligibility prelude, a second slot/factory
  family, a copy of a renderer or a "keep both paths" flag. Compare the known debt in
  `project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/impl/query/ObjectCacheQueryExecution.java`
  (`tryExecute` at 71 and `tryExecuteWithContent` at 379, duplicated prelude at 91 and 390).
- God methods (> ~80 lines) and god classes (> ~600 lines) with mixed responsibilities: `[QUALITY]`
  unless the mixing hides a defect.
- God test classes: a 5000-line, 100-test class mixing raw JDBC, codecs, transactions and DTOs is
  `[QUALITY]`; layer-correct homes are `jimmer-core`, `jimmer-sql`, `jimmer-sql-kotlin`,
  `jimmer-ksp`, `jimmer-dto-compiler`.
- New bespoke helper where stdlib or existing infrastructure (`Keys.matchingKeyOf`, `KeyMatcher`,
  `FetcherUtil`, `ImmutableObjects`, `JoinFetchFieldVisitor.hasTableFields`, `ListUtils`) already
  covers it.

## 4. Upstream merge friction

Fork logic should sit in fork-owned classes. In upstream classes (`ConfigurableRootQueryImpl`,
`TypedQueryData`, `AbstractMutableStatementImpl`, `FetcherSelectionImpl`,
`ast/impl/EntitiesImpl`, `Dialect`), accept only narrow hooks: a widened guard, an extended
dispatch entry, a default method. Reject:

- broad signature changes or reordered parameters in shared classes;
- new public types whose only purpose is a fork-only branch;
- duplicated upstream logic copied instead of delegated.

Check that a hook cannot be expressed as an existing capability before accepting it.

## 5. Public API surface

- New public type/interface/method: is it a real contract with Javadoc stating semantics and
  exceptions, or an internal detail made public? An implementation type placed in an API package (outside `.impl`) is `[API]`.
- No silent no-op defaults on public interfaces; no configuration duplicating derivable model
  information; no option that only one implementation honours.
- Java and Kotlin APIs/docs move together. A Java-only public change with a Kotlin wrapper is
  `[API]` unless no Kotlin path exists.
- Removing a documented contract needs replacement coverage and a declared migration/version note
  in the same commit (`META-INF/jimmer/code_generation`, `gradle.properties`).

## 6. Java 8 in core

```bash
git diff <base>...HEAD -- 'project/jimmer-core/src/main' 'project/jimmer-sql/src/main' \
  'project/jimmer-apt/src/main' 'project/jimmer-ksp/src/main' 'project/jimmer-dto-compiler/src/main'
```

Reject `var`, records, `List.of`, `Map.of`, `Set.of`, `String.isBlank`, `Optional.isEmpty` and
other post-8 APIs there. Tests in those modules are also Java 8 unless the module says otherwise.
Only `project/jimmer-quarkus` targets 25.

## 7. Tests

- Ratio: test lines should be ≥ the production lines for behavioural fixes; a contract removal
  that reduces test lines is `[RISK]`.
- Placement: the module that owns the layer, the existing class/fixture, not a new test app.
- Assertions: SQL pinned verbatim via `ctx.sql`, variables via `ctx.variables`, rows via
  `ctx.rows` JSON; mutation cases through `executeAndExpectResult`/`executeAndExpectRowCount`.
- Error paths: combinatorial dimensions and exact line/column/message, source passed as the
  assertion message.
- Native: type-inference, temporal, JSON and dialect defects need a real-database reproduction.
  Verify whether the added native test actually ran; a skipped test is not coverage.
- Java↔Kotlin mirror exists when the Kotlin path exists; APT↔KSP counterpart for processor changes.

## 8. Commit hygiene

- Subject imperative, capitalised, no `feat:`/`fix:` prefix, ≤ ~72 chars, no trailing period,
  optional `(#issue)`.
- Body states what/why and the invariants kept, then a validation ledger with counts and the
  native databases **not** run. Missing ledger is `[QUALITY]`; when cache/DTO/dialect/transaction
  paths are touched, treat it as `[RISK]`.
- One behaviour per commit. Build/CI/tooling changes belong in a separate commit; a feature commit
  containing a workflow rewrite is `[QUALITY]`/`[RISK]`.
- Version/code-generation contract changes declared where they are needed.

## 9. Public-repo boundary

Grep the diff for private consumer/application names, packages, schemas, real source snippets and
secrets. Core changes and regressions must use generic Jimmer fixtures
(`jimmer-sql-test/jimmer-sql-test-model`, `-model-kotlin`). A leak is `[BUG]`/`[RISK]` and must be
reported without reproducing the private content further.

## 10. Dead code and superseded iterations

Look for leftover old branches, unused helpers, duplicated constants, commented-out code and
tests asserting a removed behaviour. Deleting an internal abstraction is fine only if its
coverage is preserved or replaced.

## Output

Group by severity, most severe first. One block per finding:

```
[BUG] project/jimmer-sql/src/main/java/.../File.java:123 — CONFIRMED
Evidence: <what the source does; cite the relevant lines/callers/tests>
Fix: <smallest correct change, in the owning layer>

[RISK] project/jimmer-sql/src/test/java/.../SomeTest.java:45 — SUSPECTED
Evidence: ...
Fix: ...
```

Severity:

- `[BUG]` — wrong behaviour, data loss, broken invariant, security/authorization gap.
- `[RISK]` — plausible failure under an untested path, missing native proof, merge hazard,
  contract removal without coverage.
- `[QUALITY]` — duplication, god method/class, dead code, unclear naming/shape.
- `[API]` — public surface, doc/parity, silent default, internal type exposed.

`CONFIRMED` means the source/diff or a run proves it. `SUSPECTED` means it needs a run or a
missing file; say what would confirm it. Never present a preference as a bug.

End with:

- **Verdict** — mergeable / mergeable with fixes / not mergeable, one sentence.
- **Coverage** — what you read, which commands you ran and their results, and what you did not
  run (native databases, Quarkus/Testcontainers, full suites). Do not claim unrun checks passed.
