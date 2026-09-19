package io.quarkiverse.jimmer.runtime.exception;

/**
 * Marker for data-access failures that are transient and may succeed if the operation is retried
 * (serialization failures, deadlocks). Useful as a predicate for retry logic.
 */
public interface TransientDataAccessException {
}
