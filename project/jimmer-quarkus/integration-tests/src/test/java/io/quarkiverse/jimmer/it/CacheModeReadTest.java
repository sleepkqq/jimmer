package io.quarkiverse.jimmer.it;

import static io.quarkiverse.jimmer.it.TestCacheConfigs.config;
import static io.quarkiverse.jimmer.it.TestCacheConfigs.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.cache.CacheLoader;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.it.entity.BookProps;
import io.quarkiverse.jimmer.it.entity.BookTable;
import io.quarkiverse.jimmer.runtime.cache.CacheMode;
import io.quarkiverse.jimmer.runtime.cache.GuardedCacheFactory;
import io.quarkiverse.jimmer.runtime.cache.JimmerRedisCacheFactory;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * A cache read must traverse the whole chain in every mode. Building the cache is not enough:
 * {@code LOCAL_ONLY} used to build fine and blow up on the first miss, because its chain link
 * returned an immutable map that Jimmer's {@code ChainCacheImpl.SimpleNode} writes loaded values
 * into.
 */
@QuarkusTest
class CacheModeReadTest {

    private static final long MISSING_ID = -1L;

    @Inject
    RedisDataSource redisDataSource;

    @Inject
    CacheTracker cacheTracker;

    @Inject
    JSqlClient sqlClient;

    @Inject
    DataSource dataSource;

    @ParameterizedTest
    @EnumSource(CacheMode.class)
    void readsThroughTheChainOnMiss(CacheMode mode) throws Exception {
        Cache<Long, Book> cache = objectCache(mode);

        try (Connection con = dataSource.getConnection()) {
            Map<Long, Book> loaded = cache.getAll(
                    List.of(MISSING_ID),
                    new CacheEnvironment<>(sqlClient, con, keys -> Map.of(), false));

            assertNull(loaded.get(MISSING_ID));
        }
    }

    @ParameterizedTest
    @EnumSource(CacheMode.class)
    void readsThroughTheChainOnHit(CacheMode mode) throws Exception {
        Cache<Long, Book> cache = objectCache(mode);
        long id = anyBookId();

        try (Connection con = dataSource.getConnection()) {
            CacheEnvironment<Long, Book> env = new CacheEnvironment<>(
                    sqlClient, con, CacheLoader.objectLoader(sqlClient, con, Book.class), false);

            assertNotNull(cache.getAll(List.of(id), env).get(id));
            // second read is served by the cache tiers themselves, still traversing the chain
            assertNotNull(cache.getAll(List.of(id), env).get(id));
        }
    }

    @Test
    void contentOnlyPolicyIsPreservedByEveryRedisCacheMode() {
        for (CacheMode mode : CacheMode.values()) {
            JimmerRedisCacheFactory factory = new JimmerRedisCacheFactory(
                    redisDataSource,
                    config(entity("Book", mode, true, "name")),
                    null,
                    cacheTracker);
            GuardedCacheFactory guarded = new GuardedCacheFactory(factory, () -> false);

            assertTrue(guarded.isObjectCacheContentOnly(ImmutableType.get(Book.class)), mode.toString());
            assertEquals(List.of("name"), guarded.getObjectCacheContentFields(ImmutableType.get(Book.class)), mode.toString());
            assertTrue(guarded.getObjectCacheContentFields(ImmutableType.get(io.quarkiverse.jimmer.it.entity.Author.class))
                    .isEmpty(), mode.toString());
            assertFalse(guarded.isObjectCacheContentOnly(ImmutableType.get(io.quarkiverse.jimmer.it.entity.Author.class)),
                    mode.toString());
        }
    }

    private long anyBookId() {
        return sqlClient
                .createQuery(BookTable.$)
                .select(BookTable.$.id())
                .limit(1)
                .execute()
                .get(0);
    }

