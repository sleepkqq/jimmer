package io.quarkiverse.jimmer.it.config;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.AbstractCacheFactory;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheCreator;
import org.babyfish.jimmer.sql.cache.CacheFactory;

import io.quarkiverse.jimmer.runtime.cache.QuarkusRedisCacheTracker;
import io.quarkiverse.jimmer.runtime.cache.RedisCacheCreator;
import io.quarkus.redis.datasource.RedisDataSource;

@ApplicationScoped
public class CacheConfig {

    @Singleton
    public CacheFactory cacheFactory(RedisDataSource redisDataSource) {
        CacheCreator creator = new RedisCacheCreator(redisDataSource)
                .withRemoteDuration(Duration.ofHours(1))
                .withLocalCache(100, Duration.ofMinutes(5))
                .withMultiViewProperties(40, Duration.ofMinutes(2), Duration.ofMinutes(24))
                .withTracking( // Optional, for application cluster
                        new QuarkusRedisCacheTracker(redisDataSource));

        return new AbstractCacheFactory() {

            // Id -> Object
            @Override
            public Cache<?, ?> createObjectCache(ImmutableType type) {
                return creator.createForObject(type);
            }

            // Id -> TargetId, for one-to-one/many-to-one
            @Override
            public Cache<?, ?> createAssociatedIdCache(ImmutableProp prop) {
                return creator.createForProp(prop, getFilterState().isAffected(prop.getTargetType()));
            }

            // Id -> TargetId list, for one-to-many/many-to-many
            @Override
            public Cache<?, List<?>> createAssociatedIdListCache(ImmutableProp prop) {
                return creator.createForProp(prop, getFilterState().isAffected(prop.getTargetType()));
            }

            // Id -> computed value, for transient properties with resolver
            @Override
            public Cache<?, ?> createResolverCache(ImmutableProp prop) {
                return creator.createForProp(prop, true);
            }
        };
    }
}