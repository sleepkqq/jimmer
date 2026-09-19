package io.quarkiverse.jimmer.runtime.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.babyfish.jimmer.sql.cache.caffeine.CaffeineValueBinder;
import org.babyfish.jimmer.sql.cache.chain.ChainCacheBuilder;
import org.babyfish.jimmer.sql.cache.chain.LoadingBinder;
import org.jetbrains.annotations.Nullable;

import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheConfig;

/**
 * Builds the {@code LOCAL_ONLY} chain: a Caffeine tier and, when a {@link CacheTracker} is present
 * (Redis on the classpath), a storage-less {@link InvalidationPublishBinder} so invalidations still
 * propagate across instances. Deliberately free of Redis imports — usable when
 * {@code quarkus-redis-client} is absent entirely.
 */
final class LocalOnlyCaches {

    private LocalOnlyCaches() {
    }

    static <K, V> Cache<K, V> create(
            @Nullable ImmutableType type,
            @Nullable ImmutableProp prop,
            JimmerCacheConfig.EntityCacheConfig config,
            @Nullable CacheTracker tracker,
            boolean operationLog) {
        CaffeineValueBinder.Builder<K, V> caffeine = type != null
                ? CaffeineValueBinder.forObject(type)
                : CaffeineValueBinder.forProp(prop);
        LoadingBinder<K, V> caffeineBinder = caffeine
                .subscribe(tracker)
                .maximumSize(config.localMaxSize())
                .duration(config.localTtl())
                .build();
        return new ChainCacheBuilder<K, V>()
                .add(operationLog ? LoggingBinder.wrap(caffeineBinder) : caffeineBinder)
                .add(tracker != null ? new InvalidationPublishBinder<>(type, prop, tracker) : null)
                .build();
    }
}
