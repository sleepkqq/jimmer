package io.quarkiverse.jimmer.runtime.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;

/** Atomic read-token / conditional fill. Expired or evicted tokens reject old loaders, never become ABA matches. */
final class RedisLoadFence {
    // A long load may lose its opportunity to fill the cache, but can still return its DB result.
    private static final long LEASE_MILLIS = 60_000;
    private static final String READ = "local t=redis.call('GET',KEYS[2]); if not t then " +
            "t=ARGV[1]; redis.call('SET',KEYS[2],t,'PX',ARGV[2]); end; " +
            "local v; if ARGV[3]=='v' then v=redis.call('GET',KEYS[1]); " +
            "else v=redis.call('HGET',KEYS[1],ARGV[4]); end; return {t,v or false}";
    private static final String WRITE = "if redis.call('GET',KEYS[2])~=ARGV[1] then return 0 end; " +
            "if ARGV[2]=='v' then redis.call('SET',KEYS[1],ARGV[3],'PX',ARGV[4]); " +
            "else redis.call('HSET',KEYS[1],ARGV[5],ARGV[3]); redis.call('PEXPIRE',KEYS[1],ARGV[4]); end; return 1";
    private static final String DELETE = "redis.call('SET',KEYS[2],ARGV[1],'PX',ARGV[2]); return redis.call('DEL',KEYS[1])";

    private RedisLoadFence() {}

    private static Request script(String body, String key) {
        return Request.cmd(Command.EVAL).arg(body).arg(2).arg(key).arg("_quarkus_jimmer_:fence:" + key);
    }

    static List<byte[]> read(Redis redis, Duration timeout, Collection<String> keys, String hashKey) {
        CacheLoadScope scope = CacheLoadScope.current();
        List<Request> requests = new ArrayList<>(keys.size());
        String token = UUID.randomUUID().toString();
        for (String key : keys) requests.add(script(READ, key).arg(token).arg(LEASE_MILLIS)
                .arg(hashKey == null ? "v" : "h").arg(hashKey == null ? "" : hashKey));
        List<Response> responses = redis.batch(requests).await().atMost(timeout);
        List<byte[]> result = new ArrayList<>(responses.size());
        int index = 0;
        for (String key : keys) {
            Response row = responses.get(index++);
            scope.tokens.put(key, row.get(0).toString());
            result.add(row.get(1) == null ? null : row.get(1).toBytes());
        }
        return result;
    }

    static void write(Redis redis, Duration timeout, Map<String, byte[]> values, String hashKey, LongSupplier expiry) {
        CacheLoadScope scope = CacheLoadScope.current();
        List<Request> requests = new ArrayList<>(values.size());
        for (var entry : values.entrySet()) {
            String token = scope.tokens.get(entry.getKey());
            if (token == null) { scope.invalidated = true; continue; }
            requests.add(script(WRITE, entry.getKey()).arg(token).arg(hashKey == null ? "v" : "h")
                    .arg(entry.getValue()).arg(expiry.getAsLong()).arg(hashKey == null ? "" : hashKey));
        }
        if (!requests.isEmpty()) {
            for (Response response : redis.batch(requests).await().atMost(timeout)) {
                if (response.toInteger() == 0) scope.invalidated = true;
            }
        }
    }

    static void delete(Redis redis, Duration timeout, Collection<String> keys) {
        String token = UUID.randomUUID().toString();
        List<Request> requests = new ArrayList<>(keys.size());
        for (String key : keys) requests.add(script(DELETE, key).arg(token).arg(LEASE_MILLIS));
        redis.batch(requests).await().atMost(timeout);
    }
}