    @ParameterizedTest
    @EnumSource(CacheMode.class)
    @SuppressWarnings("unchecked")
    void filteredAssociationsKeepViewsSeparateAndInvalidateAllViews(CacheMode mode) throws Exception {
        JimmerRedisCacheFactory factory = new JimmerRedisCacheFactory(
                redisDataSource, config(entity("Book", mode), entity("Author", mode)), null, cacheTracker);
        factory.setFilterState(type -> true);
        Cache.Parameterized<Long, List<Long>> cache = (Cache.Parameterized<Long, List<Long>>)
                assertInstanceOf(Cache.Parameterized.class, factory.createAssociatedIdListCache(BookProps.AUTHORS.unwrap()));
        long id = -System.nanoTime();
        AtomicInteger loads = new AtomicInteger();
        try (Connection con = dataSource.getConnection()) {
            CacheEnvironment<Long, List<Long>> first = new CacheEnvironment<>(sqlClient, con, keys -> {
                loads.incrementAndGet();
                return Map.of(id, List.of(11L));
            }, false);
            CacheEnvironment<Long, List<Long>> second = new CacheEnvironment<>(sqlClient, con, keys -> {
                loads.incrementAndGet();
                return Map.of(id, List.of(22L));
            }, false);
            TreeMap<String, Object> defaultTenant = new TreeMap<>(Map.of("tenant", "DEFAULT"));
            TreeMap<String, Object> demoTenant = new TreeMap<>(Map.of("tenant", "DEMO"));
            for (int i = 0; i < 2; i++) {
                assertEquals(List.of(11L), cache.getAll(List.of(id), defaultTenant, first).get(id));
                assertEquals(List.of(22L), cache.getAll(List.of(id), demoTenant, second).get(id));
            }
            assertEquals(2, loads.get());
            cache.deleteAll(List.of(id), null);
            assertEquals(List.of(11L), cache.getAll(List.of(id), defaultTenant, first).get(id));
            assertEquals(List.of(22L), cache.getAll(List.of(id), demoTenant, second).get(id));
            assertEquals(4, loads.get());
        } finally {
            cache.deleteAll(List.of(id), null);
        }
    }

    /**
     * A fill that is already in flight when an invalidation arrives must not be retained in either
     * tier, for a hit value and for a cached miss (negative entry). The loader blocks on a latch, so
     * the invalidation is delivered deterministically while the scope is active; no sleeps and no
     * fake readiness are used.
     */
    @ParameterizedTest
    @EnumSource(CacheMode.class)
    void invalidatedInFlightFillIsNotRetained(CacheMode mode) throws Exception {
        Cache<Long, Book> cache = objectCache(mode);
        assertFillRaceIsNotRetained(cache, mode, anyBookId(), false);
        assertFillRaceIsNotRetained(cache, mode, MISSING_ID, true);
    }

    private void assertFillRaceIsNotRetained(Cache<Long, Book> cache, CacheMode mode, long id, boolean negative)
            throws Exception {
        cache.deleteAll(List.of(id), null);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inFlight = executor.submit(() -> {
                try (Connection worker = dataSource.getConnection()) {
                    return cache.getAll(List.of(id), new CacheEnvironment<>(sqlClient, worker, keys -> {
                        Map<Long, Book> snapshot = negative ? Map.of()
                                : CacheLoader.<Long, Book>objectLoader(sqlClient, worker, Book.class).loadAll(keys);
                        loaded.countDown();
                        awaitLatch(release);
                        return snapshot;
                    }, false)).get(id);
                }
            });
            try {
                assertTrue(loaded.await(5, TimeUnit.SECONDS));
                // Real invalidation of the same key while the fill is in flight.
                cache.deleteAll(List.of(id), null);
                release.countDown();
                if (negative) {
                    assertNull(inFlight.get(5, TimeUnit.SECONDS));
                } else {
                    assertNotNull(inFlight.get(5, TimeUnit.SECONDS));
                }
            } finally {
                release.countDown();
            }
            AtomicInteger freshLoads = new AtomicInteger();
            try (Connection con = dataSource.getConnection()) {
                Book fresh = cache.getAll(List.of(id), new CacheEnvironment<>(sqlClient, con, keys -> {
                    freshLoads.incrementAndGet();
                    return negative ? Map.of()
                            : CacheLoader.<Long, Book>objectLoader(sqlClient, con, Book.class).loadAll(keys);
                }, false)).get(id);
                if (negative) {
                    assertNull(fresh);
                } else {
                    assertNotNull(fresh);
                }
            }
            assertEquals(1, freshLoads.get(),
                    mode + ": an invalidated in-flight " + (negative ? "negative " : "") + "fill must not be retained");
        } finally {
            cache.deleteAll(List.of(id), null);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Cache<Long, Book> objectCache(CacheMode mode) {
        JimmerRedisCacheFactory factory = new JimmerRedisCacheFactory(
                redisDataSource,
                config(entity("Book", mode)),
                null,
                cacheTracker);
        return (Cache<Long, Book>) factory.createObjectCache(ImmutableType.get(Book.class));
    }
}
