# Fork maintenance and migration

## Layout and provenance

- `project/` remains the upstream Gradle build (JDK 25, Gradle 9.8.2); core modules target Java 8, the Quarkus extension Java 25.
- `project/jimmer-quarkus/{runtime,deployment,integration-tests}` contains the extension.
- Extension history was imported with `git subtree add` **without squashing**, from
  `sleepkqq/quarkus-jimmer-extension` after release `1.14.1`.
- `ai-toolkit/` holds the agent skills, docs MCP server and skill evaluations, imported the
  same way from the archived `sleepkqq/jimmer-ai-toolkit`. Skills track the latest fork
  release; install them with `ai-toolkit/install.sh --tool claude --symlink`.
- Release `1.0.1` starts from upstream `dev`, commit `7c1d302b0`, including belovaf's
  single-table inheritance binlog fix and preceding save/upsert changes.
- Release `1.0.2` merges upstream `dev` through `4e49a9108`, including the save-returning
  key normalization from #1522, match-by-key and LIKE fixes, and keeps JOIN separators
  when FK-only joins are eliminated.
- Release `1.0.3` preserves temporal SQL types in update-returning `VALUES` sources,
  including nullable timestamps and the distinction between local times and instants.
- Release `1.0.4` preserves nullable JSON types in update-returning `VALUES` sources.
  The renderer prefers column metadata and falls back to `Dialect.getJsonSqlType()`
  (`jsonb` for PostgreSQL), since the JDBC `PGobject` class alone is not a SQL type name.
- Kotlin 2.4.21, KSP 2.3.12 and compiler-testing 0.14.0 are aligned across the build.
- All published modules, including the Quarkus runtime/deployment pair and BOM, share
  `com.github.sleepkqq.jimmer` and the version in `project/gradle.properties`.
- The repository remains a GitHub fork of `babyfish-ct/jimmer`; upstream history and
  existing fork branches remain available.

## Migrate a consumer

### Connection-free cache hits and own-write eviction in 1.2.0

No configuration changes; behaviour follows from the existing cache and trigger settings.

- The Quarkus connection manager acquires a pooled connection only at the first real JDBC
  call. A read fully served by the object cache, inside or outside a JTA transaction, takes no
  connection and enlists nothing. Pool-exhaustion errors now surface from the first statement
  instead of the `execute` entry.
- `findById`/`findByIds` (entity, fetcher or DTO view) of a type whose only filter is the
  built-in logical-delete filter judge visibility on the cached row; a warm read issues no SQL.
  User, reversed, ignored or sharding filters, locking reads and content-only types keep the
  SQL visibility check.
- `BINLOG_ONLY` clients evict the object and owning-side association cache entries of their own
  inserts, updates, upserts, deletes and middle-table changes after commit (rollback evicts
  nothing), so the writing instance reads its own committed writes without waiting for CDC.
  Bulk update/delete of a cached type pre-selects target ids once; types without caches keep
  their previous SQL. Inverse-side association caches, and saved rows whose ids the database
  does not return (for example MySQL batch upserts by key), still rely on CDC.
- Automatic DTO/View admission that cannot be applied falls back to the ordinary cached path
  instead of a cache-disabled execution. `CacheTypeMismatchException` moved to an internal
  package.

The release also targets Java 25 for the Quarkus extension (core modules stay on Java 8) and
moves to Quarkus 3.40.1.1, Kotlin 2.4.21 and Gradle 9.8.2. Release 1.2.0 includes these
changes; 1.1.14 does not.

### Non-nullable key-group preference in 1.1.10

When multiple key groups are loaded, matching prefers a group without nullable
properties before nullable alternatives, then the group with more properties.
Entity and property-set matching use the same metadata-based ordering. This avoids
collapsing distinct batch rows or reporting false conflicts on an optional null key
when a complete non-nullable key is available. A nullable group remains eligible
when no non-nullable group is loaded; explicit key configuration is unchanged.
The existing `MatchByKeyTest` covers new and existing rows, entity/shape agreement
and nullable-only fallback with the shared anonymized `NullableKeyUser` fixture.
This correction is new in `1.1.10`; `1.1.9` does not include it.

### Optional object-cache query hydration in 1.1.0

