package org.babyfish.jimmer.sql.fetcher.impl;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Derives a read client that keeps exactly the given entity types SQL-fresh: their object
 * caches, the property caches they declare (association edges, resolvers) and the property
 * caches targeting them are bypassed, together with their whole inheritance line. Every other
 * reachable type keeps its own cache policy, as in an ordinary read. Derived clients only
 * disable more, so an already cache-disabled client stays fully disabled.
 */
public final class CacheIsolation {

    private CacheIsolation() {}

    public static JSqlClientImplementor bypassing(
            JSqlClientImplementor sqlClient,
            Collection<ImmutableType> freshTypes
    ) {
        Set<ImmutableType> types = new LinkedHashSet<>();
        for (ImmutableType type : freshTypes) {
            types.addAll(type.getAllTypes());
            types.addAll(type.getAllDerivedTypes());
        }
        return sqlClient.caches(cfg -> {
            for (ImmutableType type : types) {
                cfg.disable(type);
                for (ImmutableProp prop : type.getProps().values()) {
                    cfg.disable(prop);
                }
            }
        });
    }
}
