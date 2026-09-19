package io.quarkiverse.jimmer.runtime;

/**
 * Implemented by lazily initialized SqlClient wrappers so that infrastructure such as
 * {@link io.quarkiverse.jimmer.runtime.cache.impl.TransactionCacheOperatorFlusher} can check
 * whether the underlying SqlClient has already been built without triggering its initialization.
 */
public interface SqlClientInitializationAware {

    boolean isSqlClientInitialized();
}
