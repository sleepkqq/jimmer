package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.meta.TypedProp;
import org.babyfish.jimmer.sql.event.DatabaseEvent;
import org.jetbrains.annotations.Nullable;

public interface Caches {

    @Nullable
    default <K, V> Cache<K, V> getObjectCache(Class<V> type) {
        return getObjectCache(ImmutableType.get(type));
    }

    @Nullable
    <K, V> Cache<K, V> getObjectCache(ImmutableType type);

    /** Whether ordinary entity reads must not consume this cache as a complete entity snapshot. */
    default boolean isObjectCacheContentOnly(ImmutableType type) {
        return false;
    }

    /**
     * Whether a converted DTO/View root slot of this type may use its declared object cache.
     * The default admits every type; an application policy can deny own/editable/command reads.
     */
    default boolean isObjectCacheAdmissible(ImmutableType type) {
        return true;
    }

    /**
     * Whether this freshly read row may be served from its declared object cache.
     * The default keeps the query-level verdict.
     */
    default boolean isObjectCacheAdmissible(ImmutableType type, Object row) {
        return isObjectCacheAdmissible(type);
    }

    @Nullable
    default <K, V> Cache<K, V> getPropertyCache(TypedProp<?, ?> prop) {
        return getPropertyCache(prop.unwrap());
    }

    @Nullable
    <K, V> Cache<K, V> getPropertyCache(ImmutableProp prop);

    CacheAbandonedCallback getAbandonedCallback();

    boolean isAffectedBy(DatabaseEvent e);
}
