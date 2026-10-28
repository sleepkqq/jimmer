# Quarkus cache lifecycle — unified fork

Applies to `com.github.sleepkqq.jimmer:quarkus-jimmer`, whose implementation is in `sleepkqq/jimmer/project/jimmer-quarkus`. These are extension capabilities, not upstream Spring settings.

## Declarative setup

```yaml
quarkus:
  jimmer:
    trigger-type: BINLOG_ONLY
    cache:
      guard:
        enabled: true
      entities:
        - type: Book
          mode: FULL
          local-ttl: PT30S
          remote-ttl: PT30M
  redis:
    timeout: 2s
```

Enable the guard only with **exactly one** CDI `io.quarkiverse.jimmer.runtime.cache.CacheReadiness` provider. It supplies `ready()` and a nonblank `namespace()` stable for the process lifetime. The application owns what establishes source freshness; the extension does not supply a Kafka/CDC consumer. A changed cache generation/namespace can isolate old entries after a source gap.

Not ready: bypass cache reads **and fills** through the database loader; still perform invalidations and propagate their failures. A readiness gate by itself does not fence already-started loads. For programmatic integration, use `GuardedCacheFactory`/`GuardedCache.wrap` and the provider's existing readiness policy.

## Three different consistency mechanisms

| Mechanism | What it establishes | What it does not establish |
|---|---|---|
| `QuarkusRedisCacheTracker.isReady()` | Local Redis invalidation subscription readiness | External CDC catch-up, source retention, or global freshness |
| `CacheReadiness.ready()` | Application-defined source/readiness gate | Automatic durable CDC, already-in-flight fencing, or linearizable reads |
| Factory/creator-built fill fences | Reject a late cache fill when its per-key invalidation token changed/disappeared | Safety for older repeatable-read snapshots or old incompatible cache writers |

Tracker disconnection makes built-in chains bypass reads/fills. Recovery waits for earlier cached reads, clears L1 via the reconnect hook, then resumes cache use. Pub/Sub reconnect is not durable replay. Custom hand-built chains must explicitly honor readiness; CDI disposes its tracker, while programmatic owners close theirs.

Built-in Redis chains use Lua token checks for object and parameterized property caches. Redis must allow `EVAL`; in Redis Cluster, each data/fence pair needs a shared hash tag. Do not copy low-level binder calls and assume they retain factory-level fencing. Deploy compatible writers together or move to a fresh namespace.

`quarkus.redis.timeout` bounds cache operations, separately from entry TTLs. Programmatic factories/binders have explicit timeout options. Timeout means the Redis command's outcome can be unknown, not that it rolled back; retry invalidation and do not acknowledge the CDC event on failure.

## Verification

Hold a DB load across an invalidation and verify it cannot repopulate stale L1/L2. Test a subscription gap, recovery, repeated disconnect, and two filter contexts. Verify freshness before accepting cached traffic; a successful Redis PING is insufficient.

Source: [module cache implementation](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/cache), [configuration](https://github.com/sleepkqq/jimmer/tree/main/project/jimmer-quarkus/runtime/src/main/java/io/quarkiverse/jimmer/runtime/cfg).
