package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.junit.jupiter.api.Test;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.BookStoreDraft;
import io.quarkiverse.jimmer.runtime.cache.CacheReadiness;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(ConfiguredCacheGuardTest.Profile.class)
class ConfiguredCacheGuardTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.arc.exclude-types", "io.quarkiverse.jimmer.it.config.CacheConfig",
                "test.cache-readiness", "true",
                "quarkus.jimmer.cache.guard.enabled", "true",
                "quarkus.jimmer.cache.entities[0].type", "BookStore",
                "quarkus.jimmer.cache.entities[0].mode", "FULL"
            );
        }
    }

    @ApplicationScoped
    @IfBuildProperty(name = "test.cache-readiness", stringValue = "true")
    public static class Readiness implements CacheReadiness {
        private volatile boolean ready;
        @Override public boolean ready() { return ready; }
        @Override public String namespace() { return "guard-test"; }
        public void setReady(boolean value) { ready = value; }
    }

    @Inject CacheFactory factory;
    @Inject Readiness readiness;
    @Inject JSqlClient sql;
    @Inject DataSource dataSource;
    @Inject RedisDataSource redis;

    @Test
    @SuppressWarnings("unchecked")
    void yamlConfigAppliesGuardAndNamespaceToTheExistingFactory() throws Exception {
        Cache<Long, BookStore> cache = (Cache<Long, BookStore>) factory.createObjectCache(ImmutableType.get(BookStore.class));
        var loads = new AtomicInteger();
        try (var connection = dataSource.getConnection()) {
            var env = new CacheEnvironment<Long, BookStore>(sql, connection, keys -> {
                loads.incrementAndGet();
                return Map.of(-99L, BookStoreDraft.$.produce(draft -> {
                    draft.setId(-99L);
                    draft.setName("guarded");
                }));
            }, false);
            cache.getAll(List.of(-99L), env);
            cache.getAll(List.of(-99L), env);
            assertEquals(2, loads.get());
            readiness.setReady(true);
            cache.getAll(List.of(-99L), env);
            cache.getAll(List.of(-99L), env);
            assertEquals(3, loads.get());
            assertTrue(redis.execute("KEYS", "*guard-test*BookStore*-99*").size() > 0);
            readiness.setReady(false);
            cache.deleteAll(List.of(-99L), null);
            readiness.setReady(true);
            cache.getAll(List.of(-99L), env);
            assertEquals(4, loads.get());
            readiness.setReady(false);
            cache.getAll(List.of(-99L), env);
            assertEquals(5, loads.get());
        }
    }
}
