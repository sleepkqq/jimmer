package io.quarkiverse.jimmer.runtime.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.babyfish.jimmer.jackson.codec.JsonCodec;
import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.babyfish.jimmer.sql.cache.RemoteKeyPrefixProvider;
import org.babyfish.jimmer.sql.cache.spi.AbstractRemoteValueBinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;

/**
 * Redis tier of the Jimmer object/reference cache on the Quarkus Redis (Vert.x) client.
 * Ported from Jimmer's deprecated {@code org.babyfish.jimmer.sql.cache.redis.quarkus} package —
 * framework-specific binders are the extension's responsibility.
 *
 * <p>Writes and deletes go through the low-level client as ONE pipelined batch: per-key TTL rules
 * out {@code MSET} (no TTL argument), and a per-key {@code SET}/{@code GETDEL} loop would cost a
 * network round-trip per key — 20 written entries used to take ~25ms of pure wire chatter.</p>
 */
public class RedisValueBinder<K, V> extends AbstractRemoteValueBinder<K, V> {

    private final ValueCommands<String, byte[]> operations;

    private final Redis redis;
    private final Duration timeout;

    protected RedisValueBinder(
            @Nullable ImmutableType type,
            @Nullable ImmutableProp prop,
            @Nullable CacheTracker tracker,
            @NotNull JsonCodec<?> jsonCodec,
            @Nullable RemoteKeyPrefixProvider keyPrefixProvider,
            @NotNull Duration duration,
            int randomPercent,
            @NotNull RedisDataSource redisDataSource) {
        this(type, prop, tracker, jsonCodec, keyPrefixProvider, duration, randomPercent, redisDataSource,
                RedisCacheCreator.DEFAULT_TIMEOUT);
    }

    private RedisValueBinder(
            ImmutableType type, ImmutableProp prop, CacheTracker tracker, JsonCodec<?> jsonCodec,
            RemoteKeyPrefixProvider keyPrefixProvider, Duration duration, int randomPercent,
            RedisDataSource redisDataSource, Duration timeout) {
        super(type, prop, tracker, jsonCodec, keyPrefixProvider, duration, randomPercent);
        this.operations = redisDataSource.value(byte[].class);
        this.redis = redisDataSource.getReactive().getRedis();
        this.timeout = RedisCacheCreator.requireTimeout(timeout);
    }

    @Override
    protected List<byte[]> read(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, byte[]> values = operations.mget(keys.toArray(new String[0]));
        return new ArrayList<>(values.values());
    }

    @Override
    protected void write(Map<String, byte[]> map) {
        if (map.isEmpty()) {
            return;
        }
        List<Request> requests = new ArrayList<>(map.size());
        for (Map.Entry<String, byte[]> e : map.entrySet()) {
            requests.add(Request.cmd(Command.SET)
                    .arg(e.getKey())
                    .arg(e.getValue())
                    .arg("PX")
                    .arg(nextExpireMillis()));
        }
        redis.batch(requests).await().atMost(timeout);
    }

    @Override
    protected void deleteAllSerializedKeys(List<String> serializedKeys) {
        if (serializedKeys.isEmpty()) {
            return;
        }
        Request del = Request.cmd(Command.DEL);
        for (String key : serializedKeys) {
            del.arg(key);
        }
        redis.send(del).await().atMost(timeout);
    }

    @Override
    protected boolean matched(@Nullable Object reason) {
        return "redis".equals(reason);
    }

    @NotNull
    public static <K, V> Builder<K, V> forObject(ImmutableType type, @NotNull JsonCodec<?> jsonCodec) {
        return new Builder<>(type, null, jsonCodec);
    }

    @NotNull
    public static <K, V> Builder<K, V> forProp(ImmutableProp prop, @NotNull JsonCodec<?> jsonCodec) {
        return new Builder<>(null, prop, jsonCodec);
    }

    public static class Builder<K, V> extends AbstractBuilder<K, V, Builder<K, V>> {

        private RedisDataSource redisDataSource;
        private Duration timeout = RedisCacheCreator.DEFAULT_TIMEOUT;

        protected Builder(ImmutableType type, ImmutableProp prop, JsonCodec<?> jsonCodec) {
            super(type, prop, jsonCodec);
        }

        public Builder<K, V> redis(RedisDataSource redisDataSource) {
            this.redisDataSource = redisDataSource;
            return this;
        }

        public RedisValueBinder<K, V> build() {
            if (redisDataSource == null) {
                throw new IllegalStateException("RedisDataSource has not been specified");
            }
            return new RedisValueBinder<>(
                    type, prop, tracker, jsonCodec, keyPrefixProvider, duration, randomPercent, redisDataSource, timeout);
        }

        /** Deadline for a pipelined Redis operation, independent of entry TTL. Defaults to 10 seconds. */
        public Builder<K, V> timeout(Duration timeout) {
            this.timeout = RedisCacheCreator.requireTimeout(timeout);
            return this;
        }
    }
}
