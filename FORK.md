# Fork maintenance and migration

## Layout and provenance

- `project/` remains the upstream Gradle build (JDK 21, Gradle 9.7.1).
- `project/jimmer-quarkus/{runtime,deployment,integration-tests}` contains the extension.
- Extension history was imported with `git subtree add` **without squashing**, from
  `sleepkqq/quarkus-jimmer-extension` after release `1.14.1`.
- Release `1.0.0` starts from upstream `dev`, commit `7c1d302b0`, including belovaf's
  single-table inheritance binlog fix and preceding save/upsert changes.
- Kotlin 2.4.20, KSP 2.3.12 and compiler-testing 0.14.0 are aligned across the build.
- All published modules, including the Quarkus runtime/deployment pair and BOM, share
  `com.github.sleepkqq.jimmer` and the version in `project/gradle.properties`.
- The repository remains a GitHub fork of `babyfish-ct/jimmer`; upstream history and
  existing fork branches remain available.

## Migrate a consumer

Replace `com.github.sleepkqq.quarkus-jimmer-extension:quarkus-jimmer:1.14.1` with
`com.github.sleepkqq.jimmer:quarkus-jimmer:1.0.0`. Replace every direct
`org.babyfish.jimmer:*` dependency with `com.github.sleepkqq.jimmer:*:1.0.0`, including
`jimmer-apt`, `jimmer-ksp` and `jimmer-bom`. Keep Maven Central and add
`https://jitpack.io`; no credentials or tokens are required. Packages and configuration
keys are unchanged. Kotlin consumers use the KSP plugin compatible with their compiler;
this release is tested with Kotlin 2.4.20 and KSP 2.3.12.

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

```bash
project/gradlew -p project build
project/gradlew -p project publishToMavenLocal -Dmaven.repo.local=/tmp/jimmer-m2
python3 scripts/verify-publication.py /tmp/jimmer-m2 1.0.0
project/gradlew -p smoke-tests build -PforkRepository=file:///tmp/jimmer-m2 -PforkVersion=1.0.0
```

`smoke-tests` is a separate Gradle build: it reuses the integration-test sources but
resolves runtime, deployment, APT and KSP from the selected Maven repository, with no
project dependencies, composite build or `mavenLocal()` fallback. The verifier checks
every published POM and Gradle module metadata file, plus the Quarkus deployment descriptor.

For each release, update the fork version and consumer snippets, pass CI, commit and
push `main`, then create and push an immutable semver tag (for example `1.0.0`).
JitPack's root `jitpack.yml` runs `publishToMavenLocal` from `project/` without signing.
Request the tagged POM to trigger the public build, then check
`https://jitpack.io/com/github/sleepkqq/jimmer/1.0.0/build.log` and run:

```bash
python3 scripts/verify-publication.py https://jitpack.io 1.0.0
project/gradlew -p smoke-tests clean build -PforkVersion=1.0.0 --refresh-dependencies
```

The final consumer check intentionally uses anonymous JitPack access. Create the GitHub
release only after JitPack succeeds; a tag alone does not prove the artifacts are available.
