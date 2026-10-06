package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.it.entity.BookFetcher;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.BookStoreFetcher;
import io.quarkiverse.jimmer.it.entity.BookStoreTable;
import io.quarkiverse.jimmer.it.entity.BookTable;
import io.quarkiverse.jimmer.runtime.cfg.support.QuarkusConnectionManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Covers the {@link QuarkusConnectionManager#isTransactionKnownInactive(Connection)}
 * guard. The manager may only positively recognize a connection that is actually
 * owned by the current thread's managed {@code execute} scope and is in auto-commit
 * mode: an external auto-commit connection, a mismatched connection, a disabled
 * auto-commit, an active/marked-rollback transaction, a null connection or an
 * introspection error is denied. Nested managed scopes and failing callbacks must
 * restore the outer ownership proof. The last case drives the optional object-cache
 * query hint through a real JTA transaction to prove that uncommitted data is not
 * served from (or allowed to pollute) the shared object cache.
 */
@QuarkusTest
@TestProfile(QuarkusConnectionManagerTransactionKnownInactiveTest.Profile.class)
class QuarkusConnectionManagerTransactionKnownInactiveTest {

    /**
     * Exercises a real object cache for {@code BookStore} without touching the
     * application configuration: this is the established in-test fixture (also used
     * by {@code ConfiguredCacheGuardTest} and {@code RedisCacheTimeoutTest}) that
     * excludes the application's blanket {@code CacheConfig} so the runtime
     * {@code JimmerRedisCacheFactory} is the single resolved factory, then enables
     * {@code BookStore} explicitly. Without it the default client resolves no cache
     * factory and the cache-bypass assertion below would be vacuous.
     */
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.arc.exclude-types", "io.quarkiverse.jimmer.it.config.CacheConfig",
                    "quarkus.jimmer.cache.entities[0].type", "BookStore",
                    "quarkus.jimmer.cache.entities[0].mode", "FULL");
        }
    }

    @Inject
    DataSource dataSource;

    @Inject
    TransactionManager transactionManager;

    @Inject
    JSqlClient sqlClient;

    @Inject
    CacheFactory cacheFactory;

    private QuarkusConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        connectionManager = new QuarkusConnectionManager(dataSource);
        assertNotNull(
                cacheFactory.createObjectCache(ImmutableType.get(BookStore.class)),
                "The BookStore object cache must be configured, otherwise this scenario proves nothing"
        );
    }

    @Test
    void managedNonTxAutoCommitConnectionIsKnownInactive() {
        connectionManager.execute(con -> {
            assertTrue(autoCommit(con), "outside any JTA transaction the managed connection must be in auto-commit mode");
            assertTrue(connectionManager.isTransactionKnownInactive(con));
            return null;
        });
    }

    @Test
    void externalAutoCommitConnectionIsNotKnownInactive() throws SQLException {
        try (Connection con = dataSource.getConnection()) {
            assertTrue(con.getAutoCommit(), "outside any JTA transaction the connection must be in auto-commit mode");
            assertFalse(connectionManager.isTransactionKnownInactive(con));
        }
    }

    @Test
    void differentExternalConnectionInsideManagedScopeIsNotKnownInactive() {
        connectionManager.execute(managed -> {
            try (Connection external = dataSource.getConnection()) {
                assertTrue(external.getAutoCommit());
                assertFalse(connectionManager.isTransactionKnownInactive(external));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Test
    void ownedAutoCommitDisabledConnectionIsNotKnownInactive() {
        connectionManager.execute(con -> {
            try {
                con.setAutoCommit(false);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            assertFalse(connectionManager.isTransactionKnownInactive(con));
            return null;
        });
    }

    @Test
    void nullConnectionIsNotKnownInactive() {
        assertFalse(connectionManager.isTransactionKnownInactive(null));
    }

    @Test
    void activeJtaTransactionIsNotKnownInactive() throws Exception {
        transactionManager.begin();
        try {
            connectionManager.execute(con -> {
                assertFalse(connectionManager.isTransactionKnownInactive(con));
                return null;
            });
        } finally {
            transactionManager.rollback();
        }
    }

    @Test
    void markedRollbackTransactionIsNotKnownInactive() throws Exception {
        transactionManager.begin();
        try {
            // Enter the manager's execute while the JTA transaction is still ACTIVE so the
            // callback receives the actually owned, enlisted connection and scope; only then
            // mark rollback. The guard must still deny by transaction status, proving the
            // ownership proof is checked before the status check rather than bypassed.
            connectionManager.execute(con -> {
                try {
                    transactionManager.setRollbackOnly();
                    assertEquals(Status.STATUS_MARKED_ROLLBACK, transactionManager.getStatus());
                } catch (SystemException e) {
                    throw new RuntimeException(e);
                }
                assertFalse(connectionManager.isTransactionKnownInactive(con));
                return null;
            });
        } finally {
            transactionManager.rollback();
        }
    }

    @Test
    void nestedManagedScopeRestoresOuterOwnership() {
        connectionManager.execute(outer -> {
            assertTrue(connectionManager.isTransactionKnownInactive(outer));
            connectionManager.execute(inner -> {
                assertTrue(connectionManager.isTransactionKnownInactive(inner));
                return null;
            });
            assertTrue(connectionManager.isTransactionKnownInactive(outer));
            return null;
        });
    }

    @Test
    void failingNestedScopeRestoresOuterOwnership() {
        connectionManager.execute(outer -> {
            assertThrows(
                    IllegalStateException.class,
                    () -> connectionManager.execute(inner -> {
                        throw new IllegalStateException("boom");
                    })
            );
            assertTrue(connectionManager.isTransactionKnownInactive(outer));
            return null;
        });
    }

    @Test
    void introspectionFailureIsNotKnownInactive() throws SQLException {
        AtomicBoolean fail = new AtomicBoolean();
        Connection real = dataSource.getConnection();
        Connection proxy = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (p, method, args) -> {
                    if (method.getName().equals("getAutoCommit") && fail.get()) {
                        throw new SQLException("introspection failure");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
        QuarkusConnectionManager manager = new QuarkusConnectionManager(new SingleConnectionDataSource(proxy));
        manager.execute(con -> {
            fail.set(true);
            assertFalse(manager.isTransactionKnownInactive(con));
            return null;
        });
    }

    /**
     * The object-cache hint must be ignored inside a JTA transaction: a warmed cache
     * entry must not hide a raw, uncommitted update performed on the transaction's
     * own connection.
     */
    @Test
    void useObjectCacheInsideTransactionDoesNotReturnStaleCachedData() throws Exception {
        long id = anyStoreId();
        String original = storeName(id);

        // Warm the shared object cache with the committed state, outside any transaction.
        List<BookStore> warm = queryStore(id);
        assertEquals(1, warm.size());
        assertEquals(original, warm.get(0).name());

        String pending = original + "-pending";
        transactionManager.begin();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("update BOOK_STORE set NAME = ? where ID = ?")) {
                ps.setString(1, pending);
                ps.setLong(2, id);
                assertEquals(1, ps.executeUpdate());
            }
            // The guard must be false while the transaction is active, so the query
            // reads the uncommitted value instead of the warmed cache value.
            List<BookStore> inTx = queryStore(id);
            assertEquals(1, inTx.size());
            assertEquals(pending, inTx.get(0).name());
        } finally {
            transactionManager.rollback();
        }

        // The rollback restored the committed row and the cache was never poisoned.
        List<BookStore> after = queryStore(id);
        assertEquals(1, after.size());
        assertEquals(original, after.get(0).name());
    }

    /**
     * The same guard applies to the recursive content-fetcher overload: even an
     * explicitly approved whitelist must stay ordinary SQL inside an active
     * transaction, so the uncommitted value is read instead of the warm cache.
     */
    @Test
    void contentFetcherInsideTransactionDoesNotReturnStaleCachedData() throws Exception {
        long id = anyStoreId();
        String original = storeName(id);

        List<BookStore> warm = queryStore(id);
        assertEquals(1, warm.size());
        assertEquals(original, warm.get(0).name());

        String pending = original + "-pending";
        transactionManager.begin();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("update BOOK_STORE set NAME = ? where ID = ?")) {
                ps.setString(1, pending);
                ps.setLong(2, id);
                assertEquals(1, ps.executeUpdate());
            }
            List<BookStore> inTx = sqlClient.createQuery(BookStoreTable.$)
                    .where(BookStoreTable.$.id().eq(id))
                    .select(BookStoreTable.$.fetch(BookStoreFetcher.$.name()))
                    .useObjectCache(BookStoreFetcher.$.name())
                    .execute();
            assertEquals(1, inTx.size());
            assertEquals(pending, inTx.get(0).name());
        } finally {
            transactionManager.rollback();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void contentFetcherColdCacheInsideTransactionNeverPublishesPendingValue() throws Exception {
        long bookId = anyBookId();
        long id = bookStoreId(bookId);
        String original = storeName(id);
        Cache<Object, BookStore> cache =
                (Cache<Object, BookStore>) sqlClient.getCaches().getObjectCache(ImmutableType.get(BookStore.class));
        assertNotNull(cache, "The BookStore object cache must be configured");
        cache.deleteAll(Collections.singletonList(id), null);

        String pending = original + "-pending";
        transactionManager.begin();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("update BOOK_STORE set NAME = ? where ID = ?")) {
                ps.setString(1, pending);
                ps.setLong(2, id);
                assertEquals(1, ps.executeUpdate());
            }
            List<Book> inTx = sqlClient.createQuery(BookTable.$)
                    .where(BookTable.$.id().eq(bookId))
                    .select(BookTable.$.fetch(BookFetcher.$.store(
                            ReferenceFetchType.SELECT,
                            BookStoreFetcher.$.name()
                    )))
                    .useObjectCache(BookFetcher.$.store(BookStoreFetcher.$.name()))
                    .execute();
            assertEquals(1, inTx.size());
            assertEquals(pending, inTx.get(0).store().name());
        } finally {
            transactionManager.rollback();
        }

        // The cold load inside the transaction never published the pending value.
        List<BookStore> after = queryStore(id);
        assertEquals(1, after.size());
        assertEquals(original, after.get(0).name());
    }

    /**
     * The nested child path is no exception: inside a JTA transaction the masked hint
     * must read the pending FK edge and the pending child fact instead of the warm
     * child cache, and a rollback must not publish the uncommitted child.
     */
    @Test
    void contentFetcherNestedChildInsideTransactionReadsPendingTarget() throws Exception {
        long bookId = anyBookId();
        long originalStoreId = bookStoreId(bookId);
        long otherStoreId = anyOtherStoreId(originalStoreId);
        String originalOtherName = storeName(otherStoreId);

        // Warm the currently committed child through the ordinary object-cache path.
        List<BookStore> warm = queryStore(originalStoreId);
        assertEquals(1, warm.size());

        String pendingName = originalOtherName + "-pending";
        transactionManager.begin();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("update BOOK set STORE_ID = ? where ID = ?")) {
                ps.setLong(1, otherStoreId);
                ps.setLong(2, bookId);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = con.prepareStatement("update BOOK_STORE set NAME = ? where ID = ?")) {
                ps.setString(1, pendingName);
                ps.setLong(2, otherStoreId);
                assertEquals(1, ps.executeUpdate());
            }
            List<Book> inTx = sqlClient.createQuery(BookTable.$)
                    .where(BookTable.$.id().eq(bookId))
                    .select(BookTable.$.fetch(BookFetcher.$.store(BookStoreFetcher.$.name())))
                    .useObjectCache(BookFetcher.$.store(BookStoreFetcher.$.name()))
                    .execute();
            assertEquals(1, inTx.size());
            assertEquals(otherStoreId, inTx.get(0).store().id());
            assertEquals(pendingName, inTx.get(0).store().name());
        } finally {
            transactionManager.rollback();
        }

        // The rollback restored the committed child and the pending name was never
        // published into the shared cache.
        List<BookStore> after = queryStore(otherStoreId);
        assertEquals(1, after.size());
        assertEquals(originalOtherName, after.get(0).name());
    }

    private long anyBookId() {
        return sqlClient.createQuery(BookTable.$)
                .where(BookTable.$.storeId().isNotNull())
                .select(BookTable.$.id())
                .limit(1)
                .execute()
                .get(0);
    }

    private long bookStoreId(long bookId) {
        return sqlClient.createQuery(BookTable.$)
                .where(BookTable.$.id().eq(bookId))
                .select(BookTable.$.storeId())
                .execute()
                .get(0);
    }

    private long anyOtherStoreId(long storeId) {
        return sqlClient.createQuery(BookStoreTable.$)
                .where(BookStoreTable.$.id().ne(storeId))
                .select(BookStoreTable.$.id())
                .limit(1)
                .execute()
                .get(0);
    }

    private List<BookStore> queryStore(long id) {
        return sqlClient.createQuery(BookStoreTable.$)
                .where(BookStoreTable.$.id().eq(id))
                .select(BookStoreTable.$.fetch(BookStoreFetcher.$.allScalarFields()))
                .useObjectCache()
                .execute();
    }

    private long anyStoreId() {
        return sqlClient.createQuery(BookStoreTable.$)
                .select(BookStoreTable.$.id())
                .limit(1)
                .execute()
                .get(0);
    }

    private String storeName(long id) {
        return sqlClient.createQuery(BookStoreTable.$)
                .where(BookStoreTable.$.id().eq(id))
                .select(BookStoreTable.$.name())
                .execute()
                .get(0);
    }

    private static boolean autoCommit(Connection con) {
        try {
            return con.getAutoCommit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Minimal {@link DataSource} that always hands out the same connection, used to
     * drive an introspection failure on a manager-owned connection.
     */
    private static final class SingleConnectionDataSource implements DataSource {

        private final Connection connection;

        SingleConnectionDataSource(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper for " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
