package io.quarkiverse.jimmer.runtime.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.babyfish.jimmer.sql.cache.CacheTracker;

/** Per-call fence state; nested fetchers restore their caller's scope. Never retained between requests. */
final class CacheLoadScope implements AutoCloseable {
    private static final ThreadLocal<CacheLoadScope> CURRENT = new ThreadLocal<>();
    private final CacheLoadScope previous = CURRENT.get();
    final CacheTracker.InvalidateEvent interest;
    final Map<String, String> tokens = new HashMap<>();
    volatile boolean invalidated;

    CacheLoadScope(CacheTracker.InvalidateEvent interest) {
        this.interest = interest;
        CURRENT.set(this);
    }

    static CacheLoadScope current() { return CURRENT.get(); }

    void invalidate(CacheTracker.InvalidateEvent event) {
        if (interest.getType().equals(event.getType()) && Objects.equals(interest.getProp(), event.getProp()) &&
                event.getIds().stream().anyMatch(interest.getIds()::contains)) {
            invalidated = true;
        }
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
