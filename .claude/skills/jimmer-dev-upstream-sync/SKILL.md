---
name: jimmer-dev-upstream-sync
description: Merges upstream babyfish-ct/jimmer `dev` into the sleepkqq fork while keeping fork coordinates, Kotlin/KSP alignment and the fork's object-cache hooks intact. Use when the owner says "подтяни upstream", "синхронизируй с jimmer dev", "смержи апстрим", or asks to bring belovaf's latest commits into the fork. Not for sending fixes upstream, ordinary feature work, or cutting a release.
---

# Upstream sync

## HARD RULE: upstream is read-only

`babyfish-ct/jimmer` and `belovaf/jimmer` are read-only. `git fetch` is the only allowed
upstream operation. Never push, force-push, tag, open a PR, issue or review, comment, label,
or dispatch a workflow there, and never "fix" upstream so the merge gets easier. A fork is not
its parent: `sleepkqq/jimmer` authorises writes only to `sleepkqq/jimmer`, and only via `origin`.

## 1. Inspect

```bash
git remote -v                                   # upstream / belovaf / origin must all exist
git fetch upstream dev
git log --oneline main..upstream/dev            # the only commits this sync adds
git rev-parse --short=9 upstream/dev            # goes verbatim into the merge subject
git log --oneline -3 --merges                   # previous sync commits, for message shape
```

Skip the sync entirely when `git log --oneline main..upstream/dev` is empty.

## 2. Dry-run the conflict set

```bash
git merge-tree --write-tree --name-only main upstream/dev
```

Exit 0 with no file names means no textual conflict. That is **not** permission to merge
blindly: a clean run still takes upstream's side of every fork-owned file upstream touched.
List them with `git diff --name-only main...upstream/dev` and check each against step 4.

## 3. Merge on a branch

```bash
git switch -c sync/upstream-dev main
git merge upstream/dev
```

Subject copies the established convention (`da6a82f2f`), with the short sha of the merged tip:

```text
Merge upstream dev through <short-sha>

Preserve fork coordinates, Kotlin/KSP versions and Quarkus build compatibility. No release version change.
```

`6aa452cc8` used the default `Merge remote-tracking branch 'upstream/dev'`; prefer the
`through <short-sha>` form so the provenance is readable without `git log --parents`.

## 4. Fork-owned files upstream also edits

| File | What stays fork |
|---|---|
| `project/gradle.properties` | the fork `group=com.github.sleepkqq.jimmer` and the fork `version=` (read the current line, do not copy it from here). Upstream ships `group=org.babyfish.jimmer`, `version=0.12.4`; never take either. |
| `project/gradle/libs.versions.toml` | `ksp = "2.3.12"`, `kspTest = "0.14.0"` (FORK.md: KSP 2.3.12, compiler-testing 0.14.0). Upstream still carries `ksp = "2.1.20-2.0.0"`, `kspTest = "0.7.1"`. Take every other upstream catalog entry. |
| `project/buildSrc/build.gradle.kts` | `api("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")`. |
| `project/settings.gradle.kts` | the three `jimmer-quarkus:*` includes, their `projectDir` remaps, and the `quarkusLibs` catalog fed from `jimmer-quarkus/gradle/libs.versions.toml`. Add upstream's new modules alongside, never instead. |
| `project/buildSrc/src/main/kotlin/org/babyfish/jimmer/publish-convention.gradle.kts` | fork group, `https://github.com/sleepkqq/jimmer` url/scm. |
| `project/jimmer-quarkus/`, `.github/workflows/ci.yml`, `jitpack.yml`, `scripts/verify-publication.py`, `smoke-tests/`, `FORK.md`, `README.md`, `ai-toolkit/` | fork-only; upstream has no `ci.yml` at all. Conflicts here are always "keep fork". |

A Kotlin or KSP bump that upstream requires must be applied to **both** `buildSrc` (Kotlin
compiler) and `libs.versions.toml` (`ksp`, `kspTest`), plus `smoke-tests/build.gradle.kts`
which pins `2.4.21` / `2.3.12` itself. Never bump only one of the three.

## 5. Fork-modified upstream classes

Re-derive the list; do not trust a cached one:

```bash
git diff --stat upstream/dev...main -- project/jimmer-sql/src/main project/jimmer-core/src/main project/jimmer-sql-kotlin/src/main
```

Known carriers of fork hooks and the invariant each one holds:

