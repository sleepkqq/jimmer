---
name: jimmer-dev-release
description: Cuts a sleepkqq/jimmer fork release - one shared version across every published artifact, FORK.md migration notes, isolated publish verification and JitPack/smoke-consumer checks. Use when the owner says "выпусти релиз форка", "подними версию", "релиз 1.1.x", or asks to publish the fork to JitPack. Not for merging upstream `dev` (jimmer-dev-upstream-sync) or for consumer-side migration advice (ai-toolkit skills).
---

# Fork release

## Read before changing anything

`FORK.md` (Layout, Migrate a consumer, Coordinates, Verify and release), `project/gradle.properties`,
`project/buildSrc/src/main/kotlin/org/babyfish/jimmer/publish-convention.gradle.kts`, `jitpack.yml`,
`scripts/verify-publication.py`, `.github/workflows/ci.yml`, and the last release commit:

```bash
git log --oneline --grep='bump fork version' -5   # 047055c84 bumped 1.1.13 -> 1.1.14
git show --stat 047055c84
```

`047055c84` touched exactly two files — `project/gradle.properties` and `FORK.md`. That is the
whole mechanical size of a normal release; everything below is verification, not extra code.

## 1. Bump the version in one place

`project/gradle.properties` is the only version source in the build:

```bash
grep -rn "version=" project/gradle.properties
grep -rn "1\.1\." project --include='*.kts' --include='*.properties' --include='*.toml'
```

The second command must return only `project/gradle.properties:version=…`. If a literal version
appears in a build file, that is a bug in the release, not a second place to edit.

Every module publishes through `publish-convention`, which sets
`coordinates(project.group, project.name, project.version)`; `jimmer-bom` constrains the other
fifteen modules, so one edit aligns all of them. Group stays `com.github.sleepkqq.jimmer`.

Published artifacts `scripts/verify-publication.py` will demand at that version:

`jimmer-bom`, `jimmer-core`, `jimmer-core-kotlin`, `jimmer-apt`, `jimmer-ksp`, `jimmer-sql`,
`jimmer-sql-kotlin`, `jimmer-client`, `jimmer-client-swagger`, `jimmer-client-scalar`,
`jimmer-dto-compiler`, `jimmer-ddl-compiler`, `jimmer-mapstruct-apt`,
`jimmer-spring-boot-starter`, `quarkus-jimmer`, `quarkus-jimmer-deployment`.

## 2. Documentation the release must move

| File | What changes |
|---|---|
| `FORK.md` → `### Coordinates` | the consumer snippet version (three occurrences, as in `047055c84`). |
| `FORK.md` → `## Migrate a consumer` | a new `### <behaviour> in <version>` section per user-visible change: what changed, the invariant that survives, and an explicit boundary sentence such as `This correction is new in 1.1.10; 1.1.9 does not include it.` or `Release 1.0.5 includes this correction; 1.0.4 does not.` When a previous option is gone, mark the old section `> Superseded: …` instead of deleting the history (see the 1.1.7/1.1.8 blocks). |
| `project/jimmer-quarkus/README.md` | one row in the `<!-- versions:start -->` compatibility table (Extension, Quarkus, Jimmer, Kotlin, JDK). It is behind today — it still lists `1.1.4` and `1.1.2` — so add the row rather than assuming it self-updates. |
| `README.md` | the `## sleepkqq fork` section. It is stale at `1.1.6`: version, the upstream `dev` commit it is based on, the immutable-tag sentence and the three dependency snippets all name it. |

`ai-toolkit/` has no version of its own: it always describes the fork's current state and ships
with every release. Move its "current release" statements and snippets to the new version, document
every user-visible change in the matching consumer skill, correct advice the change contradicts,
and add a refresh entry to `ai-toolkit/docs/skill-refresh.md`, in the same release.

Place a new `###` release section directly under `## Migrate a consumer`, newest first (`1.1.10`
currently sits at the top), and keep `### Coordinates` as the last consumer-side section.

A contract change is declared in the same commit that needs it: a code-generation format bump
also updates `project/jimmer-core/src/main/resources/META-INF/jimmer/code_generation` and says
"existing models require regeneration".

## 3. Verify before any tag exists

```bash
cd project && ./gradlew build            # matches CI's build job
cd ..

# isolated repository, no mavenLocal() fallback
repo=$(mktemp -d)
project/gradlew -p project --no-parallel --max-workers=2 publishToMavenLocal \
        -Dmaven.repo.local="$repo"
version=$(sed -n 's/^version=//p' project/gradle.properties)
python3 scripts/verify-publication.py "$repo" "$version"

# consumer that resolves runtime, deployment, APT and KSP only from that repository
project/gradlew -p smoke-tests clean build \
        -PforkRepository="file://$repo" -PforkVersion="$version"
```

