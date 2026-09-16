package io.quarkiverse.jimmer.runtime.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.babyfish.jimmer.jackson.codec.JsonCodec;
import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.babyfish.jimmer.sql.cache.RemoteKeyPrefixProvider;
import org.babyfish.jimmer.sql.cache.spi.AbstractRemoteHashBinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.quarkus.redis.datasource.RedisDataSource;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;

/**
 * Redis tier of the Jimmer multi-view association cache on the Quarkus Redis (Vert.x) client.
 * Ported from Jimmer's deprecated {@code org.babyfish.jimmer.sql.cache.redis.quarkus} package —
 * framework-specific binders are the extension's responsibility.
 *
 * <p>Reads, writes and deletes go through the low-level client as ONE pipelined batch instead of
 * a command per key. TTL is applied with {@code PEXPIRE} — the ported code used {@code GETEX},
 * which is a string command and fails with {@code WRONGTYPE} against these hash keys.</p>
 */
public class RedisHashBinder<K, V> extends AbstractRemoteHashBinder<K, V> {

    private final Redis redis;
    private final Duration timeout;
    private final CacheTracker tracker;

    protected RedisHashBinder(
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

    private RedisHashBinder(
            ImmutableType type, ImmutableProp prop, CacheTracker tracker, JsonCodec<?> jsonCodec,
            RemoteKeyPrefixProvider keyPrefixProvider, Duration duration, int randomPercent,
            RedisDataSource redisDataSource, Duration timeout) {
        super(type, prop, tracker, jsonCodec, keyPrefixProvider, duration, randomPercent);
        this.redis = redisDataSource.getReactive().getRedis();
        this.timeout = RedisCacheCreator.requireTimeout(timeout);
        this.tracker = tracker;
    }

    @Override
    protected List<byte[]> read(Collection<String> keys, String hashKey) {
        if (keys.isEmpty()) {
            return null;
        }
        List<Request> requests = new ArrayList<>(keys.size());
        for (String key : keys) {
            requests.add(Request.cmd(Command.HGET).arg(key).arg(hashKey));
        }
        List<Response> responses = redis.batch(requests).await().atMost(timeout);
        List<byte[]> list = new ArrayList<>(responses.size());
        for (Response response : responses) {
            list.add(response == null ? null : response.toBytes());
        }
        return list;
    }

    @Override
    protected void write(Map<String, byte[]> map, String hashKey) {
        if (map.isEmpty() || tracker instanceof QuarkusRedisCacheTracker managed && !managed.isReady()) {
            return;
        }
        List<Request> requests = new ArrayList<>(map.size() * 2);
        for (Map.Entry<String, byte[]> e : map.entrySet()) {
            requests.add(Request.cmd(Command.HSET).arg(e.getKey()).arg(hashKey).arg(e.getValue()));
        }
        for (String key : map.keySet()) {
            requests.add(Request.cmd(Command.PEXPIRE).arg(key).arg(nextExpireMillis()));
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

        public RedisHashBinder<K, V> build() {
            if (redisDataSource == null) {
                throw new IllegalStateException("RedisDataSource has not been specified");
            }
            return new RedisHashBinder<>(
                    type, prop, tracker, jsonCodec, keyPrefixProvider, duration, randomPercent, redisDataSource, timeout);
        }

        /** Deadline for a pipelined Redis operation, independent of entry TTL. Defaults to 10 seconds. */
        public Builder<K, V> timeout(Duration timeout) {
            this.timeout = RedisCacheCreator.requireTimeout(timeout);
            return this;
        }
    }
}
