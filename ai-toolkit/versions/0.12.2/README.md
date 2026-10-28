# Jimmer 0.12.2

The existing toolkit set has moved from root `skills/` to this release directory.
Its 17 topics, on-demand guides, helper scripts and historical evaluation evidence
are retained. Descriptions now identify the release to prevent accidental use in
older applications.

```bash
./install.sh --version 0.12.2
```

## Source boundary

Official core baseline: `babyfish-ct/jimmer` tag `v0.12.2`, commit
`871c49379149c1950a836bb11b59d30a0babd476`.

**Quarkus guidance is a separately versioned integration**, from `sleepkqq/jimmer`
commit `d16eb39ee49a7429093fcc8c8b6db22f57262f04`, as recorded in the original
[refresh ledger](../../docs/skill-refresh.md). The unified Quarkus module,
configuration, repository, exception and Redis-lifecycle sections must not be
interpreted as official `org.babyfish.jimmer` release artifacts or mixed with
official runtime/processors. [sources.json](sources.json) records both origins.

## Existing executable evidence

The former `tests/skill-evals/compile-smoke` fixture is now
[`examples/java`](examples/java). It retains its original Java/APT/H2 behavior
checks and defaults to 0.12.2:

```bash
gradle -p versions/0.12.2/examples/java --no-daemon --console=plain run
```

The recorded [model evaluations](../../tests/skill-evals/README.md) apply to this
newer release-era set. The separately authored
[0.9.111 examples](../0.9.111/examples/README.md) include both Java/APT and
Kotlin/KSP and do not inherit those evaluation scores.
