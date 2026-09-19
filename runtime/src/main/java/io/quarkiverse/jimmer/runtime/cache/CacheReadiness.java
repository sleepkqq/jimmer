package io.quarkiverse.jimmer.runtime.cache;

/** External freshness contract. The namespace must be stable for this provider's lifetime. */
public interface CacheReadiness {
    boolean ready();
    String namespace();
}
