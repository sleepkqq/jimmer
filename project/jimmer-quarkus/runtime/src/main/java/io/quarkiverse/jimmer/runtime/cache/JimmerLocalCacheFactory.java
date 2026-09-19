package io.quarkiverse.jimmer.runtime.cache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheConfig;

/**
 * {@link CacheFactory} for applications without {@code quarkus-redis-client}: only
 * {@code LOCAL_ONLY} entries are legal (per-instance Caffeine, consistency by {@code local-ttl}).
 * Anything else fails the start — there is no Redis to back it and no pub/sub channel to
 * invalidate across instances. Free of Redis imports by design.
 */
public class JimmerLocalCacheFactory implements CacheFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(JimmerLocalCacheFactory.class);

    private final Map<String, JimmerCacheConfig.EntityCacheConfig> configByType;
    private final boolean operationLog;

    public JimmerLocalCacheFactory(JimmerCacheConfig config) {
        this.operationLog = config.logOperations();
        this.configByType = new LinkedHashMap<>();
        for (JimmerCacheConfig.EntityCacheConfig entity : config.entities()) {
            if (entity.mode() != CacheMode.LOCAL_ONLY) {
                throw new IllegalStateException(
                        "Entity " + entity.type() + " is configured with cache mode " + entity.mode()
                                + ", which requires quarkus-redis-client on the classpath; "
                                + "add the dependency or switch the entity to LOCAL_ONLY");
            }
            configByType.put(entity.type(), entity);
            LOGGER.info("Jimmer cache enabled for entity {} [mode=LOCAL_ONLY, no cross-instance invalidation]",
                    entity.type());
        }
    }

    @Override
    public Cache<?, ?> createObjectCache(ImmutableType type) {
        JimmerCacheConfig.EntityCacheConfig config = configByType.get(type.getJavaClass().getSimpleName());
        if (config == null) {
            return null;
        }
        return LocalOnlyCaches.create(type, null, config, null, operationLog);
    }

    @Override
    public Cache<?, ?> createAssociatedIdCache(ImmutableProp prop) {
        return associationCache(prop);
    }

    @Override
    public Cache<?, List<?>> createAssociatedIdListCache(ImmutableProp prop) {
        return associationCache(prop);
    }

  @SuppressWarnings("unchecked")
    private <T> T associationCache(ImmutableProp prop) {
        JimmerCacheConfig.EntityCacheConfig config = configByType.get(prop.getDeclaringType().getJavaClass().getSimpleName());
        if (config == null || !config.cacheAssociations() || !isCachedAssociation(prop)) {
            return null;
        }
        return (T) LocalOnlyCaches.create(null, prop, config, null, operationLog);
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
