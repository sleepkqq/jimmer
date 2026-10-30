package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.model.BookStore;
import org.babyfish.jimmer.sql.model.BookStoreDraft;
import org.babyfish.jimmer.sql.model.BookStoreFetcher;
import org.babyfish.jimmer.sql.model.BookStoreTable;
import org.babyfish.jimmer.sql.model.inheritance.singletable.Client;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientFetcher;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientTable;
import org.babyfish.jimmer.sql.model.inheritance.singletable.Organization;
import org.babyfish.jimmer.sql.model.inheritance.singletable.PersonDraft;
import org.babyfish.jimmer.sql.model.inheritance.singletable.dto.ClientImplicitCatchAllView;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.babyfish.jimmer.sql.common.Constants.oreillyId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cache-hydration regression coverage that does not belong in the projection matrix:
 * a cache payload of the wrong concrete type must decline before any conversion, and
 * two projections of the same table sharing one explicit mask must still receive the
 * correct content for a row whose admission the policy denied.
 */
public class ObjectCacheHydrationGuardTest extends AbstractQueryTest {

    @Test
    public void testAutomaticDtoSlotDeclinesWrongConcreteTypePayload() {
        ImmutableType clientType = ImmutableType.get(Client.class);
        MapCache<Client> cache = new MapCache<>(clientType);
        JSqlClient client = createClient(type -> type == clientType ? cache : null);
        // The database row 100 is an Organization; the cache reports a Person.
        cache.put(100L, PersonDraft.$.produce(draft -> {
            draft.setId(100L);
            draft.setName("WRONG-TYPE");
            draft.setFirstName("Wrong");
        }));
        ClientTable table = ClientTable.$;
        clearExecutions();
        List<ClientImplicitCatchAllView> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(client.createQuery(table)
                .where(table.id().eq(100L))
                .select(table.fetch(ClientImplicitCatchAllView.class))
                .execute(con)));
        assertEquals(1, rows.size());
        ClientImplicitCatchAllView.Organization organization =
                assertInstanceOf(ClientImplicitCatchAllView.Organization.class, rows.get(0));
        assertEquals("Acme", organization.getName());
        assertEquals("ACME-001", organization.getTaxCode());
        assertEquals(1, cache.getAllKeys.size(), cache.getAllKeys::toString);
        assertEquals(2, getExecutions().size(), () -> sqlOf(getExecutions()));
        String fullSql = getExecutions().get(1).getSql();
        assertTrue(fullSql.contains("NAME"), fullSql);
        assertTrue(fullSql.contains("TAX_CODE"), fullSql);
    }

    @Test
    public void testExplicitHintDeclinesWrongConcreteTypePayload() {
        ImmutableType clientType = ImmutableType.get(Client.class);
        MapCache<Client> cache = new MapCache<>(clientType);
        JSqlClient client = createClient(type -> type == clientType ? cache : null);
        cache.put(100L, PersonDraft.$.produce(draft -> {
            draft.setId(100L);
            draft.setName("WRONG-TYPE");
        }));
        ClientTable table = ClientTable.$;
        clearExecutions();
        List<Client> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(client.createQuery(table)
                .where(table.id().eq(100L))
                .select(table.fetch(ClientFetcher.$.name()))
                .useObjectCache()
                .execute(con)));
        assertEquals(1, rows.size());
        Organization organization = assertInstanceOf(Organization.class, rows.get(0));
        assertEquals("Acme", organization.name());
        assertEquals(1, cache.getAllKeys.size(), cache.getAllKeys::toString);
        assertEquals(2, getExecutions().size(), () -> sqlOf(getExecutions()));
        String fullSql = getExecutions().get(1).getSql();
        assertTrue(fullSql.contains("NAME"), fullSql);
    }

    @Test
    public void testDeniedRowServesBothProjectionsOfTheSameTable() {
        // Both slots resolve to the same explicit-mask node, so the denied row is loaded
        // once with the first slot's projection. A mask leaf missing from that projection
        // is rejected by retainedFetcher, so a sibling leaf can only stay SQL-fresh.
        ImmutableType type = ImmutableType.get(BookStore.class);
        MapCache<BookStore> cache = new MapCache<>(type);
        JSqlClient client = createClient(
                it -> it == type ? cache : null,
                new CacheAdmissionPolicy() {
                    @Override
                    public boolean isAdmissible(ImmutableType admittedType) {
                        return true;
                    }

                    @Override
                    public boolean isAdmissible(ImmutableType admittedType, Object row) {
                        return admittedType != type;
                    }
                }
        );
        cache.put(oreillyId, BookStoreDraft.$.produce(draft -> {
            draft.setId(oreillyId);
            draft.setName("CACHED");
            draft.setWebsite("CACHED");
        }));
        BookStoreTable table = BookStoreTable.$;
        clearExecutions();
        List<Tuple2<BookStore, BookStore>> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(client.createQuery(table)
                .where(table.id().eq(oreillyId))
                .select(
                        table.fetch(BookStoreFetcher.$.name()),
                        table.fetch(BookStoreFetcher.$.name().version())
                )
                .useObjectCache(BookStoreFetcher.$.name())
                .execute(con)));
        assertEquals(1, rows.size());
        assertEquals("O'REILLY", rows.get(0).get_1().name());
        BookStore wide = rows.get(0).get_2();
        assertEquals("O'REILLY", wide.name());
        assertEquals(0, wide.version());
        assertTrue(cache.getAllKeys.isEmpty(), cache.getAllKeys::toString);
        assertEquals(2, getExecutions().size(), () -> sqlOf(getExecutions()));
        String retainedSql = getExecutions().get(0).getSql();
        assertFalse(retainedSql.contains("NAME"), retainedSql);
        assertTrue(retainedSql.contains("VERSION"), retainedSql);
        String deniedSql = getExecutions().get(1).getSql();
        assertTrue(deniedSql.contains("NAME"), deniedSql);
    }

    private JSqlClient createClient(Function<ImmutableType, Cache<?, ?>> objectCacheFactory) {
        return createClient(objectCacheFactory, (CacheAdmissionPolicy) null);
    }

    private JSqlClient createClient(
            Function<ImmutableType, Cache<?, ?>> objectCacheFactory,
            CacheAdmissionPolicy admissionPolicy
    ) {
        return getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public boolean isObjectCacheAdmissible(ImmutableType type) {
                    return admissionPolicy == null || admissionPolicy.isAdmissible(type);
                }

                @Override
                public boolean isObjectCacheAdmissible(ImmutableType type, Object row) {
                    return admissionPolicy == null || admissionPolicy.isAdmissible(type, row);
                }

                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return objectCacheFactory.apply(type);
                }
            }));
        });
    }

    private static String sqlOf(List<Execution> executions) {
        StringBuilder builder = new StringBuilder();
        for (Execution execution : executions) {
            builder.append(execution.getSql()).append('\n');
        }
        return builder.toString();
    }

    private static class MapCache<T> implements Cache<Object, T> {

        private final ImmutableType type;

        private final Map<Object, T> map = new LinkedHashMap<>();

        private final List<Collection<Object>> getAllKeys = new ArrayList<>();

        private MapCache(ImmutableType type) {
            this.type = type;
        }

        private void put(Object key, T value) {
            map.put(key, value);
        }

        @NotNull
        @Override
        public ImmutableType type() {
            return type;
        }

        @Nullable
        @Override
        public ImmutableProp prop() {
            return null;
        }

        @NotNull
        @Override
        public Map<Object, T> getAll(
                @NotNull Collection<Object> keys,
                @NotNull CacheEnvironment<Object, T> env
        ) {
            getAllKeys.add(keys);
            Map<Object, T> resultMap = new LinkedHashMap<>();
            for (Object key : keys) {
                if (map.containsKey(key)) {
                    resultMap.put(key, map.get(key));
                }
            }
            return resultMap;
        }

        @Override
        public void deleteAll(@NotNull Collection<Object> keys, @Nullable Object reason) {
            map.keySet().removeAll(keys);
        }
    }

    /**
     * A transaction-agnostic test manager: object-cache projection only activates when
     * the connection is positively known to be outside a transaction.
     */
    private static final ConnectionManager NON_TX_MANAGER = new ConnectionManager() {
        @Override
        public <R> R execute(Connection con, Function<Connection, R> block) {
            return testConnectionManager().execute(con, block);
        }

        @Override
        public boolean isTransactionKnownInactive(Connection con) {
            return con != null;
        }
    };
}
