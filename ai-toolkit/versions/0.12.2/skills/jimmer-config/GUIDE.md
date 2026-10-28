# Jimmer Configuration Keys

The frameworks share many SQL-client options but not all defaults, scopes or property names:

- Spring Boot: `jimmer.<key>`
- Quarkus (unified `sleepkqq/jimmer` module): `quarkus.jimmer.<key>`; only datasource-scoped options accept `quarkus.jimmer.<datasource-name>.<key>`.

Markers: **(Q)** = unified Quarkus module, **(S)** = Spring starter. Verify the installed `io.quarkiverse.jimmer.runtime.cfg.*` or `JimmerProperties` before using a key. This is a focused reference, not a claim of complete parity.

Quarkus scope/phase: `JimmerBuildTimeConfig` owns global `enable`, `language`, client/error metadata; `JimmerDataSourceBuildTimeConfig` owns datasource SQL logging and `trigger-type`. Runtime datasource tuning is in `JimmerDataSourceRuntimeConfig`; global validation/eager initialization is in `JimmerRuntimeConfig`. Cache/guard configuration has its own global runtime mappings.

## Core

| Key | Default | Meaning |
|---|---|---|
| `enable` **(Q)** | `true` | master switch |
| `active` **(Q)**, per-ds | ds active | enable Jimmer for a specific datasource |
| `language` | `java` | `java` \| `kotlin` — mandatory for Kotlin |
| `dialect` | derived | dialect class; Quarkus derives from `quarkus.datasource.db-kind` |
| `micro-service-name` | — | remote associations service name |
| `default-schema` | — | schema qualifier |
| `eager-metadata-initialization` **(Q)** | `true` | build metadata + SqlClient on the startup thread — prevents scheduler-vs-lazy-init deadlock; disable only if the app must start without a reachable DB |

## SQL logging

| Key | Default | Meaning |
|---|---|---|
| `show-sql` / `pretty-sql` / `inline-sql-variables` | `false` | verbose SQL log (inline affects log text only, execution keeps JDBC params) |
| `compact-sql-log` **(Q)** | `false` | one line per statement under `jimmer.sql` (`SQL SELECT … (20 rows) \| 12ms [QUERY]`); overrides `show-sql` |
| `executor-context-prefixes` | — | package/class prefixes whose stack frames are appended to SQL logs |

## Validation and triggers

| Key | Default | Meaning |
|---|---|---|
| `database-validation-mode` | `NONE` | `NONE` \| `WARNING` \| `ERROR` (`database-validation.mode` nested form: deprecated in Quarkus; `.catalog`/`.schema` **(S)**) |
| `trigger-type` | `BINLOG_ONLY` | use with real CDC delivery; `TRANSACTION_ONLY` and `BOTH` are deprecated and should be migrated away from |
| `transaction-cache-operator-fixed-delay` | `5s` (Q) / `5000` ms (S) | legacy transaction-trigger retry setting, not a replacement for CDC |

## Query and fetching

| Key | Default | Meaning |
|---|---|---|
| `default-reference-fetch-type` | `SELECT` | `SELECT` \| `JOIN_IF_NO_CACHE` \| `JOIN_ALWAYS` |
| `max-join-fetch-depth` | `3` | join-fetch nesting cap |
| `default-batch-size` / `default-list-batch-size` | `128` / `16` | fetcher batch sizes (reference / collection) |
| `in-list-padding-enabled` / `expanded-in-list-padding-enabled` | `false` | pad IN lists to stabilize SQL plans |
| `offset-optimizing-threshold` | `MAX` | deep-pagination rewrite threshold |
| `reverse-sort-optimization-enabled` | `false` | fetch tail pages by reversed sort |
| `jdbc.default-fetch-size` / `jdbc.default-query-timeout` | — | JDBC defaults for queries |

## Save commands

