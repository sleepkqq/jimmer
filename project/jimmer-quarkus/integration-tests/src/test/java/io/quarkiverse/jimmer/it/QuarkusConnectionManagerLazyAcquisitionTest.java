package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.dialect.PostgresDialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager.ConnectionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.BookStoreTable;
import io.quarkiverse.jimmer.runtime.cfg.support.QuarkusConnectionManager;
import io.quarkiverse.jimmer.runtime.cfg.support.QuarkusTransientResolverProvider;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * A managed Jimmer scope must not touch the Agroal pool until a JDBC call is actually
 * issued. The pool is observed through a counting {@link DataSource} that only this
 * test's managers receive, so application activity cannot disturb the counters.
 */
@QuarkusTest
class QuarkusConnectionManagerLazyAcquisitionTest {

    @Inject
    DataSource dataSource;

    @Inject
    AgroalDataSource agroalDataSource;

    @Inject
    JSqlClient sqlClient;

    private CountingDataSource counting;

    private QuarkusConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        counting = new CountingDataSource(dataSource);
        connectionManager = new QuarkusConnectionManager(counting);
    }

    @Test
    void managedScopeWithoutJdbcAcquiresNothing() {
        assertEquals("x", connectionManager.execute(con -> "x"));
        assertEquals(0, counting.acquisitions());
        assertEquals(0, counting.releases());
    }

    @Test
    void managedScopeWithStatementAcquiresOnceAndReleases() {
        long activeBefore = agroalDataSource.getMetrics().activeCount();
        assertEquals("1", connectionManager.execute(con -> scalar(con, "select 1")));
        assertEquals(1, counting.acquisitions());
        assertEquals(1, counting.releases());
        assertEquals(activeBefore, agroalDataSource.getMetrics().activeCount());
    }

    @Test
    void managedScopeClosesTheProxyAfterFailingCallback() {
        assertThrows(IllegalStateException.class, () -> connectionManager.execute(con -> {
            scalar(con, "select 1");
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, counting.acquisitions());
        assertEquals(1, counting.releases());
    }

    @Test
    void transactionWithoutJdbcAcquiresNothing() {
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals("a", connectionManager.execute(con -> "a"));
            assertEquals("b", connectionManager.execute(con -> "b"));
        });
        assertEquals(0, counting.acquisitions());
        assertEquals(0, counting.releases());
    }

    @Test
    void transactionWithStatementsAcquiresOnceAndClosesOnCommit() {
        long activeBefore = agroalDataSource.getMetrics().activeCount();
        AtomicReference<Connection> first = new AtomicReference<>();
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals("1", connectionManager.execute(con -> {
                first.set(con);
                return scalar(con, "select 1");
            }));
            assertEquals("1", connectionManager.execute(con -> {
                assertSame(first.get(), con, "one transaction must see one managed connection");
                return scalar(con, "select 1");
            }));
            assertEquals(0, counting.releases(), "the transaction connection must stay open until completion");
        });
        assertEquals(1, counting.acquisitions());
        assertEquals(1, counting.releases());
        assertEquals(activeBefore, agroalDataSource.getMetrics().activeCount());
    }

    @Test
    void rolledBackTransactionClosesAcquiredTarget() {
        long activeBefore = agroalDataSource.getMetrics().activeCount();
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            assertEquals("1", connectionManager.execute(con -> scalar(con, "select 1")));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(1, counting.acquisitions());
        // Rollback runs only afterCompletion, so the pool probe is what proves the release.
        assertEquals(activeBefore, agroalDataSource.getMetrics().activeCount());
    }

    @Test
    void rolledBackTransactionWithoutJdbcAcquiresNothing() {
        assertThrows(IllegalStateException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    connectionManager.execute(con -> "x");
                    throw new IllegalStateException("rollback");
                }));
        assertEquals(0, counting.acquisitions());
    }

    @Test
    void streamingScopeWithoutJdbcAcquiresNothing() {
        ConnectionScope scope = connectionManager.open(null);
        try {
            assertNotNull(scope.connection());
            assertEquals(0, counting.acquisitions());
        } finally {
            scope.close();
        }
        assertEquals(0, counting.acquisitions());
        assertEquals(0, counting.releases());
    }

    @Test
    void streamingScopeWithStatementAcquiresOnceAndClosesOnScopeClose() {
        ConnectionScope scope = connectionManager.open(null);
        try {
            assertEquals("1", scalar(scope.connection(), "select 1"));
            assertEquals(1, counting.acquisitions());
            assertEquals(0, counting.releases(), "the stream scope must keep the connection until it closes");
        } finally {
            scope.close();
        }
        assertEquals(1, counting.releases());
    }

    @Test
    void managedNonTxProxyIsKnownInactiveAndAcquiresOnDemand() {
        connectionManager.execute(con -> {
            assertTrue(connectionManager.isTransactionKnownInactive(con));
            return null;
        });
        assertEquals(1, counting.acquisitions(), "the guard inspects auto-commit and therefore acquires");
    }

    @Test
    void transactionProxyIsNotKnownInactiveWithoutAcquiring() {
        QuarkusTransaction.requiringNew().run(() -> connectionManager.execute(con -> {
            assertFalse(connectionManager.isTransactionKnownInactive(con));
            return null;
        }));
        assertEquals(0, counting.acquisitions());
    }

    @Test
    void fullyCachedEntityReadAcquiresNoConnection() {
        CountingDataSource cacheCounting = new CountingDataSource(dataSource);
        MapObjectCache cache = new MapObjectCache();
        JSqlClient client = JSqlClient.newBuilder()
                .setConnectionManager(new QuarkusConnectionManager(cacheCounting))
                .setTransientResolverProvider(new QuarkusTransientResolverProvider(Arc.container()))
                .setDialect(new PostgresDialect())
                .setCaches(config -> config.setObjectCache(BookStore.class, cache))
                .build();
        long id = sqlClient.createQuery(BookStoreTable.$)
                .select(BookStoreTable.$.id())
                .limit(1)
                .execute()
                .get(0);
        try {
            BookStore warm = client.findById(BookStore.class, id);
            assertNotNull(warm);
            assertEquals(1, cacheCounting.acquisitions(), "a cold read issues SQL");

            cacheCounting.reset();
            BookStore cached = client.findById(BookStore.class, id);
            assertNotNull(cached);
            assertEquals(warm.name(), cached.name());
            assertEquals(0, cacheCounting.acquisitions(), "a fully cache-served read must not take a pool connection");

            cache.deleteAll(List.of(id), null);
            cacheCounting.reset();
            assertNotNull(client.findById(BookStore.class, id));
            assertEquals(1, cacheCounting.acquisitions(), "an evicted entry is loaded again");
        } finally {
            cache.deleteAll(List.of(id), null);
        }
    }

    private static String scalar(Connection con, String sql) {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Counts acquisitions and releases of the wrapped pool. Each handed-out connection
     * is itself deferred behind a proxy, so {@code close()} is observed without relying
     * on the pool implementation's {@code isClosed()} semantics.
     */
    private static final class CountingDataSource implements DataSource {

        private final DataSource delegate;

        private final AtomicInteger acquisitions = new AtomicInteger();

        private final AtomicInteger releases = new AtomicInteger();

        CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        int acquisitions() {
            return acquisitions.get();
        }

        int releases() {
            return releases.get();
        }

        void reset() {
            acquisitions.set(0);
            releases.set(0);
        }

        @Override
        public Connection getConnection() throws SQLException {
            acquisitions.incrementAndGet();
            return trackRelease(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            acquisitions.incrementAndGet();
            return trackRelease(delegate.getConnection(username, password));
        }

        private Connection trackRelease(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    CountingDataSource.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            releases.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    /**
     * Minimal in-memory object cache: the loader runs on a miss, so the pool counters
     * show exactly when the extension had to reach the database.
     */
    private static final class MapObjectCache implements Cache<Long, BookStore> {

        private final Map<Long, BookStore> values = new ConcurrentHashMap<>();

        @Override
        public ImmutableType type() {
            return ImmutableType.get(BookStore.class);
        }

        @Override
        public ImmutableProp prop() {
            return null;
        }

        @Override
        public Map<Long, BookStore> getAll(Collection<Long> keys, CacheEnvironment<Long, BookStore> env) {
            Map<Long, BookStore> result = new HashMap<>();
            List<Long> missing = new ArrayList<>();
            for (Long key : keys) {
                BookStore value = values.get(key);
                if (value != null) {
                    result.put(key, value);
                } else {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                for (Map.Entry<Long, BookStore> entry : env.getLoader().loadAll(missing).entrySet()) {
                    if (entry.getValue() != null) {
                        values.put(entry.getKey(), entry.getValue());
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            return result;
        }

        @Override
        public void deleteAll(Collection<Long> keys, Object reason) {
            for (Long key : keys) {
                values.remove(key);
            }
        }
    }
}
