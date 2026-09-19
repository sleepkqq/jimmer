package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import jakarta.inject.Inject;
import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.junit.jupiter.api.Test;
import io.quarkiverse.jimmer.runtime.cache.GuardedCache;
import io.quarkiverse.jimmer.runtime.cache.GuardedCacheFactory;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GuardedCacheTest {
    @Inject JSqlClient sql;
    @Inject DataSource dataSource;

    @Test
    void bypassDoesNotReadOrFillCacheAndInvalidationFailuresPropagate() throws Exception {
        var ready = new AtomicBoolean();
        var delegate = new RecordingCache();
        var cache = GuardedCache.wrap(delegate, ready::get);
        assertNull(cache.type());
        assertNull(cache.prop());
        try (var connection = dataSource.getConnection()) {
            var env = new CacheEnvironment<Long, String>(sql, connection, keys -> Map.of(1L, "database"), false);
            assertEquals("database", cache.getAll(List.of(1L), env).get(1L));
            assertEquals(0, delegate.reads);
            ready.set(true);
            assertEquals("cache", cache.getAll(List.of(1L), env).get(1L));
            assertEquals(1, delegate.reads);
            ready.set(false);
            cache.deleteAll(List.of(1L), "cdc");
            assertEquals("cdc", delegate.reason);
            delegate.fail = true;
            assertThrows(IllegalStateException.class, () -> cache.deleteAll(List.of(1L), "cdc"));
        }
    }

    @Test
    void parameterizedDispatchAndFactoryOptOutArePreserved() throws Exception {
        var ready = new AtomicBoolean(true);
        var delegate = new RecordingCache();
        var cache = assertInstanceOf(Cache.Parameterized.class, GuardedCache.wrap(delegate, ready::get));
        var parameters = new TreeMap<String, Object>(Map.of("locale", "ru"));
        try (var connection = dataSource.getConnection()) {
            var env = new CacheEnvironment<Long, String>(sql, connection, keys -> Map.of(1L, "database"), false);
            assertEquals("cache", cache.getAll(List.of(1L), parameters, env).get(1L));
            assertSame(parameters, delegate.parameters);
            ready.set(false);
            assertEquals("database", cache.getAll(List.of(1L), parameters, env).get(1L));
            assertEquals(1, delegate.reads);
        }
        var factory = new GuardedCacheFactory(new CacheFactory() {}, ready::get);
        assertNull(factory.createObjectCache(null));
        assertNull(factory.createAssociatedIdCache(null));
        assertNull(factory.createAssociatedIdListCache(null));
        assertNull(factory.createResolverCache(null));
    }

    private static final class RecordingCache implements Cache.Parameterized<Long, String> {
        int reads;
        Object reason;
        boolean fail;
        SortedMap<String, Object> parameters;
        @Override public ImmutableType type() { return null; }
        @Override public ImmutableProp prop() { return null; }
        @Override public Map<Long, String> getAll(Collection<Long> keys, CacheEnvironment<Long, String> env) {
            return getAll(keys, new TreeMap<>(), env);
        }
        @Override public Map<Long, String> getAll(Collection<Long> keys, SortedMap<String, Object> parameters,
                CacheEnvironment<Long, String> env) {
            reads++;
            this.parameters = parameters;
            return Map.of(1L, "cache");
        }
        @Override public void deleteAll(Collection<Long> keys, Object reason) {
            if (fail) throw new IllegalStateException("invalidation failed");
            this.reason = reason;
        }
    }
}
