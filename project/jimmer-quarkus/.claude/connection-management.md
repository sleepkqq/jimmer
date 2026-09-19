# Connection Management

## QuarkusConnectionManager

`runtime/src/main/java/.../cfg/support/QuarkusConnectionManager.java`

Реализует `TxConnectionManager` + `DataSourceAwareConnectionManager`. Управляет JDBC-соединениями:
- Получает `Connection` из `DataSource` (Agroal pool)
- Делегирует управление транзакциями в `QuarkusTransaction` / Narayana JTA
- **Один коннект на JTA-транзакцию** — переиспользуется всеми Jimmer-операциями в рамках одной TX

## Connection lifecycle

1. Запрос приходит, JTA-транзакция активна (`@Transactional` / `QuarkusTransaction.requiringNew()`).
2. Первый вызов `execute(block)` → `tsr.getResource(connectionKey)` → нет ресурса → `dataSource.getConnection()`.
3. Коннект сохраняется через `tsr.putResource(connectionKey, conn)` — привязка к **текущей TX**.
4. Последующие Jimmer-операции в той же TX → `getResource` возвращает закешированный коннект.
5. Commit/rollback → Narayana автоматически сбрасывает TSR-ресурсы; Agroal возвращает коннект в пул.
6. Вне транзакции (`isTransactionActive() == false`) — try-with-resources: коннект открывается и сразу закрывается.

## ConnectionScope open() (jimmer 0.11.0, selection streaming)

`open(con)` — для streaming-запросов, где коннект должен жить дольше вызова (закрывается при закрытии stream):
- Явный `con` → `ConnectionScope.userConnection(con)` (не закрываем).
- Активная TX → тот же TX-коннект, что и в `execute()` (закроет synchronization на commit/rollback).
- Без TX → свежий коннект; `scope.close()` возвращает его в пул.

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
| Несколько repo-вызовов в `@Transactional` | Один коннект на всю транзакцию → минимум acquire |
| Несколько repo-вызовов **без** `@Transactional` | Отдельный acquire/release на **каждый** вызов |
| REQUIRES_NEW вложенная TX | Свой коннект, изолирован от outer TX |

**Рекомендация**: оборачивать серии read-запросов в `@Transactional` (или `readOnly=true`) чтобы
использовать один коннект вместо N acquire на N вызовов. Особенно важно при высокой нагрузке.

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
