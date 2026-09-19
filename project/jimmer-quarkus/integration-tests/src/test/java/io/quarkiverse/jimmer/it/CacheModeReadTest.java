package io.quarkiverse.jimmer.it;

import static io.quarkiverse.jimmer.it.TestCacheConfigs.config;
import static io.quarkiverse.jimmer.it.TestCacheConfigs.entity;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

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

import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.it.entity.BookTable;
import io.quarkiverse.jimmer.runtime.cache.CacheMode;
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

    private long anyBookId() {
        return sqlClient
                .createQuery(BookTable.$)
                .select(BookTable.$.id())
                .limit(1)
                .execute()
                .get(0);
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
