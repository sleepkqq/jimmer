package io.quarkiverse.jimmer.runtime.cache;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheFactory;

/** Applies an external freshness condition to every cache supplied by a factory. */
public class GuardedCacheFactory implements CacheFactory {
    private final CacheFactory delegate;
    private final BooleanSupplier ready;

    public GuardedCacheFactory(CacheFactory delegate, BooleanSupplier ready) {
        this.delegate = Objects.requireNonNull(delegate);
        this.ready = Objects.requireNonNull(ready);
    }

    @Override
    public Cache<?, ?> createObjectCache(ImmutableType type) {
        return GuardedCache.wrap(delegate.createObjectCache(type), ready);
    }

    @Override
    public Cache<?, ?> createAssociatedIdCache(ImmutableProp prop) {
        return GuardedCache.wrap(delegate.createAssociatedIdCache(prop), ready);
    }

    @Override
    public Cache<?, List<?>> createAssociatedIdListCache(ImmutableProp prop) {
        return GuardedCache.wrap(delegate.createAssociatedIdListCache(prop), ready);
    }

    @Override
    public Cache<?, ?> createResolverCache(ImmutableProp prop) {
        return GuardedCache.wrap(delegate.createResolverCache(prop), ready);
    }
}
