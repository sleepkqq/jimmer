package org.babyfish.jimmer.sql.ast.impl.query;

/**
 * Signals that an object cache returned a concrete type incompatible with its declared type, so
 * query hydration can fall back to the original SQL on exactly this condition.
 */
public final class CacheTypeMismatchException extends IllegalArgumentException {

    public CacheTypeMismatchException(String message) {
        super(message);
    }

    public CacheTypeMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
