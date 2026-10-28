# Spring key registry

Prefix every key with `jimmer.`. Defaults below come from `JimmerProperties` and the client builder. These are **Spring** settings, not portable Quarkus names.

| Key | Default | Notes |
|---|---|---|
| `language` | `java` | Set `kotlin` for Kotlin model |
| `dialect` | integration-selected | Use actual DB dialect; do not assume a PostgreSQL version |
| `show-sql` / `pretty-sql` / `inline-sql-variables` | `false` | Inline variables require pretty SQL; log formatting only |
| `database-validation-mode` | `NONE` | `NONE`, `WARNING`, `ERROR` |
| `trigger-type` | `BINLOG_ONLY` | `TRANSACTION_ONLY`, `BOTH` (choose by writer topology) |
| `transaction-cache-operator-fixed-delay` | `5000` | Milliseconds; not a CDC installer |
| `default-reference-fetch-type` | `SELECT` | Other concrete choices `JOIN_IF_NO_CACHE`, `JOIN_ALWAYS` |
| `max-join-fetch-depth` | `3` | Reference join nesting |
| `default-batch-size` | `128` | Fetch batching |
| `default-list-batch-size` | `16` | Collection fetch batching |
| `in-list-padding-enabled` / `expanded-in-list-padding-enabled` | `false` | Stabilize SQL shapes |
| `offset-optimizing-threshold` | `Integer.MAX_VALUE` | Deep-pagination optimization threshold |
| `reverse-sort-optimization-enabled` | `false` | Tail-page optimization |
| `id-only-target-checking-level` | `NONE` | `NONE`, `FAKE`, `ALL` |
| `default-dissociation-action-checkable` | `true` | Dissociation policy |
| `max-command-join-count` | `2` | Mutation lookup joins |
| `mutation-transaction-required` | `false` | Mutations require a transaction when enabled |
| `target-transferable` | `false` | Moving children across owners |
| `explicit-batch-enabled` / `dumb-batch-acceptable` | `false` | JDBC batch options |
| `constraint-violation-translatable` | `true` | Typed investigation may run diagnostic queries |
| `is-foreign-key-enabled-by-default` | `true` | Unspecified FKs treated as real |
| `default-enum-strategy` | `NAME` | `NAME` or `ORDINAL` |
| `default-schema` / `micro-service-name` | empty | Schema qualifier / remote associations |
| `executor-context-prefixes` | unset | Calling package/class context in logs |

Use `database-validation-mode`; `database-validation.mode` cannot be configured simultaneously. Check the exact nested class for additional keys.

Generated-client settings (`client.ts`, `client.openapi`, `client.java-feign`) and error translation live in the same pinned source; inspect their nested classes when requested rather than copying a later registry wholesale. Keep debug error details disabled for public responses.

Unknown YAML keys do not enable behavior; check the exact `JimmerProperties` nested class before adding a setting. For Quarkus, use `jimmer-quarkus` to identify the separately versioned integration first.

Sources: [JimmerProperties](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-spring-boot-starter/src/main/java/org/babyfish/jimmer/spring/cfg/JimmerProperties.java), [SQL-client builder](https://github.com/babyfish-ct/jimmer/blob/v0.9.111/project/jimmer-sql/src/main/java/org/babyfish/jimmer/sql/JSqlClient.java).
