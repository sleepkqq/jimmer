package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JoinType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.impl.EntitiesImpl;
import org.babyfish.jimmer.sql.ast.impl.table.FetcherSelectionImpl;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.ast.tuple.Tuple3;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.filter.Filter;
import org.babyfish.jimmer.sql.filter.FilterArgs;
import org.babyfish.jimmer.sql.model.Author;
import org.babyfish.jimmer.sql.model.Book;
import org.babyfish.jimmer.sql.model.BookFetcher;
import org.babyfish.jimmer.sql.model.BookStore;
import org.babyfish.jimmer.sql.model.BookStoreFetcher;
import org.babyfish.jimmer.sql.model.BookStoreProps;
import org.babyfish.jimmer.sql.model.BookStoreTable;
import org.babyfish.jimmer.sql.model.BookTable;
import org.babyfish.jimmer.sql.model.BookTableEx;
import org.babyfish.jimmer.sql.model.Country;
import org.babyfish.jimmer.sql.model.dto.ReusableBookStoreView;
import org.babyfish.jimmer.sql.model.inheritance.singletable.Client;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientFetcher;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientTable;
import org.babyfish.jimmer.sql.model.inheritance.singletable.Organization;
import org.babyfish.jimmer.sql.model.inheritance.singletable.Person;
import org.babyfish.jimmer.sql.model.inheritance.singletable.PersonDraft;
import org.babyfish.jimmer.sql.model.inheritance.singletable.dto.ClientImplicitCatchAllView;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.EntityManager;
import org.babyfish.jimmer.sql.tuple.EntityTuple;
import org.babyfish.jimmer.sql.tuple.EntityTupleMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.babyfish.jimmer.sql.common.Constants.learningGraphQLId1;
import static org.babyfish.jimmer.sql.common.Constants.learningGraphQLId2;
import static org.babyfish.jimmer.sql.common.Constants.oreillyId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Query-level object-cache projection coverage. Every case warms the object cache
 * through the ordinary {@code Entities} path, then runs the {@code useObjectCache}
 * query so the skeleton id/entity seed and the cache-owner/concrete-type handling
 * are exercised on the warm path without asserting the canonical loader SQL.
 */
public class ObjectCacheQueryProjectionTest extends AbstractQueryTest {

    private static final List<Long> CLIENT_IDS = Arrays.asList(100L, 101L);

    private static final AtomicInteger STORE_CONVERSIONS = new AtomicInteger();

    private static final String CLIENT_SKELETON_SQL =
            "select tb_1_.ID, tb_1_.CLIENT_TYPE from CLIENT tb_1_ where tb_1_.ID in (?, ?)";

    private static final String CLIENT_ENTITY_SQL =
            "select tb_1_.ID, tb_1_.CLIENT_TYPE, tb_1_.NAME from CLIENT tb_1_ where tb_1_.ID in (?, ?)";

    private static final String CLIENT_CACHE_MISS_SQL =
            "select tb_1_.ID, tb_1_.CLIENT_TYPE, tb_1_.NAME, tb_2_.TAX_CODE, tb_3_.FIRST_NAME, tb_3_.LAST_NAME " +
                    "from CLIENT tb_1_ " +
                    "left join CLIENT tb_2_ on tb_1_.ID = tb_2_.ID and tb_2_.CLIENT_TYPE = ? " +
                    "left join CLIENT tb_3_ on tb_1_.ID = tb_3_.ID and tb_3_.CLIENT_TYPE = ? " +
                    "where tb_1_.ID = ?";

    private JSqlClient sqlClient;

    @BeforeEach
    public void initialize() {
        sqlClient = createClient(type -> new MapCache<>(type));
    }

