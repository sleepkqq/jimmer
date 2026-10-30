package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.inject.Inject;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.dialect.PostgresDialect;
import org.babyfish.jimmer.sql.event.TriggerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.it.entity.BookProps;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.BookStoreDraft;
import io.quarkiverse.jimmer.it.entity.BookStoreTable;
import io.quarkiverse.jimmer.it.entity.BookFetcher;
import io.quarkiverse.jimmer.it.entity.AuthorFetcher;
import io.quarkiverse.jimmer.runtime.cfg.support.QuarkusConnectionManager;
import io.quarkiverse.jimmer.runtime.cfg.support.QuarkusTransientResolverProvider;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Post-commit eviction for a {@code BINLOG_ONLY} client: the {@link QuarkusConnectionManager} is the
 * {@link org.babyfish.jimmer.sql.runtime.MutationKeyListener} and must evict only after commit.
 */
@QuarkusTest
@TestProfile(MutationKeyListenerEvictionTest.Profile.class)
class MutationKeyListenerEvictionTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.jimmer.trigger-type", "BINLOG_ONLY",
                    "quarkus.jimmer.transaction-cache-operator-fixed-delay", "off",
                    "quarkus.datasource.jdbc.min-size", "0",
                    "quarkus.datasource.jdbc.max-size", "2",
                    "quarkus.datasource.jdbc.acquisition-timeout", "3s");
        }
    }

    @Inject AgroalDataSource dataSource;
    @Inject JSqlClient client;

    private final List<Long> stores = new CopyOnWriteArrayList<>();
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Handler logHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLoggerName() != null
                    && record.getLoggerName().contains("QuarkusConnectionManager")
                    && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void setUp() {
        Logger.getLogger("").addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        try {
            QuarkusTransaction.requiringNew().run(() -> stores.forEach(id -> client.deleteById(BookStore.class, id)));
        } finally {
            Logger.getLogger("").removeHandler(logHandler);
            stores.clear();
            warnings.clear();
        }
    }

    @Test
    void committedUpdateEvictsTheObjectCache() {
        long id = store();
        update(id, "committed");
        assertEquals("committed", client.findById(BookStore.class, id).website());

        // Bypass Jimmer: without an eviction the stale "committed" row would be served from cache.
        updateNatively(id, "bypassed");
        assertEquals("committed", client.findById(BookStore.class, id).website());
    }

    @Test
    void rolledBackUpdateDoesNotEvict() {
        long id = store();
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            updateInCurrentTransaction(id, "rolled-back");
            throw new IllegalStateException("rollback");
        }));
        updateNatively(id, "bypassed");
        assertEquals("initial", client.findById(BookStore.class, id).website());
    }

    @Test
    void autoCommitUpdateEvictsAfterTheConnectionIsClosed() {
        long id = store();
        client.createUpdate(BookStoreTable.$)
                .set(BookStoreTable.$.website(), "autocommit")
                .where(BookStoreTable.$.id().eq(id))
                .execute();
        assertEquals("autocommit", client.findById(BookStore.class, id).website());
    }

    @Test
    void committedDeleteEvictsTheObjectCache() {
        long id = store();
        QuarkusTransaction.requiringNew().run(() -> client.deleteById(BookStore.class, id));
        stores.remove(id);
        assertNull(client.findById(BookStore.class, id));
        assertNull(readNatively(id));
    }

    @Test
    void committedAssociationMutationEvictsThePropertyCache() {
        long bookId = 1L;
        long authorId = 5L;
        assertFalse(authorIdsOf(bookId).contains(authorId));
        QuarkusTransaction.requiringNew().run(
                () -> client.getAssociations(BookProps.AUTHORS).saveCommand(bookId, authorId).execute());
        try {
            assertTrue(authorIdsOf(bookId).contains(authorId));
        } finally {
            QuarkusTransaction.requiringNew().run(
                    () -> client.getAssociations(BookProps.AUTHORS).deleteCommand(bookId, authorId).execute());
        }
    }

    @Test
    void userSuppliedConnectionEvictsNothing() {
        long id = store();
        try (Connection con = dataSource.getConnection()) {
            client.createUpdate(BookStoreTable.$)
                    .set(BookStoreTable.$.website(), "external")
                    .where(BookStoreTable.$.id().eq(id))
                    .execute(con);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        assertEquals("external", readNatively(id));
        assertEquals("initial", client.findById(BookStore.class, id).website());
    }

    @Test
    void failedEvictionIsLoggedButDoesNotFailTheCommit() {
        long id = store();
        AtomicInteger attempts = new AtomicInteger();
        Cache<Long, BookStore> throwingCache = new Cache<>() {
            @Override public ImmutableType type() { return ImmutableType.get(BookStore.class); }
            @Override public ImmutableProp prop() { return null; }
            @Override
            public Map<Long, BookStore> getAll(Collection<Long> keys, CacheEnvironment<Long, BookStore> env) {
                return Map.of();
            }
            @Override
            public void deleteAll(Collection<Long> keys, Object reason) {
                attempts.incrementAndGet();
                throw new IllegalStateException("cache unavailable");
            }
        };
        QuarkusConnectionManager connectionManager = new QuarkusConnectionManager(dataSource);
        JSqlClient failingClient = JSqlClient.newBuilder()
                .setConnectionManager(connectionManager)
                .setTransientResolverProvider(new QuarkusTransientResolverProvider(Arc.container()))
                .setDialect(new PostgresDialect())
                .setTriggerType(TriggerType.BINLOG_ONLY)
                .setMutationTransactionRequired(false)
                .setCaches(config -> config.setObjectCache(BookStore.class, throwingCache))
                .build();
        connectionManager.setCaches(failingClient.getCaches());

        QuarkusTransaction.requiringNew().run(() -> failingClient.createUpdate(BookStoreTable.$)
                .set(BookStoreTable.$.website(), "failure")
                .where(BookStoreTable.$.id().eq(id))
                .execute());

        assertEquals("failure", readNatively(id));
        assertEquals(1, attempts.get());
        assertTrue(
                warnings.stream().anyMatch(it -> it.getThrown() instanceof IllegalStateException),
                () -> warnings.toString());
    }

    private long store() {
        BookStore store = QuarkusTransaction.requiringNew().call(() -> client.save(BookStoreDraft.$.produce(draft -> draft
                        .setName(UUID.randomUUID().toString())
                        .setWebsite("initial")
                        .setCreatedTime(LocalDateTime.now())
                        .setModifiedTime(LocalDateTime.now())))
                .getModifiedEntity());
        stores.add(store.id());
        assertEquals("initial", client.findById(BookStore.class, store.id()).website());
        return store.id();
    }

    private void update(long id, String website) {
        QuarkusTransaction.requiringNew().run(() -> updateInCurrentTransaction(id, website));
    }

    private void updateInCurrentTransaction(long id, String website) {
        client.createUpdate(BookStoreTable.$)
                .set(BookStoreTable.$.website(), website)
                .where(BookStoreTable.$.id().eq(id))
                .execute();
    }

    private List<Long> authorIdsOf(long bookId) {
        Book book = client.findById(BookFetcher.$.allScalarFields().authors(AuthorFetcher.$.firstName()), bookId);
        assertNotNull(book);
        return book.authors().stream().map(it -> it.id()).toList();
    }

    private void updateNatively(long id, String website) {
        try (Connection con = dataSource.getConnection();
                PreparedStatement stmt = con.prepareStatement("update book_store set website = ? where id = ?")) {
            stmt.setString(1, website);
            stmt.setLong(2, id);
            stmt.executeUpdate();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private String readNatively(long id) {
        try (Connection con = dataSource.getConnection();
                PreparedStatement stmt = con.prepareStatement("select website from book_store where id = ?")) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