| Class | Hook |
|---|---|
| `sql/ast/impl/query/ConfigurableRootQueryImpl` | `useObjectCache(boolean)` / `useObjectCache(Fetcher<?>)` |
| `sql/ast/impl/query/TypedQueryData` | `useObjectCache` carried through every copy-with-constructor |
| `sql/ast/impl/query/MergedTypedRootQueryImpl` | `hasContentCacheHint()` → `caches(CacheDisableConfig::disableAll)` + `FetcherUtil.withoutFetcherContext` |
| `sql/ast/impl/table/FetcherSelectionImpl` | keep the join for a `getJoinProp()` entity selection; descended-table discriminator guard |
| `sql/ast/impl/EntitiesImpl` | `forSqlClient` reader rebind, seed-checked `findMapByIdsForQuery`, logical-delete judgement on cached rows (`isLogicalDeleteFilterOnly`, `getCachedMap`) |
| `sql/loader/AbstractDataLoader` | `isObjectCacheContentOnly` isolation of the fresh subtree |
| `sql/cache/CachesImpl` | `CacheAdmissionPolicy`, `objectCacheContentOnlyTypes` |
| `sql/ast/impl/mutation/{Operator,Deleter,MiddleTableOperator}` | `MutationKeys.fire*` after each executed batch (BINLOG_ONLY own-write keys) |
| `sql/ast/impl/mutation/{MutableUpdateImpl,MutableDeleteImpl}` | `MutationKeys.listenerOf(sqlClient, type)` gate: id pre-select instead of direct render |

Fork-added siblings live in the same packages, so an upstream rewrite can orphan them without
a conflict: `CacheContentMask`, `ObjectCacheQueryExecution`, `ObjectCacheQuerySeed`,
`CacheAdmissionPolicy`, `CacheTypeMismatchException`, `runtime/MutationKeyListener` under
`project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/`. Also check
`project/jimmer-core` (`jackson/v2/NodeV2`, `jackson/v3/NodeV3`, `meta/impl/KeyMatcherImpl`)
and `sql/kt/ast/query/KConfigurableRootQuery.kt` /
`sql/kt/ast/query/impl/KConfigurableRootQueryImpl.kt` — Java/Kotlin parity means a Java hook
without its Kotlin delegate is a regression.

## 6. Conflict resolution rule

Keep upstream behaviour, then re-apply the fork hook minimally on top — widen an existing
guard, extend an existing dispatch, add a delegating line. Never delete a fork invariant to
make the merge compile, never keep "both paths" as a hedge, never restore a fork-side copy of
code upstream has since moved. Java 8 rules still apply in `jimmer-core`/`jimmer-sql`: no `var`,
records, `List.of`, `Map.of`.

## 7. Verify

Load the `jimmer-dev-cache-internals` skill before touching anything under `sql/cache`,
`EntitiesImpl`, the loaders or the Quarkus cache runtime — a merge in those files is a cache
change, not a mechanical sync.

```bash
cd project
./gradlew build                                          # same target as CI's build job
./gradlew :jimmer-sql:test --tests '*ModifiedFetcherTest' \
          --tests '*ModifiedAssociationFetcherTest' --tests '*SaveKeyPropsTest'
./gradlew :jimmer-apt:test :jimmer-ksp:test :jimmer-dto-compiler:test
./gradlew :jimmer-sql-kotlin:test --tests '*VirtualPredicateTest'
./gradlew :jimmer-quarkus:integration-tests:test
```

Native databases stay skipped unless `jimmer-sql-test-native-database` is set
(`env jimmer-sql-test-native-database=postgres ./gradlew :jimmer-sql:test --tests '…'`);
report them as skipped, never as passed. CI (`.github/workflows/ci.yml`) runs
`bash ./gradlew --no-build-cache --no-parallel --max-workers=2 build` with the MySQL
compose service from `jimmer-sql/src/test/resources/docker-compose.yml` and the PostgreSQL
fixture from `jimmer-sql/src/test/resources/database-postgres.sql`.

Post-merge invariant check — the fork delta must still be visible against the new tip:

```bash
git diff upstream/dev -- project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/ast/impl/query/ConfigurableRootQueryImpl.java | head -40
```

## 8. Record and stop

Record the sync the way releases do in `FORK.md`: either the Layout bullet
(`Release 1.0.2 merges upstream dev through 4e49a9108, including …`) or the release note that
ships it. A sync with no release gets the sha in the merge body only, as `da6a82f2f` did.

Commit, push, tag and release need an explicit owner request; pushes go to `origin`
(`sleepkqq/jimmer`) only. Report the merged sha range, every fork-owned file you had to
re-resolve, the suites you ran and everything skipped.
