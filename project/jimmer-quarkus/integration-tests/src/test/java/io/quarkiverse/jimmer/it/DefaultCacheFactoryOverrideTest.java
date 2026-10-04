package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.inject.Inject;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.junit.jupiter.api.Test;

import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.runtime.cache.JimmerRedisCacheFactory;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The default test profile keeps the application {@code CacheConfig} producer active while
 * {@code quarkus-redis-client} is on the classpath, so the application factory and the runtime's
 * built-in Redis factory are both eligible {@code CacheFactory} beans. The built-in producer is a
 * {@code @DefaultBean}, so the application factory must win and the injected client must carry the
 * application's object cache. Before the marker was added the two producers were ambiguous, the
 * runtime resolved no factory and the injected client silently had no cache at all.
 *
 * <p>This deliberately asserts on the injected {@link JSqlClient}'s own caches rather than on a
 * fresh {@code CacheFactory#createObjectCache} call, which would build an unused cache and listener
 * and prove nothing about the wiring actually used by the client.
 */
@QuarkusTest
class DefaultCacheFactoryOverrideTest {

    @Inject
    CacheFactory cacheFactory;

    @Inject
    JSqlClient sqlClient;

    @Test
    void applicationFactoryIsResolvedInsteadOfBuiltInDefaultProducer() {
        assertNotNull(cacheFactory, "an application CacheFactory must remain resolvable next to the built-in default");
        assertFalse(
                cacheFactory instanceof JimmerRedisCacheFactory,
                "the application CacheConfig factory must win over the runtime's built-in @DefaultBean producer");
    }

    @Test
    void injectedClientIsWiredWithApplicationObjectCache() {
        assertNotNull(
                sqlClient.getCaches().getObjectCache(ImmutableType.get(BookStore.class)),
                "the injected JSqlClient must carry the application object cache; a null cache means the "
                        + "runtime resolved no CacheFactory (ambiguous built-in and application producers)");
    }
}
