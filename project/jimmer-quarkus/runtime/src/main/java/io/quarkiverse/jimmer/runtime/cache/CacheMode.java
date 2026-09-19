package io.quarkiverse.jimmer.runtime.cache;

/**
 * Jimmer entity cache mode.
 *
 * <ul>
 * <li>{@link #LOCAL_ONLY} — single local Caffeine tier, no data stored in Redis. When Redis is present,
 * invalidations still propagate across instances via the tracker's pub/sub channel; without Redis the
 * cache is per-instance and consistency relies on {@code local-ttl} alone.</li>
 * <li>{@link #REMOTE_ONLY} — single remote (Redis) tier. No per-instance staleness, every read hits Redis.</li>
 * <li>{@link #FULL} — two tiers: local Caffeine (L1) in front of remote Redis (L2). Lowest latency on hot keys;
 * requires a {@link org.babyfish.jimmer.sql.cache.CacheTracker} to invalidate the local tier across instances.</li>
 * </ul>
 */
public enum CacheMode {
    LOCAL_ONLY,
    REMOTE_ONLY,
    FULL
}
