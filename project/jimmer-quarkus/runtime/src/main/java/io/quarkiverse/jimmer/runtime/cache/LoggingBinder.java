package io.quarkiverse.jimmer.runtime.cache;

import java.util.Collection;
import java.util.Map;
import java.util.SortedMap;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.chain.Binder;
import org.babyfish.jimmer.sql.cache.chain.CacheChain;
import org.babyfish.jimmer.sql.cache.chain.LoadingBinder;
import org.babyfish.jimmer.sql.cache.chain.SimpleBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tier logging decorators for the Jimmer cache chain under the {@code jimmer.cache} logger
 * (companion of the {@code jimmer.sql} compact SQL log):
 *
 * <pre>
 * CACHE GET Caffeine(User) 20 keys (19 hit) | 0ms
 * CACHE GET Redis(User.handle) 1 key (0 hit) | 1ms
 * CACHE SET Redis(User) 1 entry | 2ms
 * CACHE DELETE Caffeine(User) 2 keys
 * </pre>
 *
 * A key that shows no hit on any tier proceeds to the database loader and surfaces in the SQL log
 * as a {@code [LOAD]} statement. Enabled with {@code quarkus.jimmer.cache.log-operations}.
 */
final class LoggingBinder {

    private static final Logger LOG = LoggerFactory.getLogger("jimmer.cache");

    private LoggingBinder() {
    }

    static <K, V> LoadingBinder<K, V> wrap(LoadingBinder<K, V> binder) {
        return binder == null ? null : new Loading<>(binder);
    }

    static <K, V> SimpleBinder<K, V> wrap(SimpleBinder<K, V> binder) {
        return binder == null ? null : new Simple<>(binder);
    }

    static <K, V> SimpleBinder.Parameterized<K, V> wrap(SimpleBinder.Parameterized<K, V> binder) {
        return binder == null ? null : new Parameterized<>(binder);
    }

    private static String describe(Binder<?> binder) {
        String className = binder.getClass().getSimpleName();
        String tier;
        if (className.contains("Caffeine")) {
            tier = "Caffeine";
        } else if (className.contains("Redis")) {
            tier = "Redis";
        } else {
            tier = className;
        }
        ImmutableType type = binder.type();
        ImmutableProp prop = binder.prop();
        String target = type != null
                ? type.getJavaClass().getSimpleName()
                : prop.getDeclaringType().getJavaClass().getSimpleName() + '.' + prop.getName();
        return tier + '(' + target + ')';
    }

    private static String keys(int count) {
        return count == 1 ? "1 key" : count + " keys";
    }

    private static void logGet(String description, int requested, int hits, long startNanos) {
        LOG.info("CACHE GET {} {} ({} hit) | {}ms",
                description, keys(requested), hits, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static void logSet(String description, int entries, long startNanos) {
        LOG.info("CACHE SET {} {} | {}ms",
                description, entries == 1 ? "1 entry" : entries + " entries",
                (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static void logDelete(String description, int count) {
        LOG.info("CACHE DELETE {} {}", description, keys(count));
    }

    /**
     * Decorates the local loading tier (Caffeine). Its {@code getAll} transparently pulls misses
     * through the rest of the chain, so per-call hits are recovered by counting the keys the
     * wrapped chain was asked to load ({@code ThreadLocal} — the loading cache resolves misses
     * synchronously on the calling thread).
     */
    private static final class Loading<K, V> implements LoadingBinder<K, V> {

        private final LoadingBinder<K, V> raw;
        private final String description;
        private final ThreadLocal<Integer> chainLoads = ThreadLocal.withInitial(() -> 0);

        private Loading(LoadingBinder<K, V> raw) {
            this.raw = raw;
            this.description = describe(raw);
        }

        @Override
        public void initialize(CacheChain<K, V> chain) {
            raw.initialize(keys -> {
                chainLoads.set(chainLoads.get() + keys.size());
                return chain.loadAll(keys);
            });
        }

        @Override
        public Map<K, V> getAll(Collection<K> keys) {
            if (keys.isEmpty()) {
                return raw.getAll(keys);
            }
            chainLoads.set(0);
            long start = System.nanoTime();
            try {
                Map<K, V> result = raw.getAll(keys);
                logGet(description, keys.size(), keys.size() - chainLoads.get(), start);
                return result;
            } finally {
                chainLoads.remove();
            }
        }

        @Override
        public void deleteAll(Collection<K> keys, Object reason) {
            if (!keys.isEmpty()) {
                logDelete(description, keys.size());
            }
            raw.deleteAll(keys, reason);
        }

        @Override
        public ImmutableType type() {
            return raw.type();
        }

        @Override
        public ImmutableProp prop() {
            return raw.prop();
        }

        @Override
        public TrackingMode tracingMode() {
            return raw.tracingMode();
        }
    }

    private static class Simple<K, V> implements SimpleBinder<K, V> {

        final SimpleBinder<K, V> raw;
        final String description;

        private Simple(SimpleBinder<K, V> raw) {
            this.raw = raw;
            this.description = describe(raw);
        }

        @Override
        public Map<K, V> getAll(Collection<K> keys) {
            if (keys.isEmpty()) {
                return raw.getAll(keys);
            }
            long start = System.nanoTime();
            Map<K, V> result = raw.getAll(keys);
            logGet(description, keys.size(), result.size(), start);
            return result;
        }

        @Override
        public void setAll(Map<K, V> map) {
            long start = System.nanoTime();
            raw.setAll(map);
            logSet(description, map.size(), start);
        }

        @Override
        public void deleteAll(Collection<K> keys, Object reason) {
            if (!keys.isEmpty()) {
                logDelete(description, keys.size());
            }
            raw.deleteAll(keys, reason);
        }

        @Override
        public ImmutableType type() {
            return raw.type();
        }

        @Override
        public ImmutableProp prop() {
            return raw.prop();
        }

        @Override
        public TrackingMode tracingMode() {
            return raw.tracingMode();
        }
    }

    private static final class Parameterized<K, V> extends Simple<K, V> implements SimpleBinder.Parameterized<K, V> {

        private Parameterized(SimpleBinder.Parameterized<K, V> raw) {
            super(raw);
        }

        @Override
        public Map<K, V> getAll(Collection<K> keys, SortedMap<String, Object> parameterMap) {
            if (keys.isEmpty()) {
                return ((SimpleBinder.Parameterized<K, V>) raw).getAll(keys, parameterMap);
            }
            long start = System.nanoTime();
            Map<K, V> result = ((SimpleBinder.Parameterized<K, V>) raw).getAll(keys, parameterMap);
            logGet(description, keys.size(), result.size(), start);
            return result;
        }

        @Override
        public void setAll(Map<K, V> map, SortedMap<String, Object> parameterMap) {
            long start = System.nanoTime();
            ((SimpleBinder.Parameterized<K, V>) raw).setAll(map, parameterMap);
            logSet(description, map.size(), start);
        }
    }
}