| Key | Default | Meaning |
|---|---|---|
| `id-only-target-checking-level` | `NONE` | verify short association ids on save (`NONE` \| `FAKE` \| `ALL`) |
| `save-command-pessimistic-lock` **(S)** | `false` | save-command lookup queries take pessimistic locks |
| `default-dissociation-action-checkable` | `true` | validate dissociation configs |
| `dissociation-logical-delete-enabled` | `false` | dissociate children via logical delete |
| `max-command-join-count` | `2` | joins allowed inside save-command lookups |
| `mutation-transaction-required` | `false` | mutations must run inside a transaction |
| `target-transferable` | `false` | children may switch parents on save |
| `explicit-batch-enabled` / `dumb-batch-acceptable` | `false` | JDBC batch tuning knobs |
| `constraint-violation-translatable` | `false` (Q) / `true` (S) | enable constraint investigation and typed SaveException translation; may execute diagnostic queries |
| `sql-state-exception-translator` **(Q)** | `true` | SQLState → JimmerDataAccessException; effective with constraint investigation disabled |
| `default-type-change-allowed` | `false` | allow draft type switch |
| `default-save-returning-enabled` | `true` | materialize save results via DML `RETURNING` where the dialect supports it |
| `default-save-result-reads-all-properties` | `false` | read requested result values back from the DB (triggers/generated columns) instead of copying from the saved object |
| `is-foreign-key-enabled-by-default` | `true` | undeclared FKs treated as real constraints |
| `default-enum-strategy` | `NAME` | enum column mapping (`NAME` \| `ORDINAL`) |

## Entity cache (Q) — `quarkus.jimmer.cache.*`

Spring wires caches programmatically (CacheFactory bean — see jimmer-caching); the unified Quarkus module also provides declarative caches:

| Key | Default | Meaning |
|---|---|---|
| `log-operations` | `false` | one-line cache op log under `jimmer.cache` (GET tier hits / SET / DELETE) |
| `guard.enabled` | `false` | require exactly one `CacheReadiness` bean; gate reads/fills, keep invalidation active |
| `entities[].type` | — | entity simple name; absent = not cached |
| `entities[].mode` | `REMOTE_ONLY` | `LOCAL_ONLY` \| `REMOTE_ONLY` \| `FULL` (Caffeine in front of Redis) |
| `entities[].remote-ttl` / `local-ttl` | `PT30M` / `PT30S` | tier TTLs; short local TTL is the backstop for a missed cross-instance invalidation |
| `entities[].local-max-size` | `10000` | Caffeine cap |
| `entities[].cache-associations` | `true` | `false` leaves object caching but disables association caching; use when correct invalidation cannot be established |
| `entities[].random-percent` | `25` | remote-TTL jitter against synchronized mass expiry |

## Error translation and generated clients

| Key | Default | Meaning |
|---|---|---|
| `error-translator.disabled` | `false` | client error mapping off |
| `error-translator.http-status` | `500` | unified error status |
| `error-translator.debug-info-supported` | `false` | include exception details in client errors — never in prod |
| `error-translator.debug-info-max-stack-trace-count` | `0`(S)/`MAX`(Q) | stacktrace lines in client errors |
| `client.uri-prefix` **(Q)** | — | prefix for generated client endpoints |
| `client.ts.path` / `api-name` / `indent` / `mutable` | — / `Api` / `4` / `false` | generated TypeScript client |
| `client.ts.anonymous` **(S)** | `false` | anonymous TS types |
| `client.ts.null-render-mode` / `is-enum-ts-style` **(Q)** | `UNDEFINED` / `false` | TS null rendering / enum style |
| `client.openapi.path` / `ui-path` / `ref-path` / `properties.*` **(Q)** | `/openapi.yml` / `/openapi.html` / — | OpenAPI document, UI page, info/servers/securities/components |
| `client.java-feign.*` **(S)** | — | generated Spring Cloud Feign client |

## Sources

- [Quarkus config classes](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/cfg)
- [Spring JimmerProperties](https://github.com/babyfish-ct/jimmer/blob/v0.12.2/project/jimmer-spring-boot-starter/src/main/java/org/babyfish/jimmer/spring/cfg/JimmerProperties.java)
- [Spring appendix](https://babyfish-ct.github.io/jimmer-doc/docs/spring/appendix)
