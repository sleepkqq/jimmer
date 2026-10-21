package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableType;

/**
 * Application-supplied policy that decides whether a converted DTO/View root slot may be
 * served from a declared object cache as one complete content snapshot.
 *
 * <p>The ORM deliberately knows nothing about business identity. An application that has
 * viewer-sensitive content installs a bean and answers from its request context. The default
 * policy admits every type, so existing applications keep their current behavior.</p>
 */
@FunctionalInterface
public interface CacheAdmissionPolicy {

    boolean isAdmissible(ImmutableType type);
}
