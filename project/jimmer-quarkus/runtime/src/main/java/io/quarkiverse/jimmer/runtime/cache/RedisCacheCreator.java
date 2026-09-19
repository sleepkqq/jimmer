package io.quarkiverse.jimmer.runtime.cache;

import java.time.Duration;
import java.util.Objects;

import org.babyfish.jimmer.jackson.codec.JsonCodec;
import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheCreator;
import org.babyfish.jimmer.sql.cache.RemoteKeyPrefixProvider;
import org.babyfish.jimmer.sql.cache.caffeine.CaffeineHashBinder;
import org.babyfish.jimmer.sql.cache.caffeine.CaffeineValueBinder;
import org.babyfish.jimmer.sql.cache.chain.ChainCacheBuilder;
import org.babyfish.jimmer.sql.cache.chain.LoadingBinder;
import org.babyfish.jimmer.sql.cache.chain.SimpleBinder;
import org.babyfish.jimmer.sql.cache.spi.AbstractCacheCreator;
import org.jetbrains.annotations.NotNull;

import io.quarkus.redis.datasource.RedisDataSource;

/**
 * Jimmer {@link CacheCreator} on the Quarkus Redis (Vert.x) client: an optional Caffeine local
 * tier chained in front of a Redis tier. Ported from Jimmer's deprecated
 * {@code org.babyfish.jimmer.sql.cache.redis.quarkus.RedisCacheCreator} — framework-specific
 * cache wiring is the extension's responsibility.
 */
public class RedisCacheCreator extends AbstractCacheCreator {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public RedisCacheCreator(RedisDataSource redisDataSource) {
        this(redisDataSource, JsonCodec.jsonCodec());
    }

    public RedisCacheCreator(RedisDataSource redisDataSource, @NotNull JsonCodec<?> jsonCodec) {
        super(new Root(redisDataSource, jsonCodec));
    }

    protected RedisCacheCreator(Cfg cfg) {
        super(cfg);
    }

    @Override
    public <K, V> Cache<K, V> createForObject(ImmutableType type) {
        return SubscriptionCache.wrap(new ChainCacheBuilder<K, V>()
                .add(caffeineValueBinder(type))
                .add(redisValueBinder(type))
                .build(), args().tracker);
    }

    @Override
    public <K, V> Cache<K, V> createForProp(ImmutableProp prop, boolean multiView) {
        if (multiView) {
            return SubscriptionCache.wrap(new ChainCacheBuilder<K, V>()
                    .add(caffeineHashBinder(prop))
                    .add(redisHashBinder(prop))
                    .build(), args().tracker);
        }
        return SubscriptionCache.wrap(new ChainCacheBuilder<K, V>()
                .add(caffeineValueBinder(prop))
                .add(redisValueBinder(prop))
                .build(), args().tracker);
    }

    /**
     * Jimmer's {@link AbstractCacheCreator} reads a key-prefix provider from its config chain but
     * offers no builder method to set one — this fills the gap for our binders.
     */
    public RedisCacheCreator withKeyPrefixProvider(RemoteKeyPrefixProvider keyPrefixProvider) {
        return (RedisCacheCreator) newCacheCreator(new KeyPrefixProviderCfg(cfg, keyPrefixProvider));
    }

    /**
     * Wraps every binder of the built chains with a {@link LoggingBinder} decorator
     * ({@code jimmer.cache} logger).
     */
    public RedisCacheCreator withOperationLog() {
        return (RedisCacheCreator) newCacheCreator(new OperationLogCfg(cfg));
    }

    /** Sets the Redis batch deadline, not the cache-entry TTL. Defaults to 10 seconds. */
    public RedisCacheCreator withTimeout(Duration timeout) {
        return (RedisCacheCreator) newCacheCreator(new TimeoutCfg(cfg, requireTimeout(timeout)));
    }

