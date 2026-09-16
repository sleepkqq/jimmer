package io.quarkiverse.jimmer.runtime.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import javax.sql.DataSource;
import jakarta.inject.Inject;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.jackson.codec.JsonCodec;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.BookStoreTable;
import io.quarkiverse.jimmer.it.entity.BookTable;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(RedisCacheTimeoutTest.Profile.class)
class RedisCacheReconnectTest {
    @Inject RedisDataSource redis;
    @Inject JSqlClient sql;
    @Inject DataSource dataSource;
    GenericContainer<?> redisContainer;

    @Test
    void failedReconnectIsRetriedWithoutDuplicateSubscriptions() throws Exception {
        Set<Long> baseline = subscriptions();
        try (QuarkusRedisCacheTracker tracker = new QuarkusRedisCacheTracker(redis, Duration.ofSeconds(1))) {
            kill(onlyNewSubscription(baseline));
            await(() -> !tracker.isReady());
            assertEquals("OK", redisContainer.execInContainer(
                    "redis-cli", "CLIENT", "PAUSE", "3500", "ALL").getStdout().trim());
            try {
                await(tracker::isReady);
                assertNotNull(onlyNewSubscription(baseline));
            } finally {
                redisContainer.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
            }
        }
        await(() -> subscriptions().equals(baseline));
    }

    @Test
    void multiViewCacheKeepsItsContractAndBypassesAfterClose() throws Exception {
        try (QuarkusRedisCacheTracker tracker = new QuarkusRedisCacheTracker(redis);
                Connection con = dataSource.getConnection()) {
            var prop = ImmutableType.get(BookStore.class).getProp("books");
            Cache<Long, List<Long>> built = new RedisCacheCreator(redis)
                    .withMultiViewProperties(32, Duration.ofMinutes(5)).withTracking(tracker)
                    .createForProp(prop, true);
            assertInstanceOf(Cache.Parameterized.class, built);
            Cache.Parameterized<Long, List<Long>> cache = (Cache.Parameterized<Long, List<Long>>) built;
            long id = anyId();
            var parameters = new TreeMap<String, Object>(Map.of("view", "test"));
            AtomicInteger loads = new AtomicInteger();
            CacheEnvironment<Long, List<Long>> env = new CacheEnvironment<>(sql, con, keys -> {
                loads.incrementAndGet();
                return Map.of(id, sql.createQuery(BookTable.$).where(BookTable.$.store().id().eq(id))
                        .select(BookTable.$.id()).execute(con));
            }, false);
            cache.deleteAll(List.of(id));
            var expected = cache.get(id, parameters, env);
            assertEquals(expected, cache.get(id, parameters, env));
            assertEquals(1, loads.get());
            tracker.close();
            cache.deleteAll(List.of(id));
            assertEquals(expected, cache.get(id, parameters, env));
            assertEquals(expected, cache.get(id, parameters, env));
            assertEquals(3, loads.get());
            var remote = RedisHashBinder.<Long, List<Long>>forProp(prop, JsonCodec.jsonCodec()).redis(redis).build();
            assertTrue(remote.getAll(List.of(id), parameters).isEmpty());
        }
    }