`useObjectCache()` is an explicit, off-by-default hint on Java and Kotlin root queries.
It is a content-cache hint, not a query-result or statement-snapshot cache: SQL remains
authoritative for membership, join topology, ordering, duplicates, pagination and scalar
slots, while the shared object cache only supplies eventual entity content for the rows
SQL already selected. Supported entity slots project ID-only skeletons and use the existing
object-cache loader for cold entries. Cached slots are checked against fresh concrete SQL
types; unresolved missing, negative or incompatible values fall back to the whole original
query rather than a shortened page. Mixed cached/fresh rows, named entity tuples and root
single-table polymorphic results are supported conservatively. Locking reads
(`forUpdate`), command and aggregate reads, streaming and count queries are unchanged and
never use the hint, and ordinary derived or association queries still determine membership
by SQL rather than by a cached query result.

The hint is honored only with positive proof that the connection is owned by the executing
manager scope and is not in a transaction: external, mismatched or unknown connections,
active or rollback-only transactions and locking reads are denied. Hinted root-query
execution therefore does not hydrate uncommitted roots from, or publish them to, shared
object caches. Custom cache factories still own their existing transaction guards.

1.1.0 also fixes shared numerical CDC decoding: JSON node casting for `BigInteger` and
`BigDecimal` now preserves the full value instead of truncating through an int cast, so
CDC/binlog consumers decode numeric columns correctly in both Jackson v2 and v3 nodes.

In Quarkus, the built-in local and Redis `CacheFactory` producers are now `@DefaultBean`,
so an application-provided `CacheFactory` bean wins instead of making resolution ambiguous;
the built-in producers remain available when no application factory exists.

### Authenticated query-cache hydration in 1.1.1

Because the hinted base SQL is itself filtered, the id-only skeleton already proves page
membership. Unlike 1.1.0, this correction avoids repeating the per-id visibility query
for those ids. That shortcut requires an opaque, internally minted proof bound to the exact
executing client, connection, manager and id/concrete-type group, and it is re-validated
before any cache access; the caller-supplied group is snapshotted into an immutable copy
before that validation and only the snapshot is used afterwards, so a hostile ids iterable
that mutates the caller's map during iteration cannot widen the admitted group. The public
raw map read and every ordinary filtered ID read keep their existing visibility check, so a
shared cache cannot leak an id the current filter hides.

### Delegated-client query-cache hydration in 1.1.2

A delegating `JSqlClient` (for example the Quarkus default client wrapped by a `KSqlClient`)
returns the underlying entities reader from `getEntities()`. The 1.1.1 authenticated-hydration
seed is minted for the delegate, so hydration compares the seed client against that delegate.
Hydration now rebinds the reader to the query's executing client (`forSqlClient`) before the
seed is validated; otherwise that check fails for delegated clients and every hinted root query
silently falls back to the full projection, re-reading wide columns instead of using the
declared object cache. 1.1.2 keeps the existing seed purpose, manager-inactivity, locking and
visibility re-validation, and only corrects the reader identity. A delegated Java or Kotlin root
query over a mixed tuple can therefore serve the cached entity body while SQL still supplies the
scalar slots; a cache miss or a declined hint still falls back to the whole original query.

### Explicit cached-content masks in 1.1.3

`useObjectCache(Fetcher<?> cachedContent)` is an additional, off-by-default root-query hint
that names exactly which recursive content may be served from the object cache. The argument
is a path-specific whitelist of stored scalar, embedded and JVM-formula dependency leaves;
association entries navigate into child content and never authorize caching the edge itself.
Every other selected value, the foreign-key topology, membership, ordering, pagination and
concrete type stay fresh SQL, so only caller-approved display leaves can be eventually stale.
A JVM formula is recomputed by its getter in the current context rather than cached as a
computed value, and fresh SQL wins any overlap. Fetching `BookStoreFetcher.$.name().website()`
under `useObjectCache(BookStoreFetcher.$.name())`, for example, permits caching the store
name while `website` and row membership remain SQL-authoritative. Protected version and
logical-delete properties cannot be approved. The boolean and no-argument hints keep their
existing semantics.

The mask must select a subset of the query's fetcher. Collection associations, id-views,
remote or non-stored properties, SQL formulas and protected version/logical-delete properties
are rejected. Recursive projections, masked field-local filters and reductions that cannot
prove target admission conservatively decline the optimization. Unsupported shapes, an
unproven transaction state or missing/incompatible cached values run the complete original
projection once on a cache-disabled client. This fresh-graph policy also applies to locking,
command, reselected, streaming and `forEach` reads. Cached content is eventual, not a statement
snapshot, and the caller owns which content is acceptable to serve stale.

