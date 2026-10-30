package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.common.CacheImpl;
import org.babyfish.jimmer.sql.model.BookFetcher;
import org.babyfish.jimmer.sql.model.BookTable;
import org.babyfish.jimmer.sql.model.dto.BookView;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.babyfish.jimmer.sql.common.Constants.graphQLInActionId1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automatic content admission is an optimization. When it cannot be applied - the
 * transaction is unproven or the execution declines - the query must run the ordinary
 * legacy-cached path, not a fully cache-disabled one. Explicit hints keep the isolated
 * cache-disabled fallback.
 */
public class AutomaticContentAdmissionFallbackTest extends AbstractQueryTest {

    @Test
    public void testAutomaticAdmissionKeepsLegacyCachesWhenTransactionIsUnproven() {
        assertLegacyCachesUsed(new ConnectionManager() {
            @Override
            public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
                return testConnectionManager().execute(con, block);
            }

            @Override
            public boolean isTransactionKnownInactive(Connection con) {
                return false;
            }
        });
    }

    @Test
    public void testAutomaticAdmissionKeepsLegacyCachesWithDefaultTransactionProof() {
        assertLegacyCachesUsed(new ConnectionManager() {
            @Override
            public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
                return testConnectionManager().execute(con, block);
            }
        });
    }

    @Test
    public void testExplicitMaskStillDisablesCachesWhenTransactionIsUnproven() {
        JSqlClient client = newClient(new ConnectionManager() {
            @Override
            public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
                return testConnectionManager().execute(con, block);
            }

            @Override
            public boolean isTransactionKnownInactive(Connection con) {
                return false;
            }
        });
        BookTable table = BookTable.$;
        List<BookView> warm = new ArrayList<>();
        jdbc(con -> warm.addAll(client.createQuery(table)
                .where(table.id().eq(graphQLInActionId1))
                .select(table.fetch(BookView.class))
                .useObjectCache(BookFetcher.$.name())
                .execute(con)));
        assertViewContent(warm);

        clearExecutions();
        List<BookView> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(client.createQuery(table)
                .where(table.id().eq(graphQLInActionId1))
                .select(table.fetch(BookView.class))
                .useObjectCache(BookFetcher.$.name())
                .execute(con)));
        assertViewContent(rows);
        // An explicit mask keeps the isolated cache-disabled graph: the store and author
        // loaders must run SQL even though the ordinary caches are warm.
        assertEquals(3, getExecutions().size(), getExecutions().toString());
        assertTrue(getExecutions().get(1).getSql().contains("BOOK_STORE"), getExecutions().toString());
        assertTrue(getExecutions().get(2).getSql().contains("AUTHOR"), getExecutions().toString());
    }

    private void assertLegacyCachesUsed(ConnectionManager connectionManager) {
        JSqlClient client = newClient(connectionManager);
        BookTable table = BookTable.$;
        List<BookView> warm = new ArrayList<>();
        jdbc(con -> warm.addAll(client.createQuery(table)
                .where(table.id().eq(graphQLInActionId1))
                .select(table.fetch(BookView.class))
                .execute(con)));
        assertViewContent(warm);

        clearExecutions();
        List<BookView> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(client.createQuery(table)
                .where(table.id().eq(graphQLInActionId1))
                .select(table.fetch(BookView.class))
                .execute(con)));
        assertViewContent(rows);
        // Automatic admission cannot be proven here, so the ordinary path runs and the
        // warm association/object caches serve the store and author loaders.
        assertEquals(1, getExecutions().size(), getExecutions().toString());
        assertTrue(getExecutions().get(0).getSql().contains("from BOOK"), getExecutions().toString());
    }

    private static void assertViewContent(List<BookView> rows) {
        assertEquals(1, rows.size());
        BookView view = rows.get(0);
        assertEquals("GraphQL in Action", view.getName());
        assertEquals("MANNING", view.getStore().getName());
        assertEquals(1, view.getAuthors().size());
        assertEquals("Samer", view.getAuthors().get(0).getFirstName());
    }

    private JSqlClient newClient(ConnectionManager connectionManager) {
        return getSqlClient(builder -> {
            builder.setConnectionManager(connectionManager);
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return new CacheImpl<>(type);
                }

                @Override
                public Cache<?, ?> createAssociatedIdCache(ImmutableProp prop) {
                    return new CacheImpl<>(prop);
                }

                @Override
                public Cache<?, List<?>> createAssociatedIdListCache(ImmutableProp prop) {
                    return new CacheImpl<>(prop);
                }
            }));
        });
    }
}