    static Duration requireTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }

    @Override
    protected Args newArgs(Cfg cfg) {
        return new Args(cfg);
    }

    @Override
    protected CacheCreator newCacheCreator(Cfg cfg) {
        return new RedisCacheCreator(cfg);
    }

    private <K, V> LoadingBinder<K, V> caffeineValueBinder(ImmutableType type) {
        Args args = args();
        if (!args.useLocalCache) {
            return null;
        }
        LoadingBinder<K, V> binder = CaffeineValueBinder
                .<K, V>forObject(type)
                .subscribe(args.tracker)
                .maximumSize(args.localCacheMaximumSize)
                .duration(args.localCacheDuration)
                .build();
        return args.operationLog ? LoggingBinder.wrap(binder) : binder;
    }

    private <K, V> LoadingBinder<K, V> caffeineValueBinder(ImmutableProp prop) {
        Args args = args();
        if (!args.useLocalCache) {
            return null;
        }
        LoadingBinder<K, V> binder = CaffeineValueBinder
                .<K, V>forProp(prop)
                .subscribe(args.tracker)
                .maximumSize(args.localCacheMaximumSize)
                .duration(args.localCacheDuration)
                .build();
        return args.operationLog ? LoggingBinder.wrap(binder) : binder;
    }

    private <K, V> SimpleBinder<K, V> caffeineHashBinder(ImmutableProp prop) {
        Args args = args();
        if (!args.useMultiViewLocalCache) {
            return null;
        }
        SimpleBinder<K, V> binder = CaffeineHashBinder
                .<K, V>forProp(prop)
                .subscribe(args.tracker)
                .maximumSize(args.multiViewLocalCacheMaximumSize)
                .duration(args.multiViewLocalCacheDuration)
                .build();
        return args.operationLog ? LoggingBinder.wrap(binder) : binder;
    }

    private <K, V> SimpleBinder<K, V> redisValueBinder(ImmutableType type) {
        Args args = args();
        SimpleBinder<K, V> binder = RedisValueBinder
                .<K, V>forObject(type, args.jsonCodec)
                .publish(args.tracker)
                .keyPrefixProvider(args.effectiveKeyPrefixProvider)
                .duration(args.duration)
                .randomPercent(args.randomDurationPercent)
                .redis(args.redisDataSource)
                .timeout(args.timeout)
                .build()
                .lock(args.locker, args.lockWaitDuration, args.lockLeaseDuration);
        return args.operationLog ? LoggingBinder.wrap(binder) : binder;
    }

    private <K, V> SimpleBinder<K, V> redisValueBinder(ImmutableProp prop) {
        Args args = args();
        SimpleBinder<K, V> binder = RedisValueBinder
                .<K, V>forProp(prop, args.jsonCodec)
                .publish(args.tracker)
                .keyPrefixProvider(args.effectiveKeyPrefixProvider)
                .duration(args.duration)
                .randomPercent(args.randomDurationPercent)
                .redis(args.redisDataSource)
                .timeout(args.timeout)
                .build()
                .lock(args.locker, args.lockWaitDuration, args.lockLeaseDuration);
        return args.operationLog ? LoggingBinder.wrap(binder) : binder;
    }

    private <K, V> SimpleBinder.Parameterized<K, V> redisHashBinder(ImmutableProp prop) {
        Args args = args();
        SimpleBinder.Parameterized<K, V> binder = RedisHashBinder
                .<K, V>forProp(prop, args.jsonCodec)
                .publish(args.tracker)
                .keyPrefixProvider(args.effectiveKeyPrefixProvider)
                .duration(args.multiVewDuration)
                .randomPercent(args.randomDurationPercent)
                .redis(args.redisDataSource)
                .timeout(args.timeout)
                .build()
                .lock(args.locker, args.lockWaitDuration, args.lockLeaseDuration);
        return args.operationLog ? LoggingBinder.wrap(binder) : binder;
    }

    private static class Root extends Cfg {

        final RedisDataSource redisDataSource;

        final JsonCodec<?> jsonCodec;

        private Root(RedisDataSource redisDataSource, @NotNull JsonCodec<?> jsonCodec) {
            super(null);
            this.redisDataSource = Objects.requireNonNull(redisDataSource, "redisDataSource cannot be null");
            this.jsonCodec = jsonCodec;
        }
    }

    private static class KeyPrefixProviderCfg extends Cfg {

        final RemoteKeyPrefixProvider keyPrefixProvider;

        KeyPrefixProviderCfg(Cfg prev, RemoteKeyPrefixProvider keyPrefixProvider) {
            super(prev);
            this.keyPrefixProvider = keyPrefixProvider;
        }
    }

    private static class OperationLogCfg extends Cfg {

        OperationLogCfg(Cfg prev) {
            super(prev);
        }
    }

    private static class TimeoutCfg extends Cfg {
        final Duration timeout;

        TimeoutCfg(Cfg prev, Duration timeout) {
            super(prev);
            this.timeout = timeout;
        }
    }

    static class Args extends AbstractCacheCreator.Args {

        final RedisDataSource redisDataSource;

        final JsonCodec<?> jsonCodec;

        final RemoteKeyPrefixProvider effectiveKeyPrefixProvider;

        final boolean operationLog;
        final Duration timeout;

        Args(Cfg cfg) {
            super(cfg);
            Root root = cfg.as(Root.class);
            this.redisDataSource = root.redisDataSource;
            this.jsonCodec = root.jsonCodec;
            KeyPrefixProviderCfg prefixCfg = cfg.as(KeyPrefixProviderCfg.class);
            this.effectiveKeyPrefixProvider = prefixCfg != null ? prefixCfg.keyPrefixProvider : this.keyPrefixProvider;
            this.operationLog = cfg.as(OperationLogCfg.class) != null;
            TimeoutCfg timeoutCfg = cfg.as(TimeoutCfg.class);
            this.timeout = timeoutCfg != null ? timeoutCfg.timeout : DEFAULT_TIMEOUT;
        }
    }
}