    @Test
    public void testPolymorphicSingleTableEntity() {
        ClientTable table = ClientTable.$;
        jdbc(con -> sqlClient.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        executeAndExpect(
                sqlClient.createQuery(table)
                        .where(table.id().in(CLIENT_IDS))
                        .select(table.fetch(ClientFetcher.$.name()))
                        .useObjectCache(),
                ctx -> {
                    ctx.sql(CLIENT_SKELETON_SQL).variables(100L, 101L);
                    ctx.rows(rows -> {
                        assertEquals(2, rows.size());
                        Organization organization = assertInstanceOf(Organization.class, rows.get(0));
                        assertEquals("Acme", organization.name());
                        Person person = assertInstanceOf(Person.class, rows.get(1));
                        assertEquals("Bob", person.name());
                    });
                }
        );
    }

    @Test
    public void testPolymorphicSingleTableSubtypeOnlyCaches() {
        JSqlClient subtypeClient = createClient(type -> {
            Class<?> javaClass = type.getJavaClass();
            return javaClass == Organization.class || javaClass == Person.class ? new MapCache<>(type) : null;
        });
        ClientTable table = ClientTable.$;
        jdbc(con -> {
            subtypeClient.getEntities().forConnection(con)
                    .findByIds(Organization.class, Collections.singletonList(100L));
            subtypeClient.getEntities().forConnection(con)
                    .findByIds(Person.class, Collections.singletonList(101L));
        });
        executeAndExpect(
                subtypeClient.createQuery(table)
                        .where(table.id().in(CLIENT_IDS))
                        .select(table.fetch(ClientFetcher.$.name()))
                        .useObjectCache(),
                ctx -> {
                    ctx.sql(CLIENT_SKELETON_SQL).variables(100L, 101L);
                    ctx.rows(rows -> {
                        assertEquals(2, rows.size());
                        assertInstanceOf(Organization.class, rows.get(0));
                        assertInstanceOf(Person.class, rows.get(1));
                    });
                }
        );
    }

    @Test
    public void testPolymorphicSingleTableDtoConverter() {
        ClientTable table = ClientTable.$;
        jdbc(con -> sqlClient.getEntities().forConnection(con)
                .findByIds(ClientImplicitCatchAllView.class, CLIENT_IDS));
        executeAndExpect(
                sqlClient.createQuery(table)
                        .where(table.id().in(CLIENT_IDS))
                        .select(table.fetch(ClientImplicitCatchAllView.class))
                        .useObjectCache(),
                ctx -> {
                    ctx.sql(CLIENT_SKELETON_SQL).variables(100L, 101L);
                    ctx.rows(rows -> {
                        assertEquals(2, rows.size());
                        ClientImplicitCatchAllView.Organization organization =
                                assertInstanceOf(ClientImplicitCatchAllView.Organization.class, rows.get(0));
                        assertEquals("ACME-001", organization.getTaxCode());
                        assertInstanceOf(ClientImplicitCatchAllView.Default.class, rows.get(1));
                    });
                }
        );
    }

    @Test
    public void testNonPolymorphicDtoConverter() {
        BookStoreTable table = BookStoreTable.$;
        jdbc(con -> sqlClient.getEntities().forConnection(con)
                .findByIds(ReusableBookStoreView.class, Collections.singletonList(oreillyId)));
        executeAndExpect(
                sqlClient.createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(ReusableBookStoreView.class))
                        .useObjectCache(),
                ctx -> {
                    ctx.sql("select tb_1_.ID from BOOK_STORE tb_1_ where tb_1_.ID = ?")
                            .variables(oreillyId);
                    ctx.rows(rows -> {
                        assertEquals(1, rows.size());
                        ReusableBookStoreView view = rows.get(0);
                        assertEquals(oreillyId, view.getId());
                        assertEquals("O'REILLY", view.getName());
                    });
                }
        );
    }

