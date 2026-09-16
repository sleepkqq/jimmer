package io.quarkiverse.jimmer.runtime.cache;

import java.time.Duration;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheConfig;
import io.quarkiverse.jimmer.runtime.cfg.JimmerRuntimeConfig;
import io.quarkus.arc.Unremovable;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.redis.datasource.RedisDataSource;

/**
 * Produces the Jimmer {@link CacheFactory} and {@link CacheTracker} beans when
 * {@code quarkus-redis-client} is on the classpath.
 */
public class JimmerRedisCacheProducer {

    @Produces
    @Singleton
    @Unremovable
    public CacheTracker cacheTracker(RedisDataSource redisDataSource) {
        return new QuarkusRedisCacheTracker(redisDataSource);
    }

    @Produces
    @Singleton
    @Unremovable
    public CacheFactory jimmerCacheFactory(
            RedisDataSource redisDataSource,
            JimmerCacheConfig config,
            JimmerRuntimeConfig runtimeConfig,
            Instance<CacheTracker> tracker,
            @ConfigProperty(name = "quarkus.redis.timeout", defaultValue = "10s") Duration timeout) {
        String defaultSchema = runtimeConfig.dataSources()
                .get(DataSourceUtil.DEFAULT_DATASOURCE_NAME)
                .defaultSchema()
                .orElse(null);
        return new JimmerRedisCacheFactory(
                redisDataSource,
                config,
                defaultSchema,
                tracker.isResolvable() ? tracker.get() : null,
                timeout);
    }
}