`verify-publication.py` checks every POM (group, artifact, version, no `org.babyfish.jimmer`
dependency, fork dependencies pinned to the same version), every Gradle module metadata file, and
the `META-INF/quarkus-extension.properties` `deployment-artifact` pointer. A green
`publishToMavenLocal` alone proves nothing.

`smoke-tests` is a separate Gradle build with `exclusiveContent` limited to
`com.github.sleepkqq.jimmer` and no project dependencies, composite build or `mavenLocal()`
fallback — that is why it catches transitive drift.

## 4. Consumer-side check: one version for runtime **and** processors

A catalog's SQL version does not establish the extension's transitive version. Inspect the
resolved graphs, not the declared strings:

```bash
project/gradlew -p smoke-tests dependencies --configuration runtimeClasspath -PforkVersion="$version" | grep -i jimmer
project/gradlew -p smoke-tests dependencies --configuration annotationProcessor -PforkVersion="$version" | grep -i jimmer
project/gradlew -p smoke-tests dependencies --configuration kspTest -PforkVersion="$version" | grep -i jimmer
```

The result must show `com.github.sleepkqq.jimmer:*:$version` for runtime, `jimmer-apt` and
`jimmer-ksp`, and no `org.babyfish.jimmer` line at all. Mixed fork/upstream artifacts, or two
fork versions, block the release. Quarkus consumers additionally need the Quarkus platform
matched to the extension's compatibility row, and a KSP plugin matched to the Kotlin compiler
(`FORK.md`: Kotlin 2.4.21, KSP 2.3.12; `project/gradle/libs.versions.toml` and
`project/buildSrc/build.gradle.kts` hold the real values).

## 5. Publish: explicit owner authorization only

Commit, push, tag and GitHub release each need the owner's explicit request. Never push or tag
`babyfish-ct/jimmer` or `belovaf/jimmer`; `origin` (`sleepkqq/jimmer`) is the only remote.

```bash
git push origin main
git tag -a 1.1.15 -m "1.1.15: <one-line behaviour summary>"   # next version; annotated, immutable
git push origin 1.1.15
```

Existing tags carry `<version>: <summary>` (`1.1.13: admit converted object-cache slots by
application policy`); 1.1.11/1.1.12 used ad-hoc subjects. Commit subject: follow `AGENTS.md`
(imperative, no prefix) and keep the bump separate from feature commits.

`jitpack.yml` installs the JDK and runs `cd project && ./gradlew publishToMavenLocal
-Pversion=$VERSION --no-daemon`, without signing. After pushing the tag, dispatch the opt-in
verification, which refuses to run unless the input equals `project/gradle.properties` at that ref:

```bash
gh workflow run ci.yml --ref 1.1.15 -f publicReleaseVersion=1.1.15
```

CI jobs to expect: `build` (full `./gradlew build`, MySQL compose fixture plus the PostgreSQL
fixture), `publish-smoke` (isolated repository + verifier + smoke consumer), and
`public-release-verify` (anonymous `python3 scripts/verify-publication.py https://jitpack.io
"$PUBLIC_RELEASE_VERSION"` then `project/gradlew -p smoke-tests clean build
-PforkVersion=… --refresh-dependencies`).

## 6. JitPack and the GitHub release

```bash
curl -fsS https://jitpack.io/com/github/sleepkqq/jimmer/1.1.15/build.log | tail -40
python3 scripts/verify-publication.py https://jitpack.io 1.1.15
project/gradlew -p smoke-tests clean build -PforkVersion=1.1.15 --refresh-dependencies
```

JitPack rewrites component identity to the repository root, which is why the verifier expects
`("com.github.sleepkqq", "jimmer")` for that repository only. Create the GitHub release **after**
JitPack succeeds — a tag alone does not prove the artifacts exist.

## 7. Failure handling

JitPack resolves Maven Central through `https://maven-central.storage.googleapis.com/maven2/`,
which lags repo1 by days. Before tagging, check that every dependency version bumped since the
last release has its POM on that mirror; `1.2.0` failed on `swagger-ui` and `scalar-core`
released the same day, and `1.2.1` pinned the previous versions.

Do not move or delete a published tag. `1.1.3` failed before compilation on an external Maven
Central HTTP 429, the tag stayed immutable, and `1.1.4` carried the fix. A transiently failed
JitPack build (POM fetch timeout) is retried without touching source or tag; a real defect gets
the next patch version. Record the outcome — passing or failed, which suites, which databases
were skipped — in the release note, as the 1.1.5/1.1.6/1.1.9 entries do.
