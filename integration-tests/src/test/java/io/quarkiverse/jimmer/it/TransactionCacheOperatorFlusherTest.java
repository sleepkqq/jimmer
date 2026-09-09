package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.cache.TransactionCacheOperator;
import org.babyfish.jimmer.sql.dialect.PostgresDialect;
import org.babyfish.jimmer.sql.event.TriggerType;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.BookStoreDraft;
import io.quarkiverse.jimmer.it.entity.BookStoreTable;
import io.quarkiverse.jimmer.runtime.cache.impl.TransactionCacheOperatorFlusher;
import io.quarkiverse.jimmer.runtime.cfg.support.QuarkusConnectionManager;
import io.quarkiverse.jimmer.runtime.cfg.support.QuarkusTransientResolverProvider;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(TransactionCacheOperatorFlusherTest.Profile.class)
class TransactionCacheOperatorFlusherTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.jimmer.transaction-cache-operator-fixed-delay", "off",
                    "quarkus.datasource.jdbc.min-size", "0",
                    "quarkus.datasource.jdbc.max-size", "2",
                    "quarkus.datasource.jdbc.acquisition-timeout", "3s");
        }
    }

    @Inject AgroalDataSource dataSource;
    @Inject TransactionManager transactionManager;
    @Inject TransactionSynchronizationRegistry registry;

    private final AtomicInteger flushes = new AtomicInteger();
    private final Map<Long, BookStore> cached = new ConcurrentHashMap<>();
    private final List<Long> stores = new CopyOnWriteArrayList<>();
    private final List<LogRecord> completionErrors = new CopyOnWriteArrayList<>();
    private volatile boolean cacheUnavailable;
    private JSqlClient client;
    private TransactionCacheOperatorFlusher flusher;
    private final Handler logHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= java.util.logging.Level.SEVERE.intValue()
                    && (record.getLoggerName().contains("TransactionCacheOperator")
                    || record.getLoggerName().contains("DeferredEventNotification")
                    || record.getLoggerName().startsWith("com.arjuna"))) {
                completionErrors.add(record);
            }
        }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Logger.getLogger("").addHandler(logHandler);
        TransactionCacheOperator operator = new TransactionCacheOperator() {
            @Override
            public void flush() {
                flushes.incrementAndGet();
                assertDoesNotThrow(() -> assertNull(transactionManager.getTransaction()));
                super.flush();
            }
        };
        Cache<Long, BookStore> cache = new Cache<>() {
            @Override public ImmutableType type() { return ImmutableType.get(BookStore.class); }
            @Override public ImmutableProp prop() { return null; }
            @Override
            public Map<Long, BookStore> getAll(Collection<Long> keys, CacheEnvironment<Long, BookStore> env) {
                List<Long> missing = keys.stream().filter(key -> !cached.containsKey(key)).toList();
                cached.putAll(env.getLoader().loadAll(missing));
                Map<Long, BookStore> result = new HashMap<>();
                keys.forEach(key -> { if (cached.containsKey(key)) result.put(key, cached.get(key)); });
                return result;
            }
            @Override
            public void deleteAll(Collection<Long> keys, Object reason) {
                if (cacheUnavailable) throw new IllegalStateException("cache unavailable");
                keys.forEach(cached::remove);
            }
        };
        client = JSqlClient.newBuilder()
                .setConnectionManager(new QuarkusConnectionManager(dataSource))
                .setTransientResolverProvider(new QuarkusTransientResolverProvider(Arc.container()))
                .setDialect(new PostgresDialect())
                .setTriggerType(TriggerType.TRANSACTION_ONLY)
                .setMutationTransactionRequired(false)
                .setCaches(config -> config.setObjectCache(BookStore.class, cache))
                .setCacheOperator(operator)
                .build();
        // This counted operator belongs to the standalone test client, not the application's SqlClient container.
        InjectableBean<TransactionCacheOperator> bean = (InjectableBean<TransactionCacheOperator>) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[] { InjectableBean.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getQualifiers")) {
                        return java.util.Set.of(new DataSource.DataSourceLiteral("flusher-test"));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        flusher = new TransactionCacheOperatorFlusher(List.of(new InstanceHandle<>() {
            @Override public TransactionCacheOperator get() { return operator; }
            @Override public InjectableBean<TransactionCacheOperator> getBean() { return bean; }
        }), transactionManager, registry);
        client.getTriggers().addEntityListener(flusher::onDatabaseEvent);
        client.getTriggers().addAssociationListener(flusher::onDatabaseEvent);
    }

    @AfterEach
    void tearDown() {
        cacheUnavailable = false;
        try {
            flusher.retry();
            QuarkusTransaction.requiringNew().run(() -> stores.forEach(id -> client.deleteById(BookStore.class, id)));
            assertTrue(completionErrors.isEmpty(), () -> completionErrors.toString());
        } finally {
            Logger.getLogger("").removeHandler(logHandler);
        }
    }

    @Test
    void manyEventsFlushOnceAfterCommitAndInvalidateCachedState() {
        long id = store();
        flushes.set(0);
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < 100; i++) update(id, "updated-" + i);
            assertEquals("initial", cached.get(id).website());
            assertEquals(0, flushes.get());
            assertTrue(pending() > 0);
        });
        assertEquals(1, flushes.get());
        assertEquals(0, pending());
        assertFalse(cached.containsKey(id));
        assertEquals("updated-99", client.findById(BookStore.class, id).website());
    }

    @Test
    void rollbackDoesNotFlushAndDoesNotPoisonFollowingCommit() {
        long id = store();
        flushes.set(0);
        transaction(true, () -> update(id, "rolled-back"));
        assertEquals(0, flushes.get());
        assertEquals(0, pending());
        assertEquals("initial", client.findById(BookStore.class, id).website());
        transaction(false, () -> update(id, "committed"));
        assertEquals(1, flushes.get());
        assertEquals("committed", client.findById(BookStore.class, id).website());
    }

    @Test
    void nestedTransactionsHaveIndependentCompletionState() {
        for (boolean outerRollback : List.of(false, true)) {
            for (boolean innerRollback : List.of(false, true)) {
                long outer = store();
                long inner = store();
                flushes.set(0);
                transaction(outerRollback, () -> {
                    update(outer, "outer");
                    transaction(innerRollback, () -> update(inner, "inner"));
                });
                assertEquals((outerRollback ? 0 : 1) + (innerRollback ? 0 : 1), flushes.get());
                assertEquals(outerRollback ? "initial" : "outer", client.findById(BookStore.class, outer).website());
                assertEquals(innerRollback ? "initial" : "inner", client.findById(BookStore.class, inner).website());
                assertEquals(0, pending());
            }
        }
    }

    @Test
    void autocommitStillFlushesAndRetryDrainsLaterCacheEvents() {
        long id = store();
        flushes.set(0);
        update(id, "autocommit");
        assertEquals(1, flushes.get());
        // Global Jimmer listeners run before per-type cache listeners without a JTA completion boundary.
        flusher.retry();
        assertEquals("autocommit", client.findById(BookStore.class, id).website());
        assertEquals(0, pending());
    }

    @Test
    void failedInvalidationKeepsCommittedDataAndPendingWorkForRetry() {
        long id = store();
        cacheUnavailable = true;
        transaction(false, () -> update(id, "committed"));
        assertTrue(pending() > 0);
        assertEquals("initial", cached.get(id).website());
        assertEquals("committed", client.caches(config -> config.disableAll())
                .findById(BookStore.class, id).website());
        cacheUnavailable = false;
        flusher.retry();
        assertEquals(0, pending());
        assertEquals("committed", client.findById(BookStore.class, id).website());
    }

    @Test
    void simultaneousCommitsReleaseBusinessConnectionsBeforeFlushing() throws Exception {
        long first = store();
        long second = store();
        flushes.set(0);
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 4; i++) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                var futures = List.of(first, second).stream()
                        .map(id -> executor.submit(() -> transaction(false, () -> {
                            update(id, UUID.randomUUID().toString());
                            assertDoesNotThrow(() -> barrier.await(5, TimeUnit.SECONDS));
                        }))).toList();
                for (var future : futures) future.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(8, flushes.get());
        assertEquals(0, pending());
        assertFalse(cached.containsKey(first));
        assertFalse(cached.containsKey(second));
    }

    private long store() {
        BookStore store = QuarkusTransaction.requiringNew().call(() -> client.save(BookStoreDraft.$.produce(draft -> draft
                .setName(UUID.randomUUID().toString()).setWebsite("initial")
                .setCreatedTime(LocalDateTime.now()).setModifiedTime(LocalDateTime.now())))
                .getModifiedEntity());
        stores.add(store.id());
        client.findById(BookStore.class, store.id());
        return store.id();
    }

    private void update(long id, String website) {
        client.createUpdate(BookStoreTable.$).set(BookStoreTable.$.website(), website)
                .where(BookStoreTable.$.id().eq(id)).execute();
    }

    private void transaction(boolean rollback, Runnable action) {
        Runnable run = () -> QuarkusTransaction.requiringNew().run(() -> {
            action.run();
            if (rollback) throw new IllegalStateException("rollback");
        });
        if (rollback) assertEquals("rollback", assertThrows(IllegalStateException.class, run::run).getMessage());
        else run.run();
    }

    private long pending() {
        return ((JSqlClientImplementor) client).getConnectionManager().execute(connection -> {
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("select count(*) from JIMMER_TRANS_CACHE_OPERATOR")) {
                result.next();
                return result.getLong(1);
            } catch (java.sql.SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}
