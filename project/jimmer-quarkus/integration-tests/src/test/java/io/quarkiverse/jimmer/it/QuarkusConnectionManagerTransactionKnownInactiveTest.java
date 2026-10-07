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
import org.babyfish.jimmer.sql.ast.query.TypedRootQuery;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.it.entity.BookFetcher;
import io.quarkiverse.jimmer.it.entity.BookProps;
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
     * the {@code BookStore} content-only cache and {@code Book} association cache.
     * Without it the default client resolves no cache
     * factory and the cache-bypass assertion below would be vacuous.
     */
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.arc.exclude-types", "io.quarkiverse.jimmer.it.config.CacheConfig",
                    "quarkus.jimmer.cache.entities[0].type", "BookStore",
                    "quarkus.jimmer.cache.entities[0].mode", "FULL",
                    "quarkus.jimmer.cache.entities[0].content-only", "true",
                    "quarkus.jimmer.cache.entities[0].content-fields", "name",
                    "quarkus.jimmer.cache.entities[1].type", "Book",
                    "quarkus.jimmer.cache.entities[1].mode", "FULL");
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
            List<BookStore> inTx = sqlClient.createQuery(BookStoreTable.$)
                    .where(BookStoreTable.$.id().eq(id))
                    .select(BookStoreTable.$.fetch(BookStoreFetcher.$.name()))
                    .useObjectCache()
                    .execute();
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
    void contentFetcherColdCacheInsideTransactionNeverPublishesPendingValue() throws Exception {
        long bookId = anyBookId();
        long id = bookStoreId(bookId);
        String original = storeName(id);
        Cache<Object, BookStore> cache =
                sqlClient.getCaches().<Object, BookStore>getObjectCache(ImmutableType.get(BookStore.class));
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

    @Test
    void contentFetcherMergedColdCacheInsideTransactionNeverPublishesPendingValue() throws Exception {
        long bookId = anyBookId();
        long id = bookStoreId(bookId);
        String original = storeName(id);
        Cache<Object, BookStore> cache =
                sqlClient.getCaches().<Object, BookStore>getObjectCache(ImmutableType.get(BookStore.class));
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
            List<Book> inTx = TypedRootQuery.unionAll(
                    sqlClient.createQuery(BookTable.$)
                            .where(BookTable.$.id().eq(bookId))
                            .select(BookTable.$.fetch(BookFetcher.$.store(
                                    ReferenceFetchType.SELECT,
                                    BookStoreFetcher.$.name()
                            ))),
                    sqlClient.createQuery(BookTable.$)
                            .where(BookTable.$.id().eq(bookId))
                            .select(BookTable.$.fetch(BookFetcher.$.store(
                                    ReferenceFetchType.SELECT,
                                    BookStoreFetcher.$.name()
                            )))
                            .useObjectCache(BookFetcher.$.store(BookStoreFetcher.$.name()))
            ).execute();
            assertEquals(2, inTx.size());
            assertEquals(pending, inTx.get(0).store().name());
            assertEquals(pending, inTx.get(1).store().name());
        } finally {
            transactionManager.rollback();
        }

        // A cold child load in the merged transaction must not publish its pending value.
        List<BookStore> after = queryStore(id);
        assertEquals(1, after.size());
        assertEquals(original, after.get(0).name());
    }

    @Test
    void ordinaryAssociationReadBypassesStaleEdgeAndUnapprovedChildCache() throws Exception {
        long bookId = anyBookId();
        long originalStoreId = bookStoreId(bookId);
        long targetStoreId = anyOtherStoreId(originalStoreId);
        String originalName = storeName(targetStoreId);
        int originalVersion = storeVersion(targetStoreId);
        String committedName = originalName + "-committed";
        int committedVersion = originalVersion + 1;

        // Seed a stale Book.store edge and child display in the real shared caches.
        Cache<Object, Object> edgeCache = sqlClient.getCaches().getPropertyCache(BookProps.STORE.unwrap());
        Cache<Object, BookStore> storeCache =
                sqlClient.getCaches().<Object, BookStore>getObjectCache(ImmutableType.get(BookStore.class));
        assertNotNull(edgeCache);
        assertNotNull(storeCache);
        queryStore(targetStoreId);
        try (Connection con = dataSource.getConnection()) {
            edgeCache.getAll(Collections.<Object>singletonList(bookId), new CacheEnvironment<>(
                    sqlClient,
                    con,
                    keys -> Map.<Object, Object>of(bookId, originalStoreId),
                    false
            ));
        }

        try {
            try (Connection con = dataSource.getConnection()) {
                con.setAutoCommit(true);
                try (PreparedStatement ps = con.prepareStatement("update BOOK set STORE_ID = ? where ID = ?")) {
                    ps.setLong(1, targetStoreId);
                    ps.setLong(2, bookId);
                    assertEquals(1, ps.executeUpdate());
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?"
                )) {
                    ps.setString(1, committedName);
                    ps.setInt(2, committedVersion);
                    ps.setLong(3, targetStoreId);
                    assertEquals(1, ps.executeUpdate());
                }
            }

            List<Book> rows = sqlClient.createQuery(BookTable.$)
                    .where(BookTable.$.id().eq(bookId))
                    .select(BookTable.$.fetch(BookFetcher.$.store(BookStoreFetcher.$.name().version())))
                    .execute();
            assertEquals(1, rows.size());
            assertEquals(targetStoreId, rows.get(0).store().id());
            assertEquals(committedName, rows.get(0).store().name());
            assertEquals(committedVersion, rows.get(0).store().version());
        } finally {
            try (Connection con = dataSource.getConnection()) {
                con.setAutoCommit(true);
                try (PreparedStatement ps = con.prepareStatement("update BOOK set STORE_ID = ? where ID = ?")) {
                    ps.setLong(1, originalStoreId);
                    ps.setLong(2, bookId);
                    assertEquals(1, ps.executeUpdate());
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?"
                )) {
                    ps.setString(1, originalName);
                    ps.setInt(2, originalVersion);
                    ps.setLong(3, targetStoreId);
                    assertEquals(1, ps.executeUpdate());
                }
            }
            edgeCache.delete(bookId);
            storeCache.delete(targetStoreId);
        }
    }

    @Test
    void ordinaryWarmAndColdAssociationReadsDoNotPublishRolledBackChild() throws Exception {
        long bookId = anyBookId();
        long originalStoreId = bookStoreId(bookId);
        long targetStoreId = anyOtherStoreId(originalStoreId);
        String originalName = storeName(targetStoreId);
        Cache<Object, BookStore> storeCache =
                sqlClient.getCaches().<Object, BookStore>getObjectCache(ImmutableType.get(BookStore.class));
        Cache<Object, Object> edgeCache = sqlClient.getCaches().getPropertyCache(BookProps.STORE.unwrap());
        assertNotNull(storeCache);
        assertNotNull(edgeCache);

        List<BookStore> warm = queryStore(targetStoreId);
        assertEquals(1, warm.size());
        try (Connection con = dataSource.getConnection()) {
            edgeCache.getAll(Collections.<Object>singletonList(bookId), new CacheEnvironment<>(
                    sqlClient,
                    con,
                    keys -> Map.<Object, Object>of(bookId, originalStoreId),
                    false
            ));
        }

        String warmPendingName = originalName + "-warm-pending";
        assertOrdinaryAssociationInsideTransaction(bookId, targetStoreId, warmPendingName);
        assertEquals(originalStoreId, bookStoreId(bookId));
        assertEquals(originalName, storeName(targetStoreId));
        List<BookStore> afterWarmRollback = queryStore(targetStoreId);
        assertEquals(1, afterWarmRollback.size());
        assertEquals(originalName, afterWarmRollback.get(0).name());

        // Repeat after clearing both tiers so the uncommitted child load is cold.
        storeCache.delete(targetStoreId);
        edgeCache.delete(bookId);

        String pendingName = originalName + "-pending";
        assertOrdinaryAssociationInsideTransaction(bookId, targetStoreId, pendingName);

        assertEquals(originalStoreId, bookStoreId(bookId));
        assertEquals(originalName, storeName(targetStoreId));
        List<BookStore> after = queryStore(targetStoreId);
        assertEquals(1, after.size());
        assertEquals(originalName, after.get(0).name());
        storeCache.delete(targetStoreId);
        edgeCache.delete(bookId);
    }

    private void assertOrdinaryAssociationInsideTransaction(long bookId, long storeId, String pendingName)
            throws Exception {
        transactionManager.begin();
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("update BOOK set STORE_ID = ? where ID = ?")) {
                ps.setLong(1, storeId);
                ps.setLong(2, bookId);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = con.prepareStatement("update BOOK_STORE set NAME = ? where ID = ?")) {
                ps.setString(1, pendingName);
                ps.setLong(2, storeId);
                assertEquals(1, ps.executeUpdate());
            }
            List<Book> inTx = sqlClient.createQuery(BookTable.$)
                    .where(BookTable.$.id().eq(bookId))
                    .select(BookTable.$.fetch(BookFetcher.$.store(BookStoreFetcher.$.name())))
                    .execute();
            assertEquals(1, inTx.size());
            assertEquals(storeId, inTx.get(0).store().id());
            assertEquals(pendingName, inTx.get(0).store().name());
        } finally {
            transactionManager.rollback();
        }
    }

    @Test
    void contentFetcherNestedChildInsideTransactionReadsPendingTarget() throws Exception {
        long bookId = anyBookId();
        long originalStoreId = bookStoreId(bookId);
        long otherStoreId = anyOtherStoreId(originalStoreId);
        String originalOtherName = storeName(otherStoreId);

        // Warm approved display content for both possible children.
        List<BookStore> warm = queryStore(originalStoreId);
        assertEquals(1, warm.size());
        List<BookStore> warmOther = queryStore(otherStoreId);
        assertEquals(1, warmOther.size());

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

            List<Book> mergedInTx = TypedRootQuery.unionAll(
                    sqlClient.createQuery(BookTable.$)
                            .where(BookTable.$.id().eq(bookId))
                            .select(BookTable.$.fetch(BookFetcher.$.store(
                                    ReferenceFetchType.SELECT,
                                    BookStoreFetcher.$.name()
                            ))),
                    sqlClient.createQuery(BookTable.$)
                            .where(BookTable.$.id().eq(bookId))
                            .select(BookTable.$.fetch(BookFetcher.$.store(
                                    ReferenceFetchType.SELECT,
                                    BookStoreFetcher.$.name()
                            )))
                            .useObjectCache(BookFetcher.$.store(BookStoreFetcher.$.name()))
            ).execute();
            assertEquals(2, mergedInTx.size());
            assertEquals(pendingName, mergedInTx.get(0).store().name());
            assertEquals(pendingName, mergedInTx.get(1).store().name());
        } finally {
            transactionManager.rollback();
        }

        // The rollback restored the committed child and the pending name was never
        // published into the shared cache.
        List<BookStore> after = queryStore(otherStoreId);
        assertEquals(1, after.size());
        assertEquals(originalOtherName, after.get(0).name());
        assertEquals(originalStoreId, bookStoreId(bookId));
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

    private int storeVersion(long id) {
        return sqlClient.createQuery(BookStoreTable.$)
                .where(BookStoreTable.$.id().eq(id))
                .select(BookStoreTable.$.version())
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
                .select(BookStoreTable.$.fetch(BookStoreFetcher.$.name()))
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
