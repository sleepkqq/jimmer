package io.quarkiverse.jimmer.runtime.cache;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.BooleanSupplier;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;

/** Bypasses cache reads and fills when an external freshness condition is false. */
public class GuardedCache<K, V> implements Cache<K, V> {
    protected final Cache<K, V> delegate;
    protected final BooleanSupplier ready;

    private GuardedCache(Cache<K, V> delegate, BooleanSupplier ready) {
        this.delegate = Objects.requireNonNull(delegate);
        this.ready = Objects.requireNonNull(ready);
    }

    /** Preserves parameterized-cache dispatch and nullable metadata. */
    public static <K, V> Cache<K, V> wrap(Cache<K, V> cache, BooleanSupplier ready) {
        Objects.requireNonNull(ready);
        if (cache == null) return null;
        if (cache instanceof Cache.Parameterized<K, V> parameterized) {
            return new Parameterized<>(parameterized, ready);
        }
        return new GuardedCache<>(cache, ready);
    }

    @Override public ImmutableType type() { return delegate.type(); }
    @Override public ImmutableProp prop() { return delegate.prop(); }

    @Override
    public Map<K, V> getAll(Collection<K> keys, CacheEnvironment<K, V> env) {
        return ready.getAsBoolean() ? delegate.getAll(keys, env) : env.getLoader().loadAll(keys);
    }

    @Override
    public void deleteAll(Collection<K> keys, Object reason) {
        delegate.deleteAll(keys, reason);
    }

    private static final class Parameterized<K, V> extends GuardedCache<K, V> implements Cache.Parameterized<K, V> {
        private final Cache.Parameterized<K, V> parameterized;

        private Parameterized(Cache.Parameterized<K, V> delegate, BooleanSupplier ready) {
            super(delegate, ready);
            this.parameterized = delegate;
        }

        @Override
        public Map<K, V> getAll(Collection<K> keys, SortedMap<String, Object> parameters, CacheEnvironment<K, V> env) {
            return ready.getAsBoolean() ? parameterized.getAll(keys, parameters, env) : env.getLoader().loadAll(keys);
        }
    }
}