    @Test
    public void testCachedJoinedEntityUsesIdOnlySkeleton() {
        BookTable table = BookTable.$;
        // Warm the joined target through the ordinary Entities path, then run the
        // hinted query. The joined non-polymorphic entity is seeded by id and
        // hydrated from cache, so the target table's name column never appears.
        jdbc(con -> sqlClient.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        clearExecutions();
        List<Tuple2<BookStore, String>> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(
                                table.store().fetch(BookStoreFetcher.$.name()),
                                table.name()
                        )
                        .useObjectCache()
                        .execute(con)
        ));
        assertEquals(1, rows.size());
        assertEquals("O'REILLY", rows.get(0).get_1().name());
        assertEquals("Learning GraphQL", rows.get(0).get_2());
        List<Execution> executions = getExecutions();
        assertEquals(1, executions.size());
        String sql = executions.get(0).getSql();
        // The joined entity is seeded by its own id through the retained join, so
        // the skeleton keeps the original join topology and only the target's name
        // column is served from the object cache. Assert the join and the target id
        // directly so a fallback to the full joined projection cannot satisfy it.
        assertTrue(sql.contains("BOOK_STORE"), sql);
        assertTrue(sql.contains("tb_2_.ID"), sql);
        assertFalse(sql.contains("tb_2_.NAME"), sql);
        assertTrue(sql.contains("tb_1_.NAME"), sql);
    }

    @Test
    public void testCachedJoinedEntityDtoConverterRunsAfterCache() {
        BookTable table = BookTable.$;
        // Warm the joined target as a DTO so the object cache holds the underlying
        // BookStore entity, then select the same view through a hinted joined slot.
        jdbc(con -> sqlClient.getEntities().forConnection(con)
                .findByIds(ReusableBookStoreView.class, Collections.singletonList(oreillyId)));
        clearExecutions();
        List<Tuple2<ReusableBookStoreView, String>> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(
                                table.store().fetch(ReusableBookStoreView.class),
                                table.name()
                        )
                        .useObjectCache()
                        .execute(con)
        ));
        assertEquals(1, rows.size());
        ReusableBookStoreView view = rows.get(0).get_1();
        assertEquals(oreillyId, view.getId());
        assertEquals("O'REILLY", view.getName());
        assertEquals("Learning GraphQL", rows.get(0).get_2());
        List<Execution> executions = getExecutions();
        assertEquals(1, executions.size());
        String sql = executions.get(0).getSql();
        // The joined DTO is seeded by the target id through the retained join and
        // served from the object cache; the converter runs only after validation.
        assertTrue(sql.contains("BOOK_STORE"), sql);
        assertTrue(sql.contains("tb_2_.ID"), sql);
        assertFalse(sql.contains("tb_2_.NAME"), sql);
    }

    @Test
    public void testTargetFilteredJoinedEntityStaysFresh() {
        // A target filter turns the to-one join into a membership filter that the
        // id-only seed cannot preserve, so the joined slot must remain a fresh full
        // selection (join retained) while the root slot is still cached. Scope the
        // entity manager to the Book/BookStore closure so the unrelated non-nullable
        // Endorsement.bookStore association is not validated (a genuine target filter
        // on BookStore would otherwise be rejected globally).
        JSqlClient client = getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setEntityManager(new EntityManager(Book.class, BookStore.class, Author.class, Country.class));
            builder.addFilters(new Filter<BookStoreProps>() {
                @Override
                public void filter(FilterArgs<BookStoreProps> args) {
                }
            });
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return new MapCache<>(type);
                }
            }));
        });
        BookTable table = BookTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Book.class, Collections.singletonList(learningGraphQLId1)));
        clearExecutions();
        List<Tuple2<Book, BookStore>> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                client.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(
                                table.fetch(BookFetcher.$.name()),
                                table.store().fetch(BookStoreFetcher.$.name())
                        )
                        .useObjectCache()
                        .execute(con)
        ));
        assertEquals(1, rows.size());
        assertEquals("Learning GraphQL", rows.get(0).get_1().name());
        assertEquals("O'REILLY", rows.get(0).get_2().name());
        List<Execution> executions = getExecutions();
        assertEquals(1, executions.size());
        String sql = executions.get(0).getSql();
        // The root Book seed is id-only; the filtered joined target keeps its full
        // join so the filter still contributes to membership.
        assertFalse(sql.contains("tb_1_.NAME"), sql);
        assertTrue(sql.contains("join"), sql);
        assertTrue(sql.contains("tb_2_.NAME"), sql);
    }

    @Test
    public void testStoredNegativeJoinedCacheFallsBackToOriginalOnce() {
        BookTable table = BookTable.$;
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return type.getJavaClass() == BookStore.class ? storeCache : null;
                }
            }));
        });
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        // Store a real negative entry (an explicit null), distinct from an eviction
        // which would simply miss and reload.
        storeCache.put(oreillyId, null);
        Function<BookStore, BookStore> converter = store -> {
            STORE_CONVERSIONS.incrementAndGet();
            return store;
        };
        STORE_CONVERSIONS.set(0);
        clearExecutions();
        List<BookStore> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                client.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(new FetcherSelectionImpl<BookStore>(
                                table.store(),
                                BookStoreFetcher.$.name(),
                                converter
                        ))
                        .useObjectCache()
                        .execute(con)
        ));
        // The stored negative hit is validated before any conversion; the whole
        // original query then runs exactly once, so the page is never shortened and
        // the DTO converter runs exactly once per returned row.
        assertEquals(1, rows.size());
        assertEquals("O'REILLY", rows.get(0).name());
        assertEquals(1, STORE_CONVERSIONS.get());
        List<Execution> executions = getExecutions();
        assertEquals(2, executions.size());
        // First statement is the joined id-only skeleton (join retained, target name
        // absent); the second is the original full joined projection.
        String skeletonSql = executions.get(0).getSql();
        assertTrue(skeletonSql.contains("BOOK_STORE"), skeletonSql);
        assertTrue(skeletonSql.contains("tb_2_.ID"), skeletonSql);
        assertFalse(skeletonSql.contains("tb_2_.NAME"), skeletonSql);
        assertTrue(executions.get(1).getSql().contains("tb_2_.NAME"), executions.get(1).getSql());
    }

    @Test
    public void testNullableAndInnerOwnedJoinParity() {
        BookTable table = BookTable.$;
        try {
            // Flip the owning FK to NULL on an autocommit connection so the LEFT join
            // must yield a null target and an INNER join must drop the root.
            jdbc(null, false, con -> {
                try (PreparedStatement st = con.prepareStatement("update BOOK set STORE_ID = null where ID = ?")) {
                    st.setObject(1, learningGraphQLId1);
                    st.executeUpdate();
                }
            });
            jdbc(con -> {
                sqlClient.getEntities().forConnection(con)
                        .findByIds(Book.class, Collections.singletonList(learningGraphQLId1));
                sqlClient.getEntities().forConnection(con)
                        .findByIds(BookStore.class, Collections.singletonList(oreillyId));
            });
            clearExecutions();
            List<Tuple3<Book, BookStore, BigDecimal>> leftHinted = new ArrayList<>();
            jdbc(con -> leftHinted.addAll(
                    sqlClient.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(
                                    table.fetch(BookFetcher.$.name()),
                                    table.store(JoinType.LEFT).fetch(BookStoreFetcher.$.name()),
                                    table.price()
                            )
                            .useObjectCache()
                            .execute(con)
            ));
            assertEquals(1, leftHinted.size());
            assertEquals("Learning GraphQL", leftHinted.get(0).get_1().name());
            assertNull(leftHinted.get(0).get_2());
            assertEquals(0, leftHinted.get(0).get_3().compareTo(new BigDecimal("50")));
            List<Execution> leftExecutions = getExecutions();
            assertEquals(1, leftExecutions.size());
            String leftSql = leftExecutions.get(0).getSql();
            assertTrue(leftSql.contains("left join BOOK_STORE"), leftSql);
            assertFalse(leftSql.contains("tb_1_.NAME"), leftSql);
            assertFalse(leftSql.contains("tb_2_.NAME"), leftSql);
            assertTrue(leftSql.contains("tb_1_.PRICE"), leftSql);
            // Same membership as ordinary SQL for the LEFT join.
            List<Tuple3<Book, BookStore, BigDecimal>> leftOrdinary = new ArrayList<>();
            jdbc(con -> leftOrdinary.addAll(
                    sqlClient.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(
                                    table.fetch(BookFetcher.$.name()),
                                    table.store(JoinType.LEFT).fetch(BookStoreFetcher.$.name()),
                                    table.price()
                            )
                            .execute(con)
            ));
            assertEquals(1, leftOrdinary.size());
            assertNull(leftOrdinary.get(0).get_2());
            // An INNER join on the nullable FK must exclude the root in both paths.
            clearExecutions();
            List<Tuple3<Book, BookStore, BigDecimal>> innerHinted = new ArrayList<>();
            jdbc(con -> innerHinted.addAll(
                    sqlClient.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(
                                    table.fetch(BookFetcher.$.name()),
                                    table.store(JoinType.INNER).fetch(BookStoreFetcher.$.name()),
                                    table.price()
                            )
                            .useObjectCache()
                            .execute(con)
            ));
            assertTrue(innerHinted.isEmpty());
            List<Execution> innerExecutions = getExecutions();
            assertEquals(1, innerExecutions.size());
            assertTrue(
                    innerExecutions.get(0).getSql().contains("inner join BOOK_STORE"),
                    innerExecutions.get(0).getSql()
            );
            List<Tuple3<Book, BookStore, BigDecimal>> innerOrdinary = new ArrayList<>();
            jdbc(con -> innerOrdinary.addAll(
                    sqlClient.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(
                                    table.fetch(BookFetcher.$.name()),
                                    table.store(JoinType.INNER).fetch(BookStoreFetcher.$.name()),
                                    table.price()
                            )
                            .execute(con)
            ));
            assertTrue(innerOrdinary.isEmpty());
        } finally {
            jdbc(null, false, con -> {
                try (PreparedStatement st = con.prepareStatement("update BOOK set STORE_ID = ? where ID = ?")) {
                    st.setObject(1, oreillyId);
                    st.setObject(2, learningGraphQLId1);
                    st.executeUpdate();
                }
            });
        }
    }

    @Test
    public void testDuplicateRootRowsManyToManyParity() {
        BookTable table = BookTable.$;
        BookTableEx book = table.asTableEx();
        List<UUID> ids = Arrays.asList(learningGraphQLId1, learningGraphQLId2);
        jdbc(con -> sqlClient.getEntities().forConnection(con).findByIds(Book.class, ids));
        List<Tuple2<Book, String>> ordinary = new ArrayList<>();
        jdbc(con -> ordinary.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().in(ids))
                        .orderBy(table.id(), book.authors().firstName())
                        .select(table.fetch(BookFetcher.$.name()), book.authors().firstName())
                        .offset(1).limit(3)
                        .execute(con)
        ));
        clearExecutions();
        List<Tuple2<Book, String>> hinted = new ArrayList<>();
        jdbc(con -> hinted.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().in(ids))
                        .orderBy(table.id(), book.authors().firstName())
                        .select(table.fetch(BookFetcher.$.name()), book.authors().firstName())
                        .offset(1).limit(3)
                        .useObjectCache()
                        .execute(con)
        ));
        // Exact ordinary parity, including row count/order. A non-zero offset cuts
        // inside the duplicated many-to-many span, so some adjacent pair still
        // repeats the same root (do not assume it is the first pair).
        assertEquals(fingerprints(ordinary), fingerprints(hinted));
        assertEquals(3, hinted.size());
        boolean repeatedRoot = false;
        for (int i = 0; i + 1 < hinted.size(); i++) {
            if (hinted.get(i).get_1().id().equals(hinted.get(i + 1).get_1().id())) {
                repeatedRoot = true;
                break;
            }
        }
        assertTrue(repeatedRoot, "expected a duplicated root pair inside the page");
        List<Execution> executions = getExecutions();
        assertEquals(1, executions.size());
        assertFalse(executions.get(0).getSql().contains("tb_1_.NAME"), executions.get(0).getSql());
    }

    @Test
    public void testCaseWithAssociatedPredicateParity() {
        BookTable table = BookTable.$;
        jdbc(con -> sqlClient.getEntities().forConnection(con)
                .findByIds(Book.class, Collections.singletonList(learningGraphQLId1)));
        List<Tuple2<Book, String>> ordinary = new ArrayList<>();
        jdbc(con -> ordinary.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(table.fetch(BookFetcher.$.name()), alexCase(table))
                        .execute(con)
        ));
        clearExecutions();
        List<Tuple2<Book, String>> hinted = new ArrayList<>();
        jdbc(con -> hinted.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(table.fetch(BookFetcher.$.name()), alexCase(table))
                        .useObjectCache()
                        .execute(con)
        ));
        assertEquals("Learning GraphQL", hinted.get(0).get_1().name());
        assertEquals("HAS_ALEX", hinted.get(0).get_2());
        assertEquals(ordinary.get(0).get_2(), hinted.get(0).get_2());
        List<Execution> executions = getExecutions();
        assertFalse(executions.isEmpty());
        // Whether the hint applied or declined, the associated predicate inside the
        // CASE must stay filtered (never an unfiltered/constant expression).
        String executedSql = executions.get(executions.size() - 1).getSql();
        assertTrue(executedSql.contains("exists("), executedSql);
        assertTrue(executedSql.contains("FIRST_NAME = ?"), executedSql);
    }

    @Test
    public void testNamedEntityTupleRebuildWithCachedRoot() {
        BookTable table = BookTable.$;
        jdbc(con -> sqlClient.getEntities().forConnection(con)
                .findByIds(Book.class, Collections.singletonList(learningGraphQLId1)));
        clearExecutions();
        List<EntityTuple> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(
                                EntityTupleMapper
                                        .book(table.fetch(BookFetcher.$.name()))
                                        .authorCount(Expression.constant(1L))
                        )
                        .useObjectCache()
                        .execute(con)
        ));
        assertEquals(1, rows.size());
        assertEquals("Learning GraphQL", rows.get(0).getBook().name());
        assertEquals(1L, rows.get(0).getAuthorCount());
        List<Execution> executions = getExecutions();
        assertEquals(1, executions.size());
        assertFalse(executions.get(0).getSql().contains("tb_1_.NAME"), executions.get(0).getSql());
    }

    private static Expression<String> alexCase(BookTable table) {
        return Expression.string()
                .caseBuilder()
                .when(table.authors(author -> author.firstName().eq("Alex")), "HAS_ALEX")
                .otherwise("NO_ALEX");
    }

    private static List<String> fingerprints(List<Tuple2<Book, String>> rows) {
        List<String> result = new ArrayList<>(rows.size());
        for (Tuple2<Book, String> row : rows) {
            result.add(row.get_1().id() + "|" + row.get_1().name() + "|" + row.get_2());
        }
        return result;
    }

    @Test
    public void testFreshUncachedJoinedEntityKeepsColumns() {
        // Only the root type is cacheable; the joined target has no object cache and
        // must stay a fresh full selection inside the same mixed projection.
        JSqlClient client = createClient(type -> type.getJavaClass() == Book.class ? new MapCache<>(type) : null);
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Book.class, Collections.singletonList(learningGraphQLId1)));
        BookTable table = BookTable.$;
        clearExecutions();
        List<Tuple2<Book, BookStore>> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                client.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(
                                table.fetch(BookFetcher.$.name()),
                                table.store().fetch(BookStoreFetcher.$.name())
                        )
                        .useObjectCache()
                        .execute(con)
        ));
        assertEquals(1, rows.size());
        assertEquals("Learning GraphQL", rows.get(0).get_1().name());
        assertEquals("O'REILLY", rows.get(0).get_2().name());
        List<Execution> executions = getExecutions();
        assertEquals(1, executions.size());
        String sql = executions.get(0).getSql();
        // Root is cached (its name column is gone) while the uncached joined entity
        // keeps its join and full columns.
        assertFalse(sql.contains("tb_1_.NAME"), sql);
        assertTrue(sql.contains("BOOK_STORE"), sql);
        assertTrue(sql.contains("tb_2_.NAME"), sql);
    }

    @Test
    public void testBaseAssignableWrongConcreteTypeFallsBack() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        // id 100 is an Organization in the database, but the shared cache holds a
        // Person. Both are assignable to the declared Client cache, so only the
        // fresh concrete seed type can detect the disagreement.
        clientCache.put(100L, PersonDraft.$.produce(draft -> draft.setId(100L)));
        executeAndExpect(
                client.createQuery(table)
                        .where(table.id().in(CLIENT_IDS))
                        .select(table.fetch(ClientFetcher.$.name()))
                        .useObjectCache(),
                ctx -> {
                    ctx.sql(CLIENT_SKELETON_SQL).variables(100L, 101L);
                    ctx.statement(1).sql(CLIENT_ENTITY_SQL).variables(100L, 101L);
                    ctx.rows(rows -> {
                        assertEquals(2, rows.size());
                        assertInstanceOf(Organization.class, rows.get(0));
                        assertInstanceOf(Person.class, rows.get(1));
                    });
                }
        );
    }

    @Test
    public void testNegativeCacheHitFallsBackWholePage() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.delete(100L);
        clearExecutions();
        List<Client> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                client.createQuery(table)
                        .where(table.id().in(CLIENT_IDS))
                        .select(table.fetch(ClientFetcher.$.name()))
                        .useObjectCache()
                        .execute(con)
        ));
        // The deleted id is a negative cache hit. The whole page must still come back
        // (never a shortened page); whether the canonical single-id loader satisfies
        // it or the optimization declines and the original full-page SQL runs is not
        // asserted here, so the test does not pin the optimization's SQL budget.
        assertEquals(2, rows.size());
        assertInstanceOf(Organization.class, rows.get(0));
        assertInstanceOf(Person.class, rows.get(1));
        List<Execution> executions = getExecutions();
        assertFalse(executions.isEmpty());
        // The hint path was entered: the first statement is the id-only skeleton.
        assertEquals(CLIENT_SKELETON_SQL, executions.get(0).getSql());
        // The negative hit was observed through the canonical single-id loader.
        boolean loaderRan = false;
        for (Execution execution : executions) {
            if (CLIENT_CACHE_MISS_SQL.equals(execution.getSql())) {
                loaderRan = true;
                break;
            }
        }
        assertTrue(loaderRan, "expected the canonical cache-miss loader to run");
    }

    @Test
    public void testGenuineCacheFailurePropagates() {
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? new ThrowingCache(type) : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> assertThrows(
                IllegalStateException.class,
                () -> client.createQuery(table)
                        .where(table.id().in(CLIENT_IDS))
                        .select(table.fetch(ClientFetcher.$.name()))
                        .useObjectCache()
                        .execute(con)
        ));
    }

    @Test
    public void testFilteredWarmRootAndOwningToOneSkipSecondVisibilityQuery() {
        // A real root filter would otherwise be applied a second time by the per-id
        // visibility check of the object-cache read. The filtered skeleton already
        // establishes membership, so the hint must use exactly the unhinted statement
        // count while preserving the fresh scalar/join shape of the same page.
        JSqlClient client = getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setEntityManager(new EntityManager(Book.class, BookStore.class, Author.class, Country.class));
            builder.addFilters(new Filter<BookTable>() {
                @Override
                public void filter(FilterArgs<BookTable> args) {
                    args.where(args.getTable().id().eq(learningGraphQLId1));
                }
            });
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    Class<?> javaClass = type.getJavaClass();
                    return javaClass == Book.class || javaClass == BookStore.class ? new MapCache<>(type) : null;
                }
            }));
        });
        BookTable table = BookTable.$;
        jdbc(con -> {
            client.getEntities().forConnection(con)
                    .findByIds(Book.class, Collections.singletonList(learningGraphQLId1));
            client.getEntities().forConnection(con)
                    .findByIds(BookStore.class, Collections.singletonList(oreillyId));
        });
        // Unhinted baseline for the same filtered projection: one statement with the
        // filter inlined.
        clearExecutions();
        List<Tuple2<Book, BookStore>> ordinary = new ArrayList<>();
        jdbc(con -> ordinary.addAll(
                client.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(
                                table.fetch(BookFetcher.$.name()),
                                table.store().fetch(BookStoreFetcher.$.name())
                        )
                        .execute(con)
        ));
        int ordinaryCount = getExecutions().size();
        assertEquals(1, ordinary.size());
        assertEquals(1, ordinaryCount);
        // Hinted: both slots are warm, so the filtered id-only skeleton is the only
        // statement. A second visibility query would make the count 2.
        clearExecutions();
        List<Tuple2<Book, BookStore>> hinted = new ArrayList<>();
        jdbc(con -> hinted.addAll(
                client.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(
                                table.fetch(BookFetcher.$.name()),
                                table.store().fetch(BookStoreFetcher.$.name())
                        )
                        .useObjectCache()
                        .execute(con)
        ));
        assertEquals(ordinaryCount, getExecutions().size());
        assertEquals(1, hinted.size());
        assertEquals(ordinary.get(0).get_1().name(), hinted.get(0).get_1().name());
        assertEquals(ordinary.get(0).get_2().name(), hinted.get(0).get_2().name());
        String sql = getExecutions().get(0).getSql();
        // Root and joined target are both served from cache; the owning to-one join is
        // retained so membership/nullability are unchanged.
        assertTrue(sql.contains("BOOK_STORE"), sql);
        assertTrue(sql.contains("tb_2_.ID"), sql);
        assertFalse(sql.contains("tb_2_.NAME"), sql);
        assertFalse(sql.contains("tb_1_.NAME"), sql);
    }

    @Test
    public void testRawFindMapByIdsForQueryStillChecksVisibility() {
        // SECURITY REGRESSION: the public raw bridge takes arbitrary ids and expected
        // concrete types with no skeleton of any kind. It must never skip the
        // current-filter visibility check, so a forbidden id that is present in the
        // shared cache (here warmed through an allowed read) cannot leak.
        UUID[] visibleId = { learningGraphQLId1 };
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        JSqlClient client = getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setEntityManager(new EntityManager(Book.class, BookStore.class, Author.class, Country.class));
            builder.addFilters(new Filter<BookTable>() {
                @Override
                public void filter(FilterArgs<BookTable> args) {
                    args.where(args.getTable().id().eq(visibleId[0]));
                }
            });
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return type.getJavaClass() == Book.class ? bookCache : null;
                }
            }));
        });
        // Warm the shared cache while the id is visible through the filter.
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Book.class, Collections.singletonList(learningGraphQLId1)));
        // Hide the id, then call the raw bridge directly with the cached id.
        visibleId[0] = new UUID(0L, 0L);
        Map<Object, ImmutableType> expectedTypes = new LinkedHashMap<>();
        expectedTypes.put(learningGraphQLId1, ImmutableType.get(Book.class));
        jdbc(con -> {
            EntitiesImpl entities = (EntitiesImpl) client.getEntities().forConnection(con);
            Map<UUID, Book> result = entities.<UUID, Book>findMapByIdsForQuery(
                    ImmutableType.get(Book.class),
                    null,
                    ImmutableType.get(Book.class),
                    Collections.singletonList(learningGraphQLId1),
                    expectedTypes
            );
            assertTrue(result.isEmpty(), "the raw bridge must not bypass the current filter");
        });
    }

    @Test
    public void testHintedFilterSwapNeverServesHiddenWarmBody() {
        // Warming the body then swapping the current filter state: the filtered skeleton
        // remains the sole membership authority, so a now-hidden id is never served from
        // the warm positive body and the content cache is not consulted for it.
        UUID[] visible = { learningGraphQLId1 };
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        JSqlClient client = getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setEntityManager(new EntityManager(Book.class, BookStore.class, Author.class, Country.class));
            builder.addFilters(new Filter<BookTable>() {
                @Override
                public void filter(FilterArgs<BookTable> args) {
                    args.where(args.getTable().id().eq(visible[0]));
                }
            });
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return type.getJavaClass() == Book.class ? bookCache : null;
                }
            }));
        });
        BookTable table = BookTable.$;
        // Warm the positive body through an allowed read.
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Book.class, Collections.singletonList(learningGraphQLId1)));
        // Allowed hinted read: the filtered skeleton is the only statement and the cached body is served.
        clearExecutions();
        List<Book> allowed = hintedBooks(client, table);
        assertEquals(1, allowed.size());
        assertFalse(getExecutions().get(0).getSql().contains("tb_1_.NAME"), getExecutions().get(0).getSql());
        int afterAllowed = bookCache.getAllKeys.size();
        // Hide the row by swapping the filter state.
        visible[0] = new UUID(0L, 0L);
        clearExecutions();
        List<Book> hidden = hintedBooks(client, table);
        assertTrue(hidden.isEmpty(), "a hidden id must never be served from the warm body cache");
        assertEquals(afterAllowed, bookCache.getAllKeys.size(), "hidden membership must not consult the content cache");
        // Re-allow: the positive cache body is served from the primary SQL membership again.
        visible[0] = learningGraphQLId1;
        clearExecutions();
        List<Book> reAllowed = hintedBooks(client, table);
        assertEquals(1, reAllowed.size());
        assertEquals(learningGraphQLId1, reAllowed.get(0).id());
    }

    private List<Book> hintedBooks(JSqlClient client, BookTable table) {
        List<Book> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                client.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(table.fetch(BookFetcher.$.name()))
                        .useObjectCache()
                        .execute(con)
        ));
        return rows;
    }

    private JSqlClient createClient(Function<ImmutableType, Cache<?, ?>> objectCacheFactory) {
        return getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return objectCacheFactory.apply(type);
                }
            }));
        });
    }

    private static class MapCache<T> implements Cache<Object, T> {

        private final ImmutableType type;

        private final Map<Object, T> map = new LinkedHashMap<>();

        private final List<Collection<Object>> getAllKeys = new ArrayList<>();

        private MapCache(ImmutableType type) {
            this.type = type;
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
            getAllKeys.add(new ArrayList<>(keys));
            Map<Object, T> result = new LinkedHashMap<>();
            Set<Object> missedKeys = new LinkedHashSet<>();
            for (Object key : keys) {
                if (map.containsKey(key)) {
                    result.put(key, map.get(key));
                } else {
                    result.put(key, null);
                    missedKeys.add(key);
                }
            }
            if (!missedKeys.isEmpty()) {
                Map<Object, T> loaded = env.getLoader().loadAll(missedKeys);
                for (Object key : missedKeys) {
                    T value = loaded.get(key);
                    map.put(key, value);
                    result.put(key, value);
                }
            }
            return result;
        }

        void put(Object key, T value) {
            map.put(key, value);
        }

        @Override
        public void deleteAll(@NotNull Collection<Object> keys, @Nullable Object reason) {
            map.keySet().removeAll(keys);
        }
    }

    private static class ThrowingCache implements Cache<Object, Object> {

        private final ImmutableType type;

        private ThrowingCache(ImmutableType type) {
            this.type = type;
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
        public Map<Object, Object> getAll(
                @NotNull Collection<Object> keys,
                @NotNull CacheEnvironment<Object, Object> env
        ) {
            throw new IllegalStateException("intentional cache failure");
        }

        @Override
        public void deleteAll(@NotNull Collection<Object> keys, @Nullable Object reason) {
        }
    }

    /**
     * A transaction-agnostic test manager: the optional object-cache projection only
     * activates when the connection is positively known to be outside a transaction.
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
