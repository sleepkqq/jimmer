package io.quarkiverse.jimmer.runtime.cache;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.Supplier;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.cache.CacheTracker;

/** Bypasses the entire chain, including cache fills, while its subscription is untrusted. */
class SubscriptionCache<K, V> implements Cache<K, V> {
    protected final Cache<K, V> delegate;
    protected final QuarkusRedisCacheTracker tracker;

    private SubscriptionCache(Cache<K, V> delegate, QuarkusRedisCacheTracker tracker) {
        this.delegate = delegate;
        this.tracker = tracker;
    }

    static <K, V> Cache<K, V> wrap(Cache<K, V> cache, CacheTracker tracker) {
        QuarkusRedisCacheTracker managed = tracker instanceof QuarkusRedisCacheTracker value ? value : null;
        if (cache instanceof Cache.Parameterized<K, V> parameterized) {
            return new Parameterized<>(parameterized, managed);
        }
        return new SubscriptionCache<>(cache, managed);
    }

    @Override public ImmutableType type() { return delegate.type(); }
    @Override public ImmutableProp prop() { return delegate.prop(); }

    @Override
    public Map<K, V> getAll(Collection<K> keys, CacheEnvironment<K, V> env) {
        return read(keys, () -> delegate.getAll(keys, env), () -> env.getLoader().loadAll(keys));
    }

    protected Map<K, V> read(Collection<K> keys, Supplier<Map<K, V>> cached, Supplier<Map<K, V>> database) {
        if (keys.isEmpty()) return Collections.emptyMap();
        CacheTracker.InvalidateEvent interest = prop() == null ? new CacheTracker.InvalidateEvent(type(), keys) :
                new CacheTracker.InvalidateEvent(prop(), keys);
        try (CacheLoadScope scope = new CacheLoadScope(interest)) {
            if (tracker != null) return tracker.read(scope, cached, database);
            try { return cached.get(); }
            finally { if (scope.invalidated) delegate.deleteAll(keys); }
        }
    }

    @Override
    public void deleteAll(Collection<K> keys, Object reason) {
        // Invalidations must still run (and propagate failures) even while reads bypass caches.
        delegate.deleteAll(keys, reason);
    }

    private static final class Parameterized<K, V> extends SubscriptionCache<K, V> implements Cache.Parameterized<K, V> {
        private final Cache.Parameterized<K, V> parameterized;

        private Parameterized(Cache.Parameterized<K, V> delegate, QuarkusRedisCacheTracker tracker) {
            super(delegate, tracker);
            parameterized = delegate;
        }

        @Override
        public Map<K, V> getAll(Collection<K> keys, SortedMap<String, Object> parameters, CacheEnvironment<K, V> env) {
            return read(keys, () -> parameterized.getAll(keys, parameters, env), () -> env.getLoader().loadAll(keys));
        }
    }
}
