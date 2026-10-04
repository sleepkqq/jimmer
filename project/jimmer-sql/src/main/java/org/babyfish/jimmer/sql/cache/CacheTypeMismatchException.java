package org.babyfish.jimmer.sql.cache;

/**
 * <p>Internal exception thrown when an object cache returns a value whose concrete
 * immutable type is not compatible with the cache's declared entity type. This can
 * only happen for a misbehaving custom cache or a shared/polymorphic cache that
 * returned the wrong concrete payload.</p>
 *
 * <p>It is a top-level type (rather than a package-private nested type of the wrapper)
 * so the optional object-cache query execution, which lives in another package, can
 * catch exactly this condition and fall back to ordinary SQL without swallowing
 * unrelated cache/database errors.</p>
 *
 * <p>Not part of the public API.</p>
 */
public class CacheTypeMismatchException extends IllegalArgumentException {

    public CacheTypeMismatchException(String message) {
        super(message);
    }

    public CacheTypeMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
