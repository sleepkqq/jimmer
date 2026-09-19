package io.quarkiverse.jimmer.runtime.cache;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Instance;

import org.babyfish.jimmer.sql.cache.CacheFactory;

import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheConfig;
import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheGuardConfig;
import io.quarkus.arc.Unremovable;

/**
 * Produces the Jimmer {@link CacheFactory} when {@code quarkus-redis-client} is NOT on the
 * classpath — only {@code LOCAL_ONLY} entries are supported then. Free of Redis imports so the
 * bean class links without the Redis API.
 */
public class JimmerLocalCacheProducer {

    @Produces
    @Singleton
    @Unremovable
    public CacheFactory jimmerCacheFactory(JimmerCacheConfig config, JimmerCacheGuardConfig guard,
            Instance<CacheReadiness> readiness) {
        CacheFactory factory = new JimmerLocalCacheFactory(config);
        if (!guard.enabled()) return factory;
        if (!readiness.isResolvable()) {
            throw new IllegalStateException("quarkus.jimmer.cache.guard requires exactly one CacheReadiness bean");
        }
        return new GuardedCacheFactory(factory, readiness.get()::ready);
    }
}
