# Connection Management

## QuarkusConnectionManager

`runtime/src/main/java/.../cfg/support/QuarkusConnectionManager.java`

Реализует `TxConnectionManager` + `DataSourceAwareConnectionManager`. Управляет JDBC-соединениями:
- Получает `Connection` из `DataSource` (Agroal pool) **лениво** — при первом реальном JDBC-вызове
  (`LazyConnection`, `java.lang.reflect.Proxy` над `java.sql.Connection`)
- Делегирует управление транзакциями в `QuarkusTransaction` / Narayana JTA
- **Один коннект на JTA-транзакцию** — переиспользуется всеми Jimmer-операциями в рамках одной TX

## Connection lifecycle

1. Запрос приходит, JTA-транзакция активна (`@Transactional` / `QuarkusTransaction.requiringNew()`).
2. Первый вызов `execute(block)` → `tsr.getResource(connectionKey)` → нет ресурса → создаётся
   `LazyConnection`-прокси. Пул **не** опрашивается: `dataSource.getConnection()` ещё не вызван.
3. Прокси сохраняется через `tsr.putResource(connectionKey, proxy)` — привязка к **текущей TX**.
4. Первый JDBC-вызов на прокси (statement, `getAutoCommit`, `getMetaData`, `unwrap`, …) → ровно один
   `dataSource.getConnection()`; дальнейшие вызовы идут в тот же target. `equals`/`hashCode`/
   `toString`/`close`/`isClosed` коннект не берут.
5. Последующие Jimmer-операции в той же TX → `getResource` возвращает тот же прокси.
6. Commit → `beforeCompletion` закрывает прокси; target возвращается в пул только если он был
   acquired. Rollback → `beforeCompletion` не вызывается, коннект возвращает Narayana/Agroal
   (соединение уже enlisted). Транзакция без единого JDBC-вызова не берёт коннект вообще.
7. Вне транзакции (`isTransactionActive() == false`) — try-with-resources над прокси: коннект
   берётся при первом JDBC-вызове и закрывается при выходе из scope.

Следствие: read, полностью обслуженный object cache (`findById`/`findByIds` без фильтров или
только со встроенным logical delete),
не делает `getConnection()` и не enlists соединение в JTA-транзакции.

## ConnectionScope open() (jimmer 0.11.0, selection streaming)

`open(con)` — для streaming-запросов, где коннект должен жить дольше вызова (закрывается при закрытии stream):
- Явный `con` → `ConnectionScope.userConnection(con)` (не закрываем).
- Активная TX → тот же TX-прокси, что и в `execute()` (закроет synchronization на commit/rollback).
- Без TX → свежий `LazyConnection`-прокси; `scope.close()` закрывает прокси, т.е. возвращает
  target в пул только если он был acquired. Streaming без JDBC коннект не берёт.

## Post-commit eviction для `BINLOG_ONLY`

`TriggerType.BINLOG_ONLY` не запускает transaction triggers, поэтому собственные записи клиента
иначе оставляли бы устаревший object/property cache до прихода binlog-события. Ядро сообщает
изменённые ключи через SPI `org.babyfish.jimmer.sql.runtime.MutationKeyListener`: вызов происходит
после выполнения statement, одним батчем на statement, и только если `triggerType == BINLOG_ONLY`
и текущий connection manager реализует SPI. Ключи собираются (а у bulk update/delete появляется
предварительный `select id`) только для типов, у которых в иерархии есть object cache или
property cache — клиент без таких кэшей выполняет прежний SQL.

`QuarkusConnectionManager` реализует этот SPI; кэши приходят из `JQuarkusSqlClient.afterCreate`
(`setCaches`) только для `BINLOG_ONLY`-клиента с непустыми кэшами — без кэшей менеджер ключи не копит.

| Scope записи | Куда попадают ключи | Когда eviction |
|---|---|---|
| Активная JTA-транзакция | TSR-ресурс под собственным `mutationKeysKey` (отдельно от `connectionKey`) | одна interposed synchronization: `afterCompletion(STATUS_COMMITTED)` |
| auto-commit `execute()` вне TX | ThreadLocal-батч скоупа | после закрытия соединения скоупа (statement уже durable) |
| statement вне обоих scope (streaming update-returning: stream закрывается позже) | одноразовый immediate-батч | сразу после statement |
| пользовательский `con` | — | не evict-ится, соединение не наше |

Eviction — `getObjectCache(type).deleteAll(ids)` и `getPropertyCache(prop).deleteAll(sourceIds)`.
Rollback ничего не удаляет. `RuntimeException` от кэша ловится на каждую запись и логируется
`warn`: сломанный кэш не отменяет уже закоммиченную транзакцию. Multi-datasource безопасен:
у каждого менеджера свой ключ и свои кэши.

## Ключ кеширования: `private final Object connectionKey`

Каждый инстанс `QuarkusConnectionManager` (один на datasource) имеет **свой** `connectionKey`.
`TransactionSynchronizationRegistry.getResource/putResource` привязывает значение к паре
**(ключ × текущая транзакция)**.

Это обеспечивает корректность двух сценариев:
- **Multi-datasource** — каждый DS кеширует свой коннект независимо (разные ключи в одной TX).
- **REQUIRES_NEW / NOT_SUPPORTED** — подвешенная транзакция остаётся со своим ресурсом в своём TSR-слоте;
  новая (inner) транзакция видит пустой слот → берёт свежий коннект.

## High-load модель

Стек **блокирующий** (JDBC + Narayana JTA), реактивности нет.

| Сценарий | Поведение |
|---|---|
| Read полностью из object cache (без фильтров или только со встроенным logical delete) | **0 acquire** — коннект не нужен |
| Несколько repo-вызовов в `@Transactional` | Один коннект на всю транзакцию → минимум acquire |
| Несколько repo-вызовов **без** `@Transactional` | Отдельный acquire/release на **каждый** вызов, но только при первом JDBC-вызове (cache-hit → 0) |
| REQUIRES_NEW вложенная TX | Свой коннект, изолирован от outer TX |

**Рекомендация**: оборачивать серии read-запросов в `@Transactional` (или `readOnly=true`) чтобы
использовать один коннект вместо N acquire на N вызовов. Особенно важно при высокой нагрузке.
Компромисс: object-cache гидратация запросов работает только при доказанно неактивной транзакции,
поэтому чтения внутри `@Transactional` идут целиком в SQL и не наполняют общий кэш.
Транзакции без JDBC-вызовов (cache-served reads) пул не трогают вовсе.

**Мониторинг пула**: Quarkus Micrometer экспортирует `agroal.pool.*` метрики — использовать для
отслеживания pool utilization, wait-time и connection leaks.

## Multi-datasource

Каждый datasource получает свой `QuarkusConnectionManager` (см. `JQuarkusSqlClient.java:220`).
Два DS в **одной** локальной JTA-транзакции невозможны без XA — Narayana/Agroal блокирует
(local transactions, не XA). Для XA нужна отдельная конфигурация Agroal с `xa=true`.

## Поддерживаемые Propagation

| Jimmer Propagation | Поведение |
|---|---|
| REQUIRED (default) | `joiningExisting()` |
| REQUIRES_NEW | `requiringNew()` |
| NOT_SUPPORTED | `suspendingExisting()` |
| SUPPORTS / MANDATORY / NEVER | `UnsupportedOperationException` |