    @Test
    void repeatedDisconnectsResetWarmL1AndCloseStopsReconnect() throws Exception {
        Set<Long> baseline = subscriptions();
        QuarkusRedisCacheTracker tracker = new QuarkusRedisCacheTracker(redis);
        try (tracker; Connection con = dataSource.getConnection()) {
            Cache<Long, BookStore> cache = cache(tracker);
            long id = anyId();
            AtomicInteger loads = new AtomicInteger();
            CacheEnvironment<Long, BookStore> env = environment(con, loads, null, null);
            cache.deleteAll(List.of(id));
            assertNotNull(cache.get(id, env));
            int cold = loads.get();
            assertNotNull(cache.get(id, env));
            assertEquals(cold, loads.get());
            for (int i = 0; i < 2; i++) {
                long subscriber = onlyNewSubscription(baseline);
                CountDownLatch lost = new CountDownLatch(1);
                // Hold one existing read open to observe bypass before recovery resets the L1.
                CountDownLatch release = new CountDownLatch(1);
                cache.deleteAll(List.of(id));
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var active = executor.submit(() -> {
                        try (Connection worker = dataSource.getConnection()) {
                            return cache.get(id, environment(worker, loads, lost, release));
                        }
                    });
                    assertTrue(lost.await(5, TimeUnit.SECONDS));
                    kill(subscriber);
                    await(() -> !tracker.isReady());
                    try {
                        int before = loads.get();
                        assertNotNull(cache.get(id, env));
                        assertNotNull(cache.get(id, env));
                        assertEquals(before + 2, loads.get(), "Disconnected reads must bypass both tiers and not fill them");
                    } finally {
                        release.countDown();
                    }
                    assertNotNull(active.get(5, TimeUnit.SECONDS));
                }
                await(tracker::isReady);
                assertNotEquals(subscriber, onlyNewSubscription(baseline));
                // The next read may hit L2, but the old L1 must have been reset.
                String changed = "reconnected-" + UUID.randomUUID();
                String previous = cache.get(id, env).name();
                try {
                    update(id, changed);
                    try (QuarkusRedisCacheTracker publisher = new QuarkusRedisCacheTracker(redis)) {
                        cache(publisher).deleteAll(List.of(id));
                        await(() -> changed.equals(cache.get(id, env).name()));
                    }
                } finally {
                    update(id, previous);
                    cache.deleteAll(List.of(id));
                }
            }
        }
        assertFalse(tracker.isReady());
        await(() -> subscriptions().equals(baseline));
    }

    @Test
    void loadSpanningLostInvalidationCannotRefillRecoveredL1OrL2() throws Exception {
        Set<Long> baseline = subscriptions();
        try (QuarkusRedisCacheTracker tracker = new QuarkusRedisCacheTracker(redis);
                Connection con = dataSource.getConnection()) {
            Cache<Long, BookStore> cache = cache(tracker);
            long id = anyId();
            CacheEnvironment<Long, BookStore> env = environment(con, new AtomicInteger(), null, null);
            String previous = cache.get(id, env).name();
            cache.deleteAll(List.of(id));
            CountDownLatch loaded = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var oldRead = executor.submit(() -> {
                    try (Connection worker = dataSource.getConnection()) {
                        return cache.get(id, environment(worker, new AtomicInteger(), loaded, release));
                    }
                });
                try {
                    assertTrue(loaded.await(5, TimeUnit.SECONDS));
                    kill(onlyNewSubscription(baseline));
                    await(() -> !tracker.isReady());
                    String changed = "during-gap-" + UUID.randomUUID();
                    update(id, changed);
                    // Real invalidation from another instance while this subscriber is absent.
                    try (QuarkusRedisCacheTracker publisher = new QuarkusRedisCacheTracker(redis)) {
                        cache(publisher).deleteAll(List.of(id));
                    }
                    assertEquals(changed, cache.get(id, env).name());
                    release.countDown();
                    assertEquals(previous, oldRead.get(5, TimeUnit.SECONDS).name()); // in-flight DB snapshot
                    await(tracker::isReady);
                    assertEquals(changed, cache.get(id, env).name());
                    assertEquals(changed, cache.get(id, env).name());
                } finally {
                    release.countDown();
                    oldRead.get(5, TimeUnit.SECONDS);
                    update(id, previous);
                    cache.deleteAll(List.of(id));
                }
            }
        }
        await(() -> subscriptions().equals(baseline));
    }

    private Cache<Long, BookStore> cache(QuarkusRedisCacheTracker tracker) {
        return new RedisCacheCreator(redis).withTimeout(Duration.ofSeconds(1))
                .withLocalCache(32, Duration.ofMinutes(5)).withTracking(tracker)
                .createForObject(ImmutableType.get(BookStore.class));
    }

    private CacheEnvironment<Long, BookStore> environment(Connection con, AtomicInteger loads,
            CountDownLatch loaded, CountDownLatch release) {
        return new CacheEnvironment<>(sql, con, keys -> {
            loads.incrementAndGet();
            Map<Long, BookStore> rows = sql.createQuery(BookStoreTable.$).where(BookStoreTable.$.id().in(keys))
                    .select(BookStoreTable.$).execute(con).stream().collect(Collectors.toMap(BookStore::id, row -> row));
            if (loaded != null) {
                loaded.countDown();
                awaitLatch(release);
            }
            return rows;
        }, false);
    }

    private long anyId() {
        return sql.createQuery(BookStoreTable.$).select(BookStoreTable.$.id()).limit(1).execute().getFirst();
    }

    private void update(long id, String name) {
        sql.createUpdate(BookStoreTable.$).where(BookStoreTable.$.id().eq(id)).set(BookStoreTable.$.name(), name).execute();
    }

    private Set<Long> subscriptions() {
        return redis.execute("CLIENT", "LIST", "TYPE", "PUBSUB").toString().lines()
                .filter(line -> !line.isBlank()).map(line -> Long.parseLong(line.split(" ")[0].substring(3)))
                .collect(Collectors.toSet());
    }

    private long onlyNewSubscription(Set<Long> baseline) {
        List<Long> ids = subscriptions().stream().filter(id -> !baseline.contains(id)).toList();
        assertEquals(1, ids.size());
        return ids.getFirst();
    }

    private void kill(long id) {
        assertEquals(1, redis.execute("CLIENT", "KILL", "ID", Long.toString(id)).toInteger());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
