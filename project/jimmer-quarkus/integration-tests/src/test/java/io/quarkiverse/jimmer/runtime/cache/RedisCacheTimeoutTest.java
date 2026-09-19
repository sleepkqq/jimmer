package io.quarkiverse.jimmer.runtime.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import jakarta.inject.Inject;

import org.babyfish.jimmer.jackson.codec.JsonCodec;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;

import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.it.entity.BookDraft;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.runtime.cfg.JimmerCacheConfig;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.TimeoutException;

@QuarkusTest
@TestProfile(RedisCacheTimeoutTest.Profile.class)
class RedisCacheTimeoutTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.arc.exclude-types", "io.quarkiverse.jimmer.it.config.CacheConfig",
                    "quarkus.redis.timeout", "1s",
                    "quarkus.jimmer.trigger-type", "BINLOG_ONLY",
                    "quarkus.jimmer.DB2.trigger-type", "BINLOG_ONLY",
                    "quarkus.jimmer.cache.entities[0].type", "BookStore",
                    "quarkus.jimmer.cache.entities[0].mode", "FULL");
        }
    }

    @Inject RedisDataSource redis;
    @Inject CacheFactory factory;
    @Inject CacheTracker tracker;
    @Inject JimmerCacheConfig config;
    GenericContainer<?> redisContainer;

    @ParameterizedTest
    @ValueSource(strings = {"value-read", "value-write", "value-delete", "hash-read", "hash-write", "hash-delete"})
    void stalledRedisTimesOutAndTheBatchCanBeRetried(String operation) throws Throwable {
        RedisValueBinder<Long, Book> value = RedisValueBinder.<Long, Book>forObject(
                ImmutableType.get(Book.class), JsonCodec.jsonCodec()).redis(redis).timeout(Duration.ofSeconds(1)).build();
        RedisHashBinder<Long, Long> hash = RedisHashBinder.<Long, Long>forProp(
                ImmutableType.get(Book.class).getProp("store"), JsonCodec.jsonCodec())
                .redis(redis).timeout(Duration.ofSeconds(1)).build();
        List<Long> ids = List.of(-1001L, -1002L);
        Map<Long, Book> entries = new LinkedHashMap<>();
        ids.forEach(id -> entries.put(id, BookDraft.$.produce(draft -> {
            draft.setId(id);
            draft.setName("Unicode \u0000 \uD83D\uDE80");
        })));
        Map<Long, Long> references = Map.of(-1001L, 1001L, -1002L, 1002L);
        SortedMap<String, Object> view = new TreeMap<>(Map.of("tenant", "one"));
        boolean isHash = operation.startsWith("hash");
        List<String> keys = ids.stream().map(id -> (isHash ? hash.keyPrefix() : value.keyPrefix()) + id).toList();
        if (isHash) {
            hash.setAll(references, view);
        } else {
            value.setAll(entries);
        }
        Executable call = switch (operation) {
            case "value-read" -> () -> value.getAll(ids);
            case "value-write" -> () -> value.setAll(entries);
            case "value-delete" -> () -> value.deleteAll(ids, null);
            case "hash-read" -> () -> hash.getAll(ids, view);
            case "hash-write" -> () -> hash.setAll(references, view);
            case "hash-delete" -> () -> hash.deleteAll(ids, null);
            default -> throw new AssertionError(operation);
        };
        try {
            assertStalledTimeout(call);
            call.execute();
            if (operation.endsWith("delete")) {
                keys.forEach(key -> assertEquals(0, redis.execute("EXISTS", key).toInteger()));
            } else {
                if (isHash) {
                    assertEquals(references, hash.getAll(ids, view));
                } else {
                    assertEquals(entries, value.getAll(ids));
                }
                keys.forEach(key -> assertTrue(redis.execute("PTTL", key).toLong() > 0));
            }
        } finally {
            redis.key().del(keys.toArray(String[]::new));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cdiFactoryUsesRedisTimeoutAndPropagatesInvalidationFailure() {
        Cache<Long, BookStore> cache = (Cache<Long, BookStore>) factory.createObjectCache(ImmutableType.get(BookStore.class));
        assertNotNull(cache);
        assertStalledTimeout(() -> cache.deleteAll(List.of(-101L), null));
        assertDoesNotThrow(() -> cache.deleteAll(List.of(-101L), null));
    }

    @Test
    void startupPingAndPubSubPublicationHaveTheDatasourceDeadline() {
        assertStalledTimeout(() -> new JimmerRedisCacheFactory(redis, config, null, null));
        assertStalledTimeout(() -> new QuarkusRedisCacheTracker(redis, Duration.ofSeconds(1)));
        CacheTracker.InvalidateEvent event = new CacheTracker.InvalidateEvent(ImmutableType.get(BookStore.class), List.of(-102L));
        assertStalledTimeout(() -> tracker.publisher().invalidate(event));
        assertDoesNotThrow(() -> tracker.publisher().invalidate(event));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void creatorPropagatesTimeoutToBothAssociationBinders(boolean multiView) {
        Cache<Long, Long> cache = new RedisCacheCreator(redis)
                .withTimeout(Duration.ofSeconds(1)).withoutLocalCache()
                .createForProp(ImmutableType.get(Book.class).getProp("store"), multiView);
        assertStalledTimeout(() -> cache.deleteAll(List.of(-103L), null));
        assertDoesNotThrow(() -> cache.deleteAll(List.of(-103L), null));
    }

    @Test
    void timeoutMustBePositiveAndIsNotTheEntryTtl() {
        RedisCacheCreator creator = new RedisCacheCreator(redis);
        assertThrows(IllegalArgumentException.class, () -> creator.withTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> creator.withTimeout(Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> creator.withTimeout(null));
    }

    private void assertStalledTimeout(Executable action) {
        // Redis auto-resumes even if the regression reintroduces an indefinite client await.
        // Control is independent of the application pool and its queued, timed-out commands.
        assertEquals("OK", assertDoesNotThrow(() -> redisContainer.execInContainer(
                "redis-cli", "CLIENT", "PAUSE", "3000", "ALL")).getStdout().trim());
        try {
            assertThrows(TimeoutException.class, action);
        } finally {
            assertEquals("OK", assertDoesNotThrow(() -> redisContainer.execInContainer(
                    "redis-cli", "CLIENT", "UNPAUSE")).getStdout().trim());
        }
        assertEquals("PONG", redis.execute("PING").toString());
    }
}
