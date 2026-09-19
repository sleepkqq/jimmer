# Configuration

## Build-time (`JimmerBuildTimeConfig`)

- `quarkus.jimmer.enable` (default: true)
- `quarkus.jimmer.language` (default: "java")
- `quarkus.jimmer.micro-service-name` (optional)
- `quarkus.jimmer.<datasource>.show-sql` (default: false)
- `quarkus.jimmer.<datasource>.pretty-sql` (default: false)
- `quarkus.jimmer.<datasource>.inline-sql-variables` (default: false)
- `quarkus.jimmer.<datasource>.trigger-type` (default: BINLOG_ONLY)

## Runtime (`JimmerRuntimeConfig` / `JimmerDataSourceRuntimeConfig`)

- `quarkus.jimmer.transaction-cache-operator-fixed-delay` (default: "5s")
- `quarkus.jimmer.database-validation.mode` (default: NONE)
- `quarkus.jimmer.<datasource>.default-schema`
- `quarkus.jimmer.<datasource>.dialect`
- `quarkus.jimmer.<datasource>.default-batch-size`
- `quarkus.jimmer.<datasource>.default-list-batch-size`
- `quarkus.jimmer.<datasource>.default-enum-strategy`
- `quarkus.jimmer.<datasource>.default-reference-fetch-type`
- `quarkus.jimmer.<datasource>.mutation-transaction-required`
- `quarkus.jimmer.<datasource>.id-only-target-checking-level`
- `quarkus.jimmer.<datasource>.in-list-padding-enabled`
- `quarkus.jimmer.<datasource>.foreign-key-enabled-by-default`
- `quarkus.jimmer.<datasource>.default-type-change-allowed` (default: false, since jimmer 0.11.0)
- `quarkus.jimmer.<datasource>.default-save-returning-enabled` (default: true, since jimmer 0.11.0)
- `quarkus.jimmer.<datasource>.default-save-result-reads-all-properties` (default: false, since jimmer 0.11.0)
- `quarkus.jimmer.<datasource>.jdbc.default-fetch-size` (since jimmer 0.11.0)
- `quarkus.jimmer.<datasource>.jdbc.default-query-timeout` (since jimmer 0.11.0)
- `quarkus.jimmer.<datasource>.constraint-violation-translatable` (default: **false** — extension override; Jimmer's own default true). При `false` Jimmer НЕ переводит SQL-нарушения в `SaveException`; наверх идёт сырой `SQLException`.
- `quarkus.jimmer.<datasource>.sql-state-exception-translator` (default: true). Регистрирует встроенный `SqlStateExceptionTranslator`, мапящий `SQLException` → `JimmerDataAccessException` по SQLState. Работает только вместе с `constraint-violation-translatable=false`.

## Exception translation

При `constraint-violation-translatable=false` встроенный `SqlStateExceptionTranslator`
(`runtime.exception`) мапит сырой `SQLException` в подтипы `JimmerDataAccessException` по SQLState:

| SQLState | Exception |
|---|---|
| `23505` | `DuplicateKeyException` |
| `23503` | `ForeignKeyViolationException` |
| `23502` | `NotNullViolationException` |
| `23514` | `CheckViolationException` |
| `40001` | `SerializationFailureException` (`TransientDataAccessException`) |
| `40P01` | `DeadlockException` (`TransientDataAccessException`) |
| прочие `23xxx` | `DataIntegrityViolationException` (fallback; напр. MySQL `23000`) |

`OptimisticLockError`, `IllegalTargetId` и др. — это save-логика Jimmer, а не SQL-нарушения, поэтому
translator их не трогает: остаются `SaveException`. Свои `ExceptionTranslator`-бины подхватываются
автоматически; для полного контроля выключить `sql-state-exception-translator`.

## Multi-datasource

`<datasource>` — имя datasource (`<default>` для основного). Каждый datasource получает свой `JSqlClient` bean с квалификатором `@DataSource("name")`.
