package io.quarkiverse.jimmer.runtime.cache;

import java.time.Duration;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheConfig;
import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheGuardConfig;
import io.quarkiverse.jimmer.runtime.cfg.JimmerRuntimeConfig;
import io.quarkus.arc.Unremovable;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.redis.datasource.RedisDataSource;

/**
 * Produces the Jimmer {@link CacheFactory} and {@link CacheTracker} beans when
 * {@code quarkus-redis-client} is on the classpath.
 */
public class JimmerRedisCacheProducer {

    public CacheTracker cacheTracker(RedisDataSource redisDataSource) {
        return new QuarkusRedisCacheTracker(redisDataSource);
    }

    @Produces
    @Singleton
    @Unremovable
    public CacheTracker cacheTracker(RedisDataSource redisDataSource,
            @ConfigProperty(name = "quarkus.redis.timeout", defaultValue = "10s") Duration timeout) {
        return new QuarkusRedisCacheTracker(redisDataSource, timeout);
    }

    public void closeTracker(@Disposes CacheTracker tracker) {
        if (tracker instanceof QuarkusRedisCacheTracker managed) managed.close();
    }

    @Produces
    @Singleton
    @Unremovable
    public CacheFactory jimmerCacheFactory(
            RedisDataSource redisDataSource,
            JimmerCacheConfig config,
            JimmerRuntimeConfig runtimeConfig,
            Instance<CacheTracker> tracker,
            JimmerCacheGuardConfig guard,
            Instance<CacheReadiness> readiness,
            @ConfigProperty(name = "quarkus.redis.timeout", defaultValue = "10s") Duration timeout) {
        String defaultSchema = runtimeConfig.dataSources()
                .get(DataSourceUtil.DEFAULT_DATASOURCE_NAME)
                .defaultSchema()
                .orElse(null);
        CacheReadiness condition = null;
        if (guard.enabled()) {
            if (!readiness.isResolvable()) {
                throw new IllegalStateException("quarkus.jimmer.cache.guard requires exactly one CacheReadiness bean");
            }
            condition = readiness.get();
            String namespace = condition.namespace();
            if (namespace == null || namespace.isBlank()) throw new IllegalStateException("Cache readiness requires a namespace");
            defaultSchema = defaultSchema == null ? namespace : defaultSchema + "-" + namespace;
        }
        CacheFactory factory = new JimmerRedisCacheFactory(
                redisDataSource,
                config,
                defaultSchema,
                tracker.isResolvable() ? tracker.get() : null,
                timeout);
        return condition == null ? factory : new GuardedCacheFactory(factory, condition::ready);
    }
}
