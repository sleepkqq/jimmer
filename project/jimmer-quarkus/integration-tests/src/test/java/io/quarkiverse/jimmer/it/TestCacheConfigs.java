package io.quarkiverse.jimmer.it;

import java.time.Duration;
import java.util.List;

import io.quarkiverse.jimmer.runtime.cache.CacheMode;
import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheConfig;

/**
 * Hand-built {@link JimmerCacheConfig} instances for cache tests — the real config is only
 * materialized by Quarkus config mapping, which cannot be varied per test method.
 */
final class TestCacheConfigs {

    private TestCacheConfigs() {
    }

    static JimmerCacheConfig config(JimmerCacheConfig.EntityCacheConfig... entities) {
        return config(false, entities);
    }

    static JimmerCacheConfig config(boolean logOperations, JimmerCacheConfig.EntityCacheConfig... entities) {
        return new JimmerCacheConfig() {
            @Override
            public List<EntityCacheConfig> entities() {
                return List.of(entities);
            }

            @Override
            public boolean logOperations() {
                return logOperations;
            }
        };
    }

    static JimmerCacheConfig.EntityCacheConfig entity(String type, CacheMode mode) {
        return new JimmerCacheConfig.EntityCacheConfig() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public CacheMode mode() {
                return mode;
            }

            @Override
            public Duration remoteTtl() {
                return Duration.ofMinutes(30);
            }

            @Override
            public Duration localTtl() {
                return Duration.ofMinutes(1);
            }

            @Override
            public int localMaxSize() {
                return 100;
            }

            @Override
            public boolean cacheAssociations() {
                return true;
            }

            @Override
            public int randomPercent() {
                return 25;
            }
        };
    }
}
