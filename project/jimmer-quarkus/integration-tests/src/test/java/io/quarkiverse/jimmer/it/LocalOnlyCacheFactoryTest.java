package io.quarkiverse.jimmer.it;

import static io.quarkiverse.jimmer.it.TestCacheConfigs.config;
import static io.quarkiverse.jimmer.it.TestCacheConfigs.entity;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.junit.jupiter.api.Test;

import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.runtime.cache.CacheMode;
import io.quarkiverse.jimmer.runtime.cache.JimmerLocalCacheFactory;

/**
 * LOCAL_ONLY without Redis: the Caffeine-only factory must build working caches for configured
 * entities and reject modes that need Redis instead of silently serving uncached reads.
 */
class LocalOnlyCacheFactoryTest {

    @Test
    void buildsCaffeineOnlyCacheForConfiguredEntity() {
        JimmerLocalCacheFactory factory = new JimmerLocalCacheFactory(
                config(entity("Book", CacheMode.LOCAL_ONLY)));

        Cache<?, ?> cache = factory.createObjectCache(ImmutableType.get(Book.class));

        assertNotNull(cache);
    }

    @Test
    void returnsNullForUnconfiguredEntity() {
        JimmerLocalCacheFactory factory = new JimmerLocalCacheFactory(
                config(entity("Book", CacheMode.LOCAL_ONLY)));

        assertNull(factory.createObjectCache(ImmutableType.get(io.quarkiverse.jimmer.it.entity.Author.class)));
    }

    @Test
    void rejectsModesThatRequireRedis() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JimmerLocalCacheFactory(config(entity("Book", CacheMode.FULL))));

        assertTrue(ex.getMessage().contains("quarkus-redis-client"));
    }

}
