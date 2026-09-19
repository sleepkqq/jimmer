package io.quarkiverse.jimmer.runtime.cache;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.babyfish.jimmer.sql.cache.chain.SimpleBinder;
import org.babyfish.jimmer.sql.cache.spi.AbstractTrackingProducerBinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Storage-less chain link that only publishes invalidation events to the {@link CacheTracker}.
 * Backs the {@code LOCAL_ONLY} mode: Jimmer fires invalidation publications solely from
 * tracking-producer binders (normally the remote tier), so a pure-Caffeine chain would never
 * notify other instances — this binder supplies the publication without storing anything.
 */
public class InvalidationPublishBinder<K, V> extends AbstractTrackingProducerBinder<K>
        implements SimpleBinder<K, V> {

    public InvalidationPublishBinder(
            @Nullable ImmutableType type,
            @Nullable ImmutableProp prop,
            @NotNull CacheTracker tracker) {
        super(type, prop, tracker);
    }

    /**
     * Must be mutable: Jimmer's {@code ChainCacheImpl.SimpleNode} treats the returned map as its own
     * accumulator and {@code putAll}s the values loaded from the rest of the chain into it, so an
     * immutable empty map makes every miss fail with {@link UnsupportedOperationException}.
     */
    @Override
    public Map<K, V> getAll(Collection<K> keys) {
        return new LinkedHashMap<>();
    }

    @Override
    public void setAll(Map<K, V> map) {
    }

    @Override
    protected void deleteAllKeys(Collection<K> keys) {
    }

    @Override
    protected boolean matched(@Nullable Object reason) {
        return false;
    }
}
