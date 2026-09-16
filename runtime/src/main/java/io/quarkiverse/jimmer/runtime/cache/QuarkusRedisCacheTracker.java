package io.quarkiverse.jimmer.runtime.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.babyfish.jimmer.sql.cache.spi.AbstractCacheTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.vertx.core.json.Json;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;

/**
 * {@link CacheTracker} on plain Redis PUB/SUB via the Quarkus Redis (Vert.x) client — cross-instance
 * invalidation of the local tier of {@code FULL} caches without a Redisson dependency. The message
 * is a plain-JSON DTO serialized by the client's Jackson codec, so it also works in a GraalVM
 * native image (Redisson's default Kryo codec needs Objenesis / {@code sun.reflect.ReflectionFactory},
 * which is absent there).
 */
public class QuarkusRedisCacheTracker extends AbstractCacheTracker implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuarkusRedisCacheTracker.class);

    private static final String CHANNEL = "_quarkus_jimmer_:invalidate";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID trackerId = UUID.randomUUID();

    private final PubSubCommands<InvalidationMessage> pubSub;
    private final Redis redis;
    private final Duration timeout;
    private final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    private final ScheduledExecutorService reconnect = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "jimmer-cache-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    // Only recovery takes the write lock; ordinary cached reads remain concurrent.
    private final ReentrantReadWriteLock loads = new ReentrantReadWriteLock();
    private RedisConnection subscriber;
    private volatile boolean ready;
    private boolean closed;
    private long generation;
    private final Set<CacheLoadScope> activeLoads = new HashSet<>();

    public QuarkusRedisCacheTracker(RedisDataSource redisDataSource) {
        this(redisDataSource, RedisCacheCreator.DEFAULT_TIMEOUT);
    }

    public QuarkusRedisCacheTracker(RedisDataSource redisDataSource, Duration timeout) {
        this.timeout = RedisCacheCreator.requireTimeout(timeout);
        pubSub = redisDataSource.pubsub(InvalidationMessage.class);
        redis = redisDataSource.getReactive().getRedis().getDelegate();
        try {
            subscribe(0);
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    /** Local subscription readiness only; does not attest that an external CDC source is caught up. */
    public boolean isReady() {
        return ready;
    }

    <T> T read(CacheLoadScope scope, Supplier<T> cached, Supplier<T> database) {
        if (!ready) {
            return database.get();
        }
        loads.readLock().lock();
        synchronized (activeLoads) { activeLoads.add(scope); }
        try {
            if (ready) {
                return cached.get();
            }
        } finally {
            try {
                synchronized (activeLoads) { activeLoads.remove(scope); }
                // A notification can precede the late L1 fill. Clear these keys once more after that load finishes.
                if (scope.invalidated) firer().invalidate(scope.interest);
            } finally {
                loads.readLock().unlock();
            }
        }
        return database.get();
    }

    private void subscribe(long attempt) {
        long deadline = System.nanoTime() + timeout.toNanos();
        var connecting = redis.connect();
        RedisConnection next;
        try {
            next = await(connecting.toCompletionStage().toCompletableFuture(), deadline);
        } catch (RuntimeException ex) {
            connecting.onSuccess(RedisConnection::close); // Also close a connection that arrives after timeout.
            throw ex;
        }
        CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        boolean accepted = false;
        try {
            next.handler(response -> {
                try {
                    if (response == null || response.size() < 3 || !CHANNEL.equals(response.get(1).toString())) return;
                    String command = response.get(0).toString();
                    if ("subscribe".equals(command)) {
                        acknowledged.complete(null);
                    } else if ("message".equals(command)) {
                        InvalidationMessage msg = Json.decodeValue(response.get(2).toString(), InvalidationMessage.class);
                        CacheTracker.InvalidateEvent event = trackerId.equals(msg.trackerId) ? null : msg.toEvent(classLoader);
                        if (event != null) {
                            invalidateLoads(event);
                            firer().invalidate(event);
                        }
                    }
                } catch (RuntimeException ex) {
                    acknowledged.completeExceptionally(new IllegalStateException("Invalid cache subscription event"));
                    disconnected(attempt);
                }
            });
            next.endHandler(ignored -> {
                acknowledged.completeExceptionally(new IllegalStateException("Cache subscription closed"));
                disconnected(attempt);
            });
            next.exceptionHandler(failure -> {
                acknowledged.completeExceptionally(failure);
                disconnected(attempt);
            });
            next.send(Request.cmd(Command.SUBSCRIBE).arg(CHANNEL)).onFailure(acknowledged::completeExceptionally);
            await(acknowledged, deadline);
            loads.writeLock().lockInterruptibly();
            try {
                synchronized (this) {
                    if (!closed && generation == attempt) {
                        // All old cache loads finished; none can refill L1 after this reset.
                        firer().reconnect();
                        subscriber = next;
                        ready = true;
                        accepted = true;
                    }
                }
            } finally {
                loads.writeLock().unlock();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cache subscription recovery interrupted", ex);
        } finally {
            if (!accepted) unsubscribe(next);
        }
    }

    private synchronized void disconnected(long attempt) {
        if (closed || generation != attempt) return;
        ready = false;
        long nextAttempt = ++generation;
        reconnect.schedule(() -> {
            RedisConnection previous;
            synchronized (this) {
                if (closed || generation != nextAttempt) return;
                previous = subscriber;
                subscriber = null;
            }
            unsubscribe(previous);
            try {
                subscribe(nextAttempt);
            } catch (RuntimeException ex) {
                // Exception messages can contain payloads; log no message/body.
                LOGGER.warn("Cache subscription reconnect failed ({})", ex.getClass().getSimpleName());
                disconnected(nextAttempt);
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void unsubscribe(RedisConnection value) {
        if (value == null) return;
        try {
            // Close the owned socket, not an UNSUBSCRIBE that can itself be queued behind a stalled server.
            await(value.close().toCompletionStage().toCompletableFuture(), System.nanoTime() + timeout.toNanos());
        } catch (RuntimeException ex) {
            LOGGER.debug("Cache subscription cleanup failed ({})", ex.getClass().getSimpleName());
        }
    }

    private static <T> T await(CompletableFuture<T> future, long deadline) {
        try {
            return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new io.smallrye.mutiny.TimeoutException();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cache subscription interrupted", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Cache subscription failed", ex.getCause());
        }
    }

    @Override
    public void close() {
        RedisConnection previous;
        synchronized (this) {
            if (closed) return;
            closed = true;
            ready = false;
            ++generation;
            previous = subscriber;
            subscriber = null;
        }
        reconnect.shutdownNow();
        unsubscribe(previous);
    }

    @Override
    protected void publishInvalidationEvent(CacheTracker.InvalidateEvent event) {
        invalidateLoads(event);
        pubSub.publish(CHANNEL, new InvalidationMessage(trackerId, event));
    }

    private void invalidateLoads(CacheTracker.InvalidateEvent event) {
        // Only in-flight calls are retained. No unbounded per-entity version map or lock around I/O.
        synchronized (activeLoads) {
            activeLoads.forEach(scope -> scope.invalidate(event));
        }
    }

    public static final class InvalidationMessage {

        public UUID trackerId;

        public String typeName;

        public String propName;

        public Collection<?> ids;

        public InvalidationMessage() {
        }

        InvalidationMessage(UUID trackerId, CacheTracker.InvalidateEvent event) {
            this.trackerId = trackerId;
            this.typeName = event.getType().toString();
            this.propName = event.getProp() != null ? event.getProp().getName() : null;
            this.ids = event.getIds();
        }

        CacheTracker.InvalidateEvent toEvent() {
            return toEvent(Thread.currentThread().getContextClassLoader());
        }

        CacheTracker.InvalidateEvent toEvent(ClassLoader classLoader) {
            ImmutableType type = resolveType(classLoader);
            if (type == null) {
                return null;
            }
            // JSON carries no id type info — rebuild the collection as the entity's id type
            // (UUIDs and longs otherwise arrive as strings/ints and never match cache keys).
            CollectionType idsType = MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, type.getIdProp().getReturnClass());
            Collection<?> typedIds = MAPPER.convertValue(ids, idsType);
            if (propName != null) {
                ImmutableProp prop = type.getProp(propName);
                return new CacheTracker.InvalidateEvent(prop, typedIds);
            }
            return new CacheTracker.InvalidateEvent(type, typedIds);
        }

        /**
         * Several services may share one Redis and therefore this pub/sub channel — an invalidation
         * for an entity class this service does not have is another service's business, not an error.
         */
        private ImmutableType resolveType(ClassLoader classLoader) {
            Class<?> javaType;
            try {
                javaType = Class.forName(typeName, true, classLoader);
            } catch (ClassNotFoundException ex) {
                LOGGER.debug("Ignoring cache invalidation for unknown entity type {}", typeName);
                return null;
            }
            return ImmutableType.get(javaType);
        }
    }
}
