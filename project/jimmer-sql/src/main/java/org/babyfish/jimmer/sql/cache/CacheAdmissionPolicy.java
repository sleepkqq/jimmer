package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableType;

/**
 * Application-supplied policy that decides whether a converted DTO/View root slot may be
 * served from a declared object cache as one complete content snapshot.
 *
 * <p>The ORM deliberately knows nothing about business identity. An application that has
 * viewer-sensitive content installs a bean and answers from its request context. The default
 * policy admits everything, so existing applications keep their current behavior.</p>
 */
@FunctionalInterface
public interface CacheAdmissionPolicy {

    /**
     * Whether converted root slots of this type may use a declared object cache at all.
     * This is the query-level gate: a denial keeps every row of the query SQL-fresh.
     */
    boolean isAdmissible(ImmutableType type);

    /**
     * Whether this exact row may be served from the object cache.
     *
     * <p>{@code row} is the freshly read, retention-masked instance of the cached view:
     * only properties of that view are loaded, and every value in it comes from the SQL
     * executed by this query. An application can therefore decide from current foreign
     * keys, ids or version columns whether the cached snapshot is still appropriate for
     * the row, while sibling rows of the same page stay cached.</p>
     *
     * <p>The default keeps the query-level verdict.</p>
     */
    default boolean isAdmissible(ImmutableType type, Object row) {
        return isAdmissible(type);
    }
}