### Native cache-mask corrections in 1.1.4

1.1.4 extends cached-content masks to native single-table `forType` branches while
preserving native polymorphic DTO conversion. SQL remains authoritative for concrete
types and unapproved values. Existing decline conditions and whole-query fallback remain
unchanged. Collections cannot be approved by a mask; unapproved collections stay SQL-fresh.
Fetchers without the native metadata required for reduction use the original fresh read.

The `1.1.3` tag (`a021104`) is immutable; its JitPack build failed before compilation on an
external Maven Central HTTP 429. `1.1.4` is available as the immutable annotated tag
(`9018a92`, peeled [`80be789`](https://github.com/sleepkqq/jimmer/commit/80be789348b9b22b532eca0651b4c26934dbb061)):
[fork CI](https://github.com/sleepkqq/jimmer/actions/runs/37553186767) and the
[anonymous public-release verification](https://github.com/sleepkqq/jimmer/actions/runs/37554211005)
both passed on that exact commit,
confirming every published POM, Gradle module metadata file and the Quarkus deployment
descriptor, and the public smoke consumer builds from JitPack alone.

### Concrete-carrier cache admission in 1.1.5

Cache admission is per fresh concrete carrier, not global across every mask branch: load a
payload only when that carrier's applicable approved leaves are absent from retained SQL.
Other concrete rows remain fully SQL-fresh and need no cache, while traversal still reaches
their independently masked navigated children. Missing required cache content continues to
decline to the complete original query. The mixed-organization/person regression verifies
that a branch with no applicable missing content does not require a cache entry; it passed
with the full 1.1.5 CI run.

The immutable annotated `1.1.5` tag (`34068a2`, peeled
[`0846dc0`](https://github.com/sleepkqq/jimmer/commit/0846dc0b12b2a36a913436adc5caa98e38e35fec))
is available. [Fork CI](https://github.com/sleepkqq/jimmer/actions/runs/37594479986)
passed on the exact commit, including the full build, object-cache tests, native PostgreSQL,
JTA and isolated tests; 139 targeted tests completed with no failures or skips. The anonymous
[public-release verification](https://github.com/sleepkqq/jimmer/actions/runs/37595457359)
also succeeded on its second attempt on the exact commit: it verified the 1.1.5 POMs,
Gradle module metadata and Quarkus deployment descriptor, and the anonymous JitPack-only
smoke consumer built successfully.

### Declarative content-only caches in 1.1.6

An entity's built-in Quarkus cache can opt into `content-only: true`. Ordinary entity,
generated DTO and association reads then use fresh SQL; only explicit cached-content masks
may hydrate approved leaves from that object cache. The policy follows native inheritance
metadata and remains attached to derived clients. Its default is false, preserving legacy
cache behavior. Custom factories expose the same policy through `CacheFactory`.

Association loading isolates the entire fresh subtree from an ambient cache-enabled fetcher
context, including deeper children. Explicit masks in merged set queries conservatively
decline to the complete fresh graph, including nested and non-first operands and JTA reads.
Neither change introduces query-result caching or permits transaction-time cache fills.

The immutable `1.1.6` tag points to
[`aee7d33`](https://github.com/sleepkqq/jimmer/commit/aee7d3381738773406430b2fdbe5aafa731aa8b9).
[Source CI](https://github.com/sleepkqq/jimmer/actions/runs/37673336571) and
[public-release verification](https://github.com/sleepkqq/jimmer/actions/runs/37674851282)
passed, including 162 targeted object-cache, Kotlin and Quarkus cache-policy/JTA tests
without failures or skips, native PostgreSQL regressions and the full build.
The anonymous publication check and JitPack-only consumer build passed on the second
attempt after an initial network timeout fetching a POM; no source or tag was changed.

### Configured content fields in 1.1.7

> Superseded: per-field cache configuration was removed after 1.1.11. See
> "Projection-derived content admission, no field lists" below. This section records
> what the 1.1.7 tag did; `content-fields` no longer exists in the fork.

`content-only` entity caches can declare `content-fields` as a list of approved stored
scalar properties. Ordinary `useObjectCache()` then uses that native policy with an
existing DTO Language projection; consumers do not need handwritten fetchers or parallel
cache DTOs. Single-table subtype fields become concrete-type branches. Unknown fields,
associations, formulas, ID views, identity/version/deletion/discriminator metadata and a
field list without `content-only` are rejected during client construction.

The no-argument/boolean hint replaces any ad hoc mask with the configured policy;
`useObjectCache(false)` clears it. Ordinary entity/DTO and association reads remain fresh.
Membership, order, scalar slots and unapproved projected leaves remain SQL-authoritative.
This configuration does not relax the manager-owned inactive-transaction proof: active,
unknown, locking and command reads still use the complete fresh graph.

The immutable `1.1.7` tag points to
[`ab22b4a`](https://github.com/sleepkqq/jimmer/commit/ab22b4a58b44bd7868d950725e9f2bde1f648efc).
[Source CI](https://github.com/sleepkqq/jimmer/actions/runs/37687642176) and
[public-release verification](https://github.com/sleepkqq/jimmer/actions/runs/37689501168)
passed, including the configured-field, DTO/tuple and polymorphic projection regressions,
native PostgreSQL cases and Quarkus cache-policy/JTA tests. The anonymous artifact and
isolated consumer checks passed on the second attempt after a POM fetch timeout;
the source and tag were unchanged.

### Configured policy projection matching in 1.1.8

> Superseded by the same removal after 1.1.11: admission is now derived from the
> projection, so there is no configured policy to intersect.

Configured `content-fields` approve eligible leaves rather than requiring every DTO
to select the full list. The boolean/no-argument hint resolves the policy at execution
and intersects it with each native selected projection, including concrete-type branches.
Scalar-only, ID-only and unsupported projections stay fresh without cache access.
Explicit Fetcher masks still reject approved content absent from their projection.
Transaction, visibility, whole-query fallback and complete entity-cache loading remain unchanged.
The immutable `1.1.8` tag points to
[`23e747943`](https://github.com/sleepkqq/jimmer/commit/23e7479439a3d771a4ef65065dadd7d77fc7631d).
[Source CI](https://github.com/sleepkqq/jimmer/actions/runs/37701909708) and
[public-release verification](https://github.com/sleepkqq/jimmer/actions/runs/37702666113)
passed, including 130 targeted Java/Kotlin cache tests without failures or skips,
native PostgreSQL cases, Quarkus cache-policy/JTA tests and the full build.
The anonymous artifacts and isolated public consumer passed on the second attempt
after a POM fetch timeout; the source and tag were unchanged.

### Native ordinary-query admission in 1.1.11

An ordinary, hint-free root query whose projection contains a native generated DTO/View
slot for an entity with a declared object cache is admitted without any per-query
`useObjectCache()` call and without any service-side field list or cache wrapper. A
complete-snapshot cache (the default, `content-only: false`) serves the projection through
the existing plain object-cache path. In the `1.1.11` tag a `content-only` cache serves the
leaves named in its `content-fields` list through the existing content path, and a
`content-only` declaration without that list admits nothing; the field list was removed
after `1.1.11`, as described below.
Membership, order, pagination, visibility, global filters and every non-approved scalar
slot stay SQL-authoritative, and the whole projection falls back to the complete fresh
graph whenever the cache cannot satisfy the page.

The admission reuses every existing guard: QUERY purpose, a non-locking read and a
connection with a proven inactive transaction; cacheable slots only (no effective join
fetch, no JOINED-inheritance root). An explicit `useObjectCache(false)` always wins.
Entity projections keep the documented explicit opt-in, so existing fresh-SQL entity reads
are unchanged; streaming and count queries remain hint-only. Native ID lookups
(`findById`/`findByIds`/`findMapByIds`) are also unchanged: they keep their existing
complete-snapshot-or-fresh behavior and are not re-admitted by this policy. No
query-result caching is introduced and no association edge or authorization fact is
served from the cache.

### Projection-derived content admission, no field lists in 1.1.12

`content-fields` is removed from the fork. A per-field cache list duplicated the
projection in configuration: a property added to an entity or DTO later would be
missing from the list until someone remembered to edit YAML, so some leaves came from
the cache and others from SQL for the same projection. Admission is now derived from
the executed projection itself: for an entity with a declared object cache and a
`content-only` policy, the admitted mask is that projection's stored scalar leaves,
recomputed on every execution. Newly projected stored properties participate
automatically; id, discriminator, version, logical deletion, formulas, associations,
views, remote properties and non-stored properties are never admitted, so protected
and authorization-relevant facts stay SQL-authoritative.

`content-only: true` remains the opt-in that enables derived admission and keeps bare
entity and ID reads and association loading SQL-backed; a converted DTO/View projection
is admitted, so a DTO read that must observe the current row needs an explicit
`useObjectCache(false)`. Existing guards are unchanged: QUERY purpose, a
non-locking read, a proven inactive transaction, cacheable slots only, whole-query
fallback on any missing, negative or incompatible value, and SQL authority over
membership, join topology, order, duplicates, pagination, global filters and
visibility. Explicit Fetcher masks and `useObjectCache(false)` keep their meaning.

A bare `useObjectCache()` keeps its legacy execution for ordinary caches: only a
`content-only` declaration opts that projection into derived admission, so adding a DTO
sibling never stops an ordinary entity slot from using its existing cache. When a bare
hint cannot be honored, the complete original projection runs on the isolated
cache-disabled graph rather than falling back to SELECT-loaded associations on the
ordinary client, so a declined hint cannot expose an unrelated association cache.

Migration (only when moving to a release that includes this change: `1.1.11` and
earlier still require `content-fields`, and a `content-only` declaration without that
list admits nothing there; `1.1.12` and later have no such key): delete `content-fields`
from every cache declaration; no
service mask, hint or fetch-plan change is required, because the projection now supplies
the mask.

Foreign content is cached as a whole-projection snapshot and is expected to be refreshed
by cache invalidation -- for example a CDC stream that evicts the changed row -- rather
than by splitting one projection into cached display fields and SQL-fresh ones. The next
read after an invalidation reloads the complete current row, so freshness of a foreign
display field is bounded by the invalidation propagation interval, not by a per-field
policy; a deliberately restored old snapshot in a test does not establish a
selective-freshness requirement.

Admission follows projection metadata and execution guards, not application intent. A
converted DTO/View projection is admitted, and can therefore return cached content,
whether the caller treats it as a display or an edit form. Only command-purpose
executions, locking reads, an explicit `useObjectCache(false)` and bare entity
projections stay on the SQL path, together with version, authorization, membership,
ordering, pagination and transaction guards. A read that must observe the current row
uses a bare entity projection or an explicit `useObjectCache(false)`.

### Missing targets in ordinary association caches in 1.1.9

An ordinary cached to-many ID snapshot can outlive a deleted or filtered target.
Association assembly now omits absent targets instead of putting null into the child
collection. Valid children retain their order and duplicates; an all-missing snapshot
produces an empty collection. This matches the existing to-one map join behavior.
Explicit query-cache hydration, SQL-authoritative membership and whole-query fallback
remain unchanged. The existing Book/BookStore regression covers mixed and all-missing
targets, including warm negative object-cache entries, without changing the cache factory.
The immutable `1.1.9` tag points to
`3ccf22aa1896094527e3b6961f15eedb56bb77bb`.
[Source CI](https://github.com/sleepkqq/jimmer/actions/runs/37713528106) passed 137 cache,
35 Quarkus/JTA and 4 native PostgreSQL tests, plus the full build and isolated consumer.
[Anonymous public verification](https://github.com/sleepkqq/jimmer/actions/runs/37714362547)
passed for every artifact and the public-only consumer after retrying only a POM read timeout;
the source and tag were unchanged.

### Reference IDs in bulk update-returning in 1.0.7

Bulk `UPDATE ... RETURNING` accepts raw owning-reference ID projections such as
`getAssociatedId("store")` and generated Kotlin ID-view properties. Ownership and
joined-inheritance validation use the physical FK property on the updated table;
the existing renderer and readers retain nullable reference semantics. Inverse
references and properties requiring a target-table join remain unsupported.
`FluentDMLTest` covers changed/non-null and cleared/null references on H2 and
PostgreSQL, plus rejection of joined and inverse projections. CI enables the canonical
PostgreSQL and MySQL fixtures in the normal full test suite.

### Tenant filters and multi-view caches in 1.0.6

- Pagination/count preparation now applies global filters to retained projection subqueries
  before freezing their shared mutable query.
- Root reads by ID recheck current visibility before using single-view object caches. A warm
  filtered root read therefore retains one ID-only SQL query; `forUpdate` bypasses the cache.
- Parameterized chains retain their filter parameters between L1 and L2. Quarkus declarative
  factories select multi-view association caches using native filter metadata, including
  LOCAL_ONLY invalidation publication. Zero local capacity disables L1; capacity nine no longer does.

Applications still own cacheable-filter parameters, dependency invalidation and CDC capture.
These fixes do not make arbitrary permission or tenancy-dependent data safe to cache automatically.
Regression coverage includes `GlobalFilterTest`, `FilterCacheTest` and `CacheModeReadTest`.

### Save-result correction in 1.0.5

Save-result matching distinguishes a null child fetcher (an ID-only reference) from a
null root fetcher (upsert-mask result shape). Residual fetching excludes satisfied
to-one ID views, while APPEND collections still require their stored snapshot.
ID-only residual seeds include a requested discriminator when the concrete subtype is known,
including enum discriminators. This avoids falling back to a full polymorphic reread.
Regression coverage lives in `ModifiedAssociationFetcherTest` and
`SingleTableInheritanceMutationTest`; rejected conditional saves retain their unmaterialized result.
Release 1.0.5 includes this correction; 1.0.4 does not.

### Coordinates

For version `1.2.0`, replace
`com.github.sleepkqq.quarkus-jimmer-extension:quarkus-jimmer:1.14.1` with
`com.github.sleepkqq.jimmer:quarkus-jimmer:1.2.0`. Replace every direct
`org.babyfish.jimmer:*` dependency with `com.github.sleepkqq.jimmer:*:1.2.0`, including
`jimmer-apt`, `jimmer-ksp` and `jimmer-bom`. Keep Maven Central and add
`https://jitpack.io`; no credentials or tokens are required. Packages and configuration
keys are unchanged. Kotlin consumers use the KSP plugin compatible with their compiler;
compatibility is tested with Kotlin 2.4.21 and KSP 2.3.12. Verify the tagged artifacts and
the public-only consumer against anonymous JitPack before publishing the GitHub release.
A tag alone does not establish artifact availability.

The previous extension repository and tags are retained for existing consumers. Its
archive is a maintenance handoff, not an artifact relocation or deletion.

## Merge upstream

```bash
git remote add upstream https://github.com/babyfish-ct/jimmer.git # once, if absent
git fetch upstream dev
git switch -c sync/upstream-dev main
git merge upstream/dev
cd project
./gradlew build
```

Keep fork publication coordinates, the fork version, Quarkus includes/catalog and
JitPack configuration when resolving build-file conflicts. Upstream source directories
retain their original layout, so normal Git merges apply. Review the merged changes and
merge the tested sync branch into `main`.

## Verify and release

Docker is required for the PostgreSQL/Redis integration tests.

Run normal CI in GitHub before pushing a release tag. The existing CI workflow
has an opt-in `public-release-verify` job, skipped by default, that verifies the anonymous
public artifacts before any GitHub release: it requires the dispatched version to equal
`project/gradle.properties` at the ref, runs the anonymous verifier against
`https://jitpack.io`, builds the default public smoke consumer, and always uploads the log
and test XML. Dispatch it after pushing the tag:

```bash
gh workflow run ci.yml --ref 1.1.4 -f publicReleaseVersion=1.1.4
```

For each release, update the fork version and consumer snippets, pass CI, commit and
push `main`, then create and push an immutable semver tag (for example `1.1.4`).
JitPack's root `jitpack.yml` runs `publishToMavenLocal` from `project/` without signing.
Request the tagged POM to trigger the public build, then check
`https://jitpack.io/com/github/sleepkqq/jimmer/1.1.4/build.log` and run:

```bash
python3 scripts/verify-publication.py https://jitpack.io 1.1.4
project/gradlew -p smoke-tests clean build -PforkVersion=1.1.4 --refresh-dependencies
```

The same verification remains reproducible against an isolated repository as a generic
operator reference:

```bash
project/gradlew -p project build
project/gradlew -p project publishToMavenLocal -Dmaven.repo.local=/tmp/jimmer-m2
python3 scripts/verify-publication.py /tmp/jimmer-m2 1.1.4
project/gradlew -p smoke-tests build -PforkRepository=file:///tmp/jimmer-m2 -PforkVersion=1.1.4
```

`smoke-tests` is a separate Gradle build: it reuses the integration-test sources but
resolves runtime, deployment, APT and KSP from the selected Maven repository, with no
project dependencies, composite build or `mavenLocal()` fallback. The verifier checks
every published POM and Gradle module metadata file, plus the Quarkus deployment descriptor.

The final consumer check intentionally uses anonymous JitPack access. Create the GitHub
release only after JitPack succeeds; a tag alone does not prove the artifacts are available.
