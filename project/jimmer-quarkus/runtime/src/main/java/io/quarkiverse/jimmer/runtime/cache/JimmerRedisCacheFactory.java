package io.quarkiverse.jimmer.runtime.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheCreator;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheConfig;
import io.quarkus.redis.datasource.RedisDataSource;

/**
 * {@link CacheFactory} building per-entity chain caches from {@link JimmerCacheConfig}:
 * {@code LOCAL_ONLY} is a Caffeine-only chain (plus cross-instance invalidation publication when a
 * {@link CacheTracker} is present), {@code REMOTE_ONLY} a Redis-only cache, {@code FULL} a Caffeine
 * local tier in front of Redis kept consistent across instances by the tracker.
 *
 * <p>Requires Redis; see {@link JimmerLocalCacheFactory} for applications without
 * {@code quarkus-redis-client}.</p>
 */
public class JimmerRedisCacheFactory implements CacheFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(JimmerRedisCacheFactory.class);

    private final RedisDataSource redisDataSource;
    private final CacheTracker tracker;
    private final Map<String, JimmerCacheConfig.EntityCacheConfig> configByType;
    private final SchemaRemoteKeyPrefixProvider keyPrefixProvider;
    private final boolean operationLog;
    private final Duration timeout;

    public JimmerRedisCacheFactory(
            RedisDataSource redisDataSource,
            JimmerCacheConfig config,
            String defaultSchema,
            CacheTracker tracker) {
        this(redisDataSource, config, defaultSchema, tracker, RedisCacheCreator.DEFAULT_TIMEOUT);
    }

    public JimmerRedisCacheFactory(
            RedisDataSource redisDataSource,
            JimmerCacheConfig config,
            String defaultSchema,
            CacheTracker tracker,
            Duration timeout) {
        this.timeout = RedisCacheCreator.requireTimeout(timeout);
        this.redisDataSource = redisDataSource;
        this.tracker = tracker;
        this.keyPrefixProvider = new SchemaRemoteKeyPrefixProvider(defaultSchema);
        this.operationLog = config.logOperations();
        this.configByType = new LinkedHashMap<>();
        for (JimmerCacheConfig.EntityCacheConfig entity : config.entities()) {
            if (entity.mode() != CacheMode.LOCAL_ONLY && redisDataSource == null) {
                throw new IllegalStateException(
                        "Entity " + entity.type() + " is configured with cache mode " + entity.mode()
                                + ", which requires quarkus-redis-client on the classpath; "
                                + "add the dependency or switch the entity to LOCAL_ONLY");
            }
            configByType.put(entity.type(), entity);
            LOGGER.info("Jimmer cache enabled for entity {} [mode={}]", entity.type(), entity.mode());
        }
        if (redisDataSource != null && !configByType.isEmpty()) {
            warmUpConnection();
        }
    }

    /**
     * The batched writes/deletes go through the low-level {@code Redis} client whose connection is
     * established lazily — without this, the first write after a pod start pays the connection
     * setup (~400ms observed) inside a user request. Also fails the start fast when Redis is down.
     */
    private void warmUpConnection() {
        redisDataSource.execute("PING");
    }

    @Override
    public Cache<?, ?> createObjectCache(ImmutableType type) {
        JimmerCacheConfig.EntityCacheConfig config = configByType.get(type.getJavaClass().getSimpleName());
        if (config == null) {
            return null;
        }
        if (config.mode() == CacheMode.LOCAL_ONLY) {
            return SubscriptionCache.wrap(LocalOnlyCaches.create(type, null, config, tracker, operationLog), tracker);
        }
        return creator(config).createForObject(type);
    }

    @Override
    public Cache<?, ?> createAssociatedIdCache(ImmutableProp prop) {
        return associationCache(prop);
    }

    @Override
    public Cache<?, List<?>> createAssociatedIdListCache(ImmutableProp prop) {
        return associationCache(prop);
    }

    @Override
    public Cache<?, ?> createResolverCache(ImmutableProp prop) {
        return null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private <T> T associationCache(ImmutableProp prop) {
        JimmerCacheConfig.EntityCacheConfig config = configByType.get(prop.getDeclaringType().getJavaClass().getSimpleName());
        if (config == null || !config.cacheAssociations() || !isCachedAssociation(prop)) {
            return null;
        }
        if (config.mode() == CacheMode.LOCAL_ONLY) {
            return (T) SubscriptionCache.wrap(LocalOnlyCaches.create(null, prop, config, tracker, operationLog), tracker);
        }
        return (T) (Cache) creator(config).createForProp(prop, false);
    }

    private CacheCreator creator(JimmerCacheConfig.EntityCacheConfig config) {
        RedisCacheCreator redisCreator = new RedisCacheCreator(redisDataSource)
                .withTimeout(timeout)
                .withKeyPrefixProvider(keyPrefixProvider);
        if (operationLog) {
            redisCreator = redisCreator.withOperationLog();
        }
        CacheCreator creator = redisCreator.withRemoteDuration(config.remoteTtl(), config.randomPercent());

        if (config.mode() == CacheMode.FULL) {
            creator = creator.withLocalCache(config.localMaxSize(), config.localTtl());
            if (tracker != null) {
                creator = creator.withTracking(tracker);
            } else {
                LOGGER.warn("Entity {} uses FULL cache but no CacheTracker is available; "
                        + "the local tier may serve stale data across instances", config.type());
            }
        } else {
            creator = creator.withoutLocalCache();
        }
        return creator;
    }

    private boolean isCachedAssociation(ImmutableProp prop) {
        String sourceType = prop.getDeclaringType().getJavaClass().getSimpleName();
        ImmutableType targetType = prop.getTargetType();
        if (targetType == null) {
            return false;
        }
        return configByType.containsKey(sourceType) && configByType.containsKey(targetType.getJavaClass().getSimpleName());
    }
}
