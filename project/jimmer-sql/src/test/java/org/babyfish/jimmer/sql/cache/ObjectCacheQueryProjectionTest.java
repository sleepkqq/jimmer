package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.runtime.ImmutableSpi;
import org.babyfish.jimmer.sql.JoinType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.impl.EntitiesImpl;
import org.babyfish.jimmer.sql.ast.impl.query.FilterLevel;
import org.babyfish.jimmer.sql.ast.impl.query.Queries;
import org.babyfish.jimmer.sql.ast.impl.table.FetcherSelectionImpl;
import org.babyfish.jimmer.sql.ast.mutation.QueryReason;
import org.babyfish.jimmer.sql.ast.query.ConfigurableRootQuery;
import org.babyfish.jimmer.sql.ast.query.TypedRootQuery;
import org.babyfish.jimmer.sql.ast.table.Table;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.ast.tuple.Tuple3;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.common.CacheImpl;
import org.babyfish.jimmer.sql.common.NativeDatabases;
import org.babyfish.jimmer.sql.dialect.PostgresDialect;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType;
import org.babyfish.jimmer.sql.filter.Filter;
import org.babyfish.jimmer.sql.filter.FilterArgs;
import org.babyfish.jimmer.sql.model.Author;
import org.babyfish.jimmer.sql.model.AuthorFetcher;
import org.babyfish.jimmer.sql.model.AuthorTable;
import org.babyfish.jimmer.sql.model.Book;
import org.babyfish.jimmer.sql.model.BookFetcher;
import org.babyfish.jimmer.sql.model.BookStore;
import org.babyfish.jimmer.sql.model.BookStoreDraft;
import org.babyfish.jimmer.sql.model.BookStoreFetcher;
import org.babyfish.jimmer.sql.model.BookStoreProps;
import org.babyfish.jimmer.sql.model.BookStoreTable;
import org.babyfish.jimmer.sql.model.BookTable;
import org.babyfish.jimmer.sql.model.BookTableEx;
import org.babyfish.jimmer.sql.model.Country;
import org.babyfish.jimmer.sql.model.TreeNode;
import org.babyfish.jimmer.sql.model.TreeNodeFetcher;
import org.babyfish.jimmer.sql.model.TreeNodeTable;
import org.babyfish.jimmer.sql.model.dto.ReusableBookStoreView;
import org.babyfish.jimmer.sql.model.embedded.LocationDraft;
import org.babyfish.jimmer.sql.model.embedded.LocationFetcher;
import org.babyfish.jimmer.sql.model.embedded.Machine;
import org.babyfish.jimmer.sql.model.embedded.MachineDraft;
import org.babyfish.jimmer.sql.model.embedded.MachineFetcher;
import org.babyfish.jimmer.sql.model.embedded.MachineTable;
import org.babyfish.jimmer.sql.model.embedded.PointDraft;
import org.babyfish.jimmer.sql.model.embedded.PointFetcher;
import org.babyfish.jimmer.sql.model.embedded.RectDraft;
import org.babyfish.jimmer.sql.model.embedded.RectFetcher;
import org.babyfish.jimmer.sql.model.embedded.Transform;
import org.babyfish.jimmer.sql.model.embedded.TransformDraft;
import org.babyfish.jimmer.sql.model.embedded.TransformFetcher;
import org.babyfish.jimmer.sql.model.embedded.TransformTable;
import org.babyfish.jimmer.sql.model.fetcher.Issue1434Message;
import org.babyfish.jimmer.sql.model.fetcher.Issue1434MessageFetcher;
import org.babyfish.jimmer.sql.model.fetcher.Issue1434MessageTable;
import org.babyfish.jimmer.sql.model.fetcher.Issue1434User;
import org.babyfish.jimmer.sql.model.fetcher.Issue1434UserFetcher;
import org.babyfish.jimmer.sql.model.inheritance.AdministratorFetcher;
import org.babyfish.jimmer.sql.model.inheritance.AdministratorTable;
import org.babyfish.jimmer.sql.model.inheritance.single.employee.Department;
import org.babyfish.jimmer.sql.model.inheritance.single.employee.DepartmentFetcher;
import org.babyfish.jimmer.sql.model.inheritance.single.employee.Employee;
import org.babyfish.jimmer.sql.model.inheritance.single.employee.EmployeeFetcher;
import org.babyfish.jimmer.sql.model.inheritance.single.employee.EmployeeTable;
import org.babyfish.jimmer.sql.model.inheritance.single.employee.FullTimeEmployee;
import org.babyfish.jimmer.sql.model.inheritance.single.employee.FullTimeEmployeeFetcher;
import org.babyfish.jimmer.sql.model.inheritance.single.employee.PartTimeEmployee;
import org.babyfish.jimmer.sql.model.inheritance.singletable.Client;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientFetcher;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientProject;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientProjectFetcher;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientProjectTable;
import org.babyfish.jimmer.sql.model.inheritance.singletable.ClientTable;
import org.babyfish.jimmer.sql.model.inheritance.singletable.Organization;
import org.babyfish.jimmer.sql.model.inheritance.singletable.OrganizationDraft;
import org.babyfish.jimmer.sql.model.inheritance.singletable.OrganizationFetcher;
import org.babyfish.jimmer.sql.model.inheritance.singletable.OrganizationProject;
import org.babyfish.jimmer.sql.model.inheritance.singletable.OrganizationProjectFetcher;
import org.babyfish.jimmer.sql.model.inheritance.singletable.OrganizationProjectTable;
import org.babyfish.jimmer.sql.model.inheritance.singletable.Person;
import org.babyfish.jimmer.sql.model.inheritance.singletable.PersonDraft;
import org.babyfish.jimmer.sql.model.inheritance.singletable.PersonFetcher;
import org.babyfish.jimmer.sql.model.inheritance.singletable.dto.ClientDefaultView;
import org.babyfish.jimmer.sql.model.inheritance.singletable.dto.ClientImplicitCatchAllView;
import org.babyfish.jimmer.sql.model.issue1252.TreeNode2;
import org.babyfish.jimmer.sql.model.issue1252.TreeNode2Fetcher;
import org.babyfish.jimmer.sql.model.issue1252.TreeNode2Table;
import org.babyfish.jimmer.sql.model.ld.PostFetcher;
import org.babyfish.jimmer.sql.model.ld.PostTable;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.EntityManager;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.babyfish.jimmer.sql.tuple.EntityTuple;
import org.babyfish.jimmer.sql.tuple.EntityTupleMapper;
import org.babyfish.jimmer.support.ProxyRecorder;
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

import javax.sql.DataSource;

import static org.babyfish.jimmer.sql.common.Constants.alexId;
import static org.babyfish.jimmer.sql.common.Constants.graphQLInActionId1;
import static org.babyfish.jimmer.sql.common.Constants.learningGraphQLId1;
import static org.babyfish.jimmer.sql.common.Constants.learningGraphQLId2;
import static org.babyfish.jimmer.sql.common.Constants.manningId;
import static org.babyfish.jimmer.sql.common.Constants.oreillyId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final AtomicInteger CLIENT_CONVERSIONS = new AtomicInteger();

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
    public void testContentOnlyBasePolicyCoversSubtypeAndSelectAssociationReads() throws Exception {
        MapCache<Client> baseCache = new MapCache<>(ImmutableType.get(Client.class));
        MapCache<Organization> organizationCache = new MapCache<>(ImmutableType.get(Organization.class));
        ImmutableProp organizationProp = ImmutableType.get(OrganizationProject.class).getProp("organization");
        Cache<Object, Object> edgeCache = new CacheImpl<>(organizationProp);
        JSqlClient client = createClient(type -> {
            if (type.getJavaClass() == Client.class) {
                return baseCache;
            }
            return type.getJavaClass() == Organization.class ? organizationCache : null;
        }, ImmutableType.get(Client.class), organizationProp, edgeCache);
        assertTrue(client.getCaches().isObjectCacheContentOnly(ImmutableType.get(Organization.class)));

        Organization stale = OrganizationDraft.$.produce(draft -> {
            draft.setId(100L);
            draft.setName("STALE");
            draft.setTaxCode("STALE");
        });
        baseCache.put(100L, stale);
        organizationCache.put(100L, stale);
        jdbc(con -> edgeCache.getAll(
                Collections.<Object>singletonList(1001L),
                new CacheEnvironment<>(
                        client,
                        con,
                        keys -> Collections.<Object, Object>singletonMap(1001L, 9999L),
                        false
                )
        ));
        Organization byId = client.getEntities().findById(Organization.class, 100L);
        assertEquals("Acme", byId.name());
        assertEquals("ACME-001", byId.taxCode());
        ClientImplicitCatchAllView dto = client.getEntities().findById(ClientImplicitCatchAllView.class, 100L);
        assertEquals("ACME-001", assertInstanceOf(ClientImplicitCatchAllView.Organization.class, dto).getTaxCode());

        OrganizationProjectTable table = OrganizationProjectTable.$;
        List<OrganizationProject> projects = new ArrayList<>();
        jdbc(con -> projects.addAll(client.createQuery(table)
                .where(table.id().eq(1001L))
                .select(table.fetch(OrganizationProjectFetcher.$.organization(
                        ReferenceFetchType.SELECT,
                        OrganizationFetcher.$.name().taxCode()
                )))
                .execute(con)));
        assertEquals(1, projects.size());
        assertEquals("Acme", projects.get(0).organization().name());
        assertEquals("ACME-001", projects.get(0).organization().taxCode());

        baseCache.delete(100L);
        organizationCache.delete(100L);
        edgeCache.deleteAll(Collections.<Object>singletonList(1001L), null);
        jdbc(con -> {
            boolean autoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            boolean rolledBack = false;
            try {
                try (PreparedStatement statement = con.prepareStatement(
                        "update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?"
                )) {
                    statement.setString(1, "PENDING");
                    statement.setString(2, "PENDING-TAX");
                    statement.setLong(3, 100L);
                    assertEquals(1, statement.executeUpdate());
                }
                List<OrganizationProject> pending = client.createQuery(table)
                        .where(table.id().eq(1001L))
                        .select(table.fetch(OrganizationProjectFetcher.$.organization(
                                ReferenceFetchType.SELECT,
                                OrganizationFetcher.$.name().taxCode()
                        )))
                        .execute(con);
                assertEquals("PENDING", pending.get(0).organization().name());
                assertEquals("PENDING-TAX", pending.get(0).organization().taxCode());
                con.rollback();
                rolledBack = true;
            } finally {
                if (!rolledBack) {
                    con.rollback();
                }
                con.setAutoCommit(autoCommit);
            }
        });
        Organization afterRollback = client.getEntities().findById(Organization.class, 100L);
        assertEquals("Acme", afterRollback.name());
        assertEquals("ACME-001", afterRollback.taxCode());
        assertTrue(baseCache.map.isEmpty());
        assertTrue(organizationCache.map.isEmpty());
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

    private static List<UUID> idsOf(List<BookStore> rows) {
        List<UUID> ids = new ArrayList<>(rows.size());
        for (BookStore row : rows) {
            ids.add(row.id());
        }
        return ids;
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

    @Test
    public void testContentFetcherStaleApprovedLeafKeepsUnapprovedPropsFresh() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookStoreTable table = BookStoreTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        try {
            rawUpdate(
                    "update BOOK_STORE set NAME = ?, WEBSITE = ?, VERSION = ? where ID = ?",
                    "STALE-DB-NAME", "STALE-DB-WEBSITE", 7, oreillyId
            );
            clearExecutions();
            List<BookStore> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(oreillyId))
                            .select(table.fetch(BookStoreFetcher.$.name().website().version()))
                            .useObjectCache(BookStoreFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("O'REILLY", rows.get(0).name());
            assertEquals("STALE-DB-WEBSITE", rows.get(0).website());
            assertEquals(7, rows.get(0).version());
            assertCacheTouched(storeCache, oreillyId);

            clearExecutions();
            List<ReusableBookStoreView> views = new ArrayList<>();
            jdbc(con -> views.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(oreillyId))
                            .select(table.fetch(ReusableBookStoreView.class))
                            .useObjectCache(BookStoreFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(1, views.size());
            assertEquals(oreillyId, views.get(0).getId());
            assertEquals("O'REILLY", views.get(0).getName());
        } finally {
            rawUpdate(
                    "update BOOK_STORE set NAME = 'O''REILLY', WEBSITE = null, VERSION = 0 where ID = ?",
                    oreillyId
            );
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testContentFetcherChildReferenceCacheHitWithFreshEdge() {
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> {
            Class<?> javaClass = type.getJavaClass();
            if (javaClass == Book.class) {
                return bookCache;
            }
            if (javaClass == BookStore.class) {
                return storeCache;
            }
            return null;
        });
        BookTable table = BookTable.$;
        jdbc(con -> {
            client.getEntities().forConnection(con)
                    .findByIds(Book.class, Collections.singletonList(learningGraphQLId1));
            client.getEntities().forConnection(con)
                    .findByIds(BookStore.class, Collections.singletonList(oreillyId));
        });
        try {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "STALE-DB-NAME", oreillyId);
            clearExecutions();
            Book book = hintedBook(client, table);
            assertEquals(learningGraphQLId1, book.id());
            assertEquals("Learning GraphQL", book.name());
            assertEquals(oreillyId, book.store().id());
            assertEquals("O'REILLY", book.store().name());
            assertCacheTouched(storeCache, oreillyId);

            rawUpdate("update BOOK set STORE_ID = ? where ID = ?", manningId, learningGraphQLId1);
            clearExecutions();
            Book moved = hintedBook(client, table);
            assertEquals(manningId, moved.store().id());
            assertEquals("MANNING", moved.store().name());

            rawUpdate("update BOOK set STORE_ID = null where ID = ?", learningGraphQLId1);
            clearExecutions();
            Book cleared = hintedBook(client, table);
            assertNull(cleared.store(), "a cleared FK must yield a null target");
        } finally {
            rawUpdate("update BOOK set STORE_ID = ? where ID = ?", oreillyId, learningGraphQLId1);
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            storeCache.delete(oreillyId);
            bookCache.delete(learningGraphQLId1);
        }
    }

    @Test
    public void testContentFetcherChildCacheWithoutRootCache() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookTable table = BookTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        try {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "STALE-DB-NAME", oreillyId);
            clearExecutions();
            Book book = hintedBook(client, table);
            assertEquals(learningGraphQLId1, book.id());
            assertEquals("Learning GraphQL", book.name());
            assertEquals(oreillyId, book.store().id());
            assertEquals("O'REILLY", book.store().name());
            assertCacheTouched(storeCache, oreillyId);
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testContentFetcherFilteredChildAdmissionStaysFresh() {
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setEntityManager(new EntityManager(Book.class, BookStore.class, Author.class, Country.class));
            builder.addFilters(new Filter<BookStoreProps>() {
                @Override
                public void filter(FilterArgs<BookStoreProps> args) {
                    args.where(args.getTable().id().eq(oreillyId));
                }
            });
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    Class<?> javaClass = type.getJavaClass();
                    if (javaClass == Book.class) {
                        return bookCache;
                    }
                    if (javaClass == BookStore.class) {
                        return storeCache;
                    }
                    return null;
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
        try {
            rawUpdate("update BOOK set STORE_ID = ? where ID = ?", manningId, learningGraphQLId1);
            clearExecutions();
            Book book = hintedBook(client, table);
            assertNull(book.store(), "a filtered-out target must not be served from the object cache");
        } finally {
            rawUpdate("update BOOK set STORE_ID = ? where ID = ?", oreillyId, learningGraphQLId1);
            storeCache.delete(oreillyId);
            bookCache.delete(learningGraphQLId1);
        }
    }

    @Test
    public void testContentFetcherStaleApprovedScalarLeaf() {
        MapCache<Author> authorCache = new MapCache<>(ImmutableType.get(Author.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Author.class ? authorCache : null);
        AuthorTable table = AuthorTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Author.class, Collections.singletonList(alexId)));
        authorCache.clearHistory();
        try {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Zed", "Zulu", alexId
            );
            clearExecutions();
            List<Author> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(alexId))
                            .select(table.fetch(AuthorFetcher.$.firstName()))
                            .useObjectCache(AuthorFetcher.$.firstName())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("Alex", rows.get(0).firstName());
            assertCacheTouched(authorCache, alexId);
        } finally {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Alex", "Banks", alexId
            );
            authorCache.delete(alexId);
        }
    }

    @Test
    public void testContentFetcherUnwhitelistedFormulaOverlapStaysFresh() {
        MapCache<Author> authorCache = new MapCache<>(ImmutableType.get(Author.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Author.class ? authorCache : null);
        AuthorTable table = AuthorTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Author.class, Collections.singletonList(alexId)));
        authorCache.clearHistory();
        try {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Zed", "Zulu", alexId
            );
            clearExecutions();
            List<Author> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(alexId))
                            .select(table.fetch(AuthorFetcher.$.firstName().fullName2()))
                            .useObjectCache(AuthorFetcher.$.firstName())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("Zed Zulu", rows.get(0).fullName2());
            assertEquals("Alex", rows.get(0).firstName());
        } finally {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Alex", "Banks", alexId
            );
            authorCache.delete(alexId);
        }
    }

    @Test
    public void testContentFetcherApprovedJvmFormulaExpandsCachedDependencies() {
        MapCache<Author> authorCache = new MapCache<>(ImmutableType.get(Author.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Author.class ? authorCache : null);
        AuthorTable table = AuthorTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Author.class, Collections.singletonList(alexId)));
        authorCache.clearHistory();
        try {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Zed", "Zulu", alexId
            );
            clearExecutions();
            List<Author> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(alexId))
                            .select(table.fetch(AuthorFetcher.$.fullName()))
                            .useObjectCache(AuthorFetcher.$.fullName())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("Alex Banks", rows.get(0).fullName());
            ImmutableSpi spi = assertInstanceOf(ImmutableSpi.class, rows.get(0));
            assertTrue(spi.__isVisible("fullName"), "the approved formula must stay visible");
            assertTrue(spi.__isLoaded("firstName"), "the implicit dependency must still be loaded");
            assertFalse(spi.__isVisible("firstName"), "an implicit formula dependency must stay hidden");
            assertFalse(spi.__isVisible("lastName"), "an implicit formula dependency must stay hidden");
            assertCacheTouched(authorCache, alexId);
        } finally {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Alex", "Banks", alexId
            );
            authorCache.delete(alexId);
        }
    }

    @Test
    public void testContentFetcherUnapprovedJvmFormulaDependenciesStayFresh() {
        MapCache<Author> authorCache = new MapCache<>(ImmutableType.get(Author.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Author.class ? authorCache : null);
        AuthorTable table = AuthorTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Author.class, Collections.singletonList(alexId)));
        try {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Zed", "Zulu", alexId
            );
            clearExecutions();
            List<Author> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(alexId))
                            .select(table.fetch(AuthorFetcher.$.firstName().fullName()))
                            .useObjectCache(AuthorFetcher.$.firstName())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("Zed Zulu", rows.get(0).fullName());
            assertEquals("Zed", rows.get(0).firstName());
        } finally {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Alex", "Banks", alexId
            );
            authorCache.delete(alexId);
        }
    }

    @Test
    public void testContentFetcherApprovedJvmFormulaAcceptsEmbeddedDependencyClosure() {
        MapCache<Machine> machineCache = new MapCache<>(ImmutableType.get(Machine.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Machine.class ? machineCache : null);
        MachineTable table = MachineTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Machine.class, Collections.singletonList(1L)));
        machineCache.clearHistory();
        try {
            rawUpdate(
                    "update MACHINE set HOST = ?, PORT = ?, SECONDARY_HOST = ?, SECONDARY_PORT = ? where ID = ?",
                    "fresh-host", 9090, "fresh-secondary-host", 7070, 1L
            );
            clearExecutions();
            List<Machine> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(MachineFetcher.$.hosts().location(LocationFetcher.$.port())))
                            .useObjectCache(MachineFetcher.$.hosts())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            Machine machine = rows.get(0);
            assertEquals(1L, machine.id());
            assertEquals(Arrays.asList("localhost", "fresh-secondary-host"), machine.hosts());
            assertEquals(
                    "localhost",
                    machine.location().host(),
                    "an approved embedded closure leaf is served from the cache"
            );
            assertEquals(
                    9090,
                    machine.location().port().intValue(),
                    "an unapproved embedded sibling stays fresh SQL"
            );
            assertNotNull(
                    machine.secondaryLocation(),
                    "a currently present nullable embedded must not be fabricated absent"
            );
            assertEquals(
                    "fresh-secondary-host",
                    machine.secondaryLocation().host(),
                    "a nullable embedded dependency stays fresh SQL"
            );
            ImmutableSpi machineSpi = assertInstanceOf(ImmutableSpi.class, machine);
            assertTrue(machineSpi.__isVisible("hosts"), "the approved formula must stay visible");
            assertTrue(machineSpi.__isVisible("location"), "an explicitly selected dependency must stay visible");
            assertFalse(machineSpi.__isVisible("secondaryLocation"), "an implicit formula dependency stays hidden");
            ImmutableSpi locationSpi = assertInstanceOf(ImmutableSpi.class, machine.location());
            assertFalse(locationSpi.__isVisible("host"), "an implicit formula dependency stays hidden");
            assertTrue(locationSpi.__isVisible("port"), "an explicitly selected sibling stays visible");
            assertCacheTouched(machineCache, 1L);
        } finally {
            rawUpdate(
                    "update MACHINE set HOST = ?, PORT = ?, SECONDARY_HOST = null, SECONDARY_PORT = null where ID = ?",
                    "localhost", 8080, 1L
            );
            machineCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherApprovedJvmFormulaOnlyKeepsImplicitEmbeddedClosure() {
        MapCache<Machine> machineCache = new MapCache<>(ImmutableType.get(Machine.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Machine.class ? machineCache : null);
        MachineTable table = MachineTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Machine.class, Collections.singletonList(1L)));
        machineCache.clearHistory();
        JSqlClient oracle = createClient(type -> null);
        try {
            rawUpdate(
                    "update MACHINE set HOST = ?, PORT = ?, SECONDARY_HOST = ?, SECONDARY_PORT = ? where ID = ?",
                    "fresh-host", 9090, "fresh-secondary-host", 7070, 1L
            );
            List<Machine> fresh = new ArrayList<>();
            jdbc(con -> fresh.addAll(
                    oracle.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(MachineFetcher.$.hosts()))
                            .execute(con)
            ));
            assertEquals(1, fresh.size());
            assertEquals(Arrays.asList("fresh-host", "fresh-secondary-host"), fresh.get(0).hosts());
            assertNotNull(fresh.get(0).secondaryLocation());
            clearExecutions();
            List<Machine> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(MachineFetcher.$.hosts()))
                            .useObjectCache(MachineFetcher.$.hosts())
                            .execute(con)
            ));
            assertEquals(1, rows.size(), "the ordinary root membership must be preserved");
            Machine machine = rows.get(0);
            assertEquals(1L, machine.id());
            assertNotNull(machine.location(), "the implicit required container must be loaded");
            assertNotNull(machine.secondaryLocation(), "fresh presence must not be fabricated absent");
            assertEquals("fresh-secondary-host", machine.secondaryLocation().host());
            List<String> hosts = machine.hosts();
            assertEquals(2, hosts.size());
            assertEquals("fresh-secondary-host", hosts.get(1));
            assertTrue(
                    "fresh-host".equals(hosts.get(0)) || "localhost".equals(hosts.get(0)),
                    "the approved closure leaf must be a legitimate current or cached value: " + hosts
            );
            ImmutableSpi machineSpi = assertInstanceOf(ImmutableSpi.class, machine);
            assertTrue(machineSpi.__isVisible("hosts"), "the approved formula must stay visible");
            assertFalse(machineSpi.__isVisible("location"), "an implicit dependency container stays hidden");
            assertFalse(machineSpi.__isVisible("secondaryLocation"), "an implicit dependency container stays hidden");
            ImmutableSpi locationSpi = assertInstanceOf(ImmutableSpi.class, machine.location());
            assertFalse(locationSpi.__isVisible("host"), "an implicit dependency leaf stays hidden");
        } finally {
            rawUpdate(
                    "update MACHINE set HOST = ?, PORT = ?, SECONDARY_HOST = null, SECONDARY_PORT = null where ID = ?",
                    "localhost", 8080, 1L
            );
            machineCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherFieldLocalReferenceFilterAdmissionKeepsRootMembershipFresh() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookTable table = BookTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Arrays.asList(oreillyId, manningId)));
        storeCache.clearHistory();
        JSqlClient oracle = createClient(type -> null);
        Fetcher<Book> content = BookFetcher.$.store(
                BookStoreFetcher.$.name(),
                cfg -> cfg.fetchType(ReferenceFetchType.SELECT)
                        .filter(args -> args.where(args.getTable().id().eq(manningId)))
        );
        try {
            List<Book> fresh = new ArrayList<>();
            jdbc(con -> fresh.addAll(
                    oracle.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(table.fetch(content))
                            .execute(con)
            ));
            assertEquals(1, fresh.size());
            assertNull(fresh.get(0).store(), "the local filter excludes the FK target");
            clearExecutions();
            List<Book> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(table.fetch(content))
                            .useObjectCache(BookFetcher.$.store(BookStoreFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, rows.size(), "the root membership and full page must be preserved");
            assertEquals(learningGraphQLId1, rows.get(0).id());
            assertNull(rows.get(0).store(), "a warm target must not be admitted past the local field filter");
            List<Book> allowed = new ArrayList<>();
            jdbc(con -> allowed.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(graphQLInActionId1))
                            .select(table.fetch(content))
                            .useObjectCache(BookFetcher.$.store(BookStoreFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, allowed.size(), "the control membership must be preserved");
            assertNotNull(allowed.get(0).store(), "the field filter admits the matching target");
        } finally {
            storeCache.delete(oreillyId);
            storeCache.delete(manningId);
        }
    }

    @Test
    public void testContentFetcherEmbeddedLeafMergesSiblingSelectively() {
        MapCache<Machine> machineCache = new MapCache<>(ImmutableType.get(Machine.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Machine.class ? machineCache : null);
        MachineTable table = MachineTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Machine.class, Collections.singletonList(1L)));
        machineCache.clearHistory();
        try {
            rawUpdate("update MACHINE set HOST = ?, PORT = ? where ID = ?", "stale-host", 9090, 1L);
            clearExecutions();
            List<Machine> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(
                                    MachineFetcher.$.location(LocationFetcher.$.host().port())
                            ))
                            .useObjectCache(
                                    MachineFetcher.$.location(LocationFetcher.$.host())
                            )
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("localhost", rows.get(0).location().host());
            assertEquals(9090, rows.get(0).location().port().intValue());
            assertCacheTouched(machineCache, 1L);
        } finally {
            rawUpdate("update MACHINE set HOST = ?, PORT = ? where ID = ?", "localhost", 8080, 1L);
            machineCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherPathSpecificWhitelistsForSameType() {
        MapCache<TreeNode2> nodeCache = new MapCache<>(ImmutableType.get(TreeNode2.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == TreeNode2.class ? nodeCache : null);
        TreeNode2Table table = TreeNode2Table.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(TreeNode2.class, Arrays.asList(2L, 1L)));
        try {
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "STALE-ROOT", 2L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "STALE-PARENT", 1L);

            clearExecutions();
            TreeNode2 rootOnly = hintedNode(client, table, TreeNode2Fetcher.$.name());
            assertEquals("Food", rootOnly.name());
            assertEquals("STALE-PARENT", rootOnly.parent().name());

            clearExecutions();
            TreeNode2 parentOnly = hintedNode(
                    client, table, TreeNode2Fetcher.$.parent(TreeNode2Fetcher.$.name())
            );
            assertEquals("STALE-ROOT", parentOnly.name());
            assertEquals("Home", parentOnly.parent().name());
            assertCacheTouched(nodeCache, 1L);
        } finally {
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Food", 2L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Home", 1L);
            nodeCache.delete(2L);
            nodeCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherNegativeHitFallsBackWholeQueryOnce() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookStoreTable table = BookStoreTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
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
                        .where(table.id().eq(oreillyId))
                        .select(new FetcherSelectionImpl<BookStore>(
                                table,
                                BookStoreFetcher.$.name(),
                                converter
                        ))
                        .useObjectCache(BookStoreFetcher.$.name())
                        .execute(con)
        ));
        assertEquals(1, rows.size());
        assertEquals("O'REILLY", rows.get(0).name());
        assertEquals(1, STORE_CONVERSIONS.get());
        storeCache.delete(oreillyId);
    }

    @Test
    public void testContentFetcherPolymorphicWarmConcreteTypeStaysOptimized() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ? where ID = ?", "STALE-100", 100L);
            clearExecutions();
            List<Client> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .select(table.fetch(ClientFetcher.$.name()))
                            .useObjectCache(ClientFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            Organization organization = assertInstanceOf(Organization.class, rows.get(0));
            assertEquals("Acme", organization.name());
            assertInstanceOf(Person.class, rows.get(1));
            assertCacheTouched(clientCache, 100L);
        } finally {
            rawUpdate("update CLIENT set NAME = ? where ID = ?", "Acme", 100L);
            clientCache.delete(100L);
        }
    }

    @Test
    public void testContentFetcherWrongConcreteTypeFallsBack() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.put(100L, PersonDraft.$.produce(draft -> draft.setId(100L)));
        clientCache.clearHistory();
        clearExecutions();
        List<Client> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                client.createQuery(table)
                        .where(table.id().in(CLIENT_IDS))
                        .select(table.fetch(ClientFetcher.$.name()))
                        .useObjectCache(ClientFetcher.$.name())
                        .execute(con)
        ));
        assertEquals(2, rows.size());
        assertInstanceOf(Organization.class, rows.get(0));
        assertInstanceOf(Person.class, rows.get(1));
        assertCacheTouched(clientCache, 100L);
    }

    @Test
    public void testContentFetcherBranchMaskServesApprovedSubtypeLeaf() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        Fetcher<Client> projection =
                ClientFetcher.$
                        .name()
                        .forType(OrganizationFetcher.$.taxCode())
                        .forType(PersonFetcher.$.firstName().lastName());
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?", "STALE-100", "STALE-TAX-100", 100L);
            rawUpdate(
                    "update CLIENT set NAME = ?, FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "STALE-101", "STALE-FIRST", "STALE-LAST", 101L
            );
            clearExecutions();
            List<Client> branchOnly = new ArrayList<>();
            jdbc(con -> branchOnly.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(projection))
                            .useObjectCache(ClientFetcher.$.forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, branchOnly.size());
            Organization branchOrganization = assertInstanceOf(Organization.class, branchOnly.get(0));
            assertEquals("ACME-001", branchOrganization.taxCode());
            assertEquals("STALE-100", branchOrganization.name());
            Person branchPerson = assertInstanceOf(Person.class, branchOnly.get(1));
            assertEquals("STALE-101", branchPerson.name());
            assertEquals("STALE-FIRST", branchPerson.firstName());
            assertEquals("STALE-LAST", branchPerson.lastName());
            assertCacheTouched(clientCache, 100L);

            clearExecutions();
            List<Client> mixed = new ArrayList<>();
            jdbc(con -> mixed.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(projection))
                            .useObjectCache(
                                    ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode())
                            )
                            .execute(con)
            ));
            assertEquals(2, mixed.size());
            Organization mixedOrganization = assertInstanceOf(Organization.class, mixed.get(0));
            assertEquals("Acme", mixedOrganization.name());
            assertEquals("ACME-001", mixedOrganization.taxCode());
            Person mixedPerson = assertInstanceOf(Person.class, mixed.get(1));
            assertEquals("Bob", mixedPerson.name());
            assertEquals("STALE-FIRST", mixedPerson.firstName());
            assertCacheTouched(clientCache, 100L);
        } finally {
            rawUpdate(
                    "update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001', FIRST_NAME = null, LAST_NAME = null where ID = ?",
                    100L
            );
            rawUpdate(
                    "update CLIENT set NAME = 'Bob', FIRST_NAME = 'Bob', LAST_NAME = 'Brown' where ID = ?",
                    101L
            );
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherOnlyLoadsApplicableMissingBranchLeaves() {
        MapCache<Organization> organizationCache = new MapCache<>(ImmutableType.get(Organization.class));
        JSqlClient client = createClient(type ->
                type.getJavaClass() == Organization.class ? organizationCache : null
        );
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Organization.class, Collections.singletonList(100L)));
        organizationCache.clearHistory();
        Fetcher<Client> projection =
                ClientFetcher.$
                        .name()
                        .forType(OrganizationFetcher.$.taxCode())
                        .forType(PersonFetcher.$.firstName().lastName());
        try {
            rawUpdate("update CLIENT set TAX_CODE = ? where ID = ?", "DB-TAX-100", 100L);
            rawUpdate(
                    "update CLIENT set NAME = ?, FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "DB-PERSON", "Fresh", "Person", 101L
            );
            clearExecutions();
            List<Client> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(projection))
                            .useObjectCache(ClientFetcher.$.forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            Organization organization = assertInstanceOf(Organization.class, rows.get(0));
            assertEquals(100L, organization.id());
            assertEquals("ACME-001", organization.taxCode());
            Person person = assertInstanceOf(Person.class, rows.get(1));
            assertEquals(101L, person.id());
            assertEquals("DB-PERSON", person.name());
            assertEquals("Fresh", person.firstName());
            assertEquals("Person", person.lastName());
            assertEquals(1, getExecutions().size(), "the mixed page must not rerun the original query");

            clearExecutions();
            List<Tuple2<ClientImplicitCatchAllView, String>> dtoRows = new ArrayList<>();
            jdbc(con -> dtoRows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(ClientImplicitCatchAllView.class), table.type())
                            .useObjectCache(ClientFetcher.$.forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, dtoRows.size());
            assertEquals("ORG", dtoRows.get(0).get_2());
            ClientImplicitCatchAllView.Organization organizationDto =
                    assertInstanceOf(ClientImplicitCatchAllView.Organization.class, dtoRows.get(0).get_1());
            assertEquals(100L, organizationDto.getId());
            assertEquals("ACME-001", organizationDto.getTaxCode());
            assertEquals("Person", dtoRows.get(1).get_2());
            ClientImplicitCatchAllView.Default personDto =
                    assertInstanceOf(ClientImplicitCatchAllView.Default.class, dtoRows.get(1).get_1());
            assertEquals(101L, personDto.getId());
            assertEquals("DB-PERSON", personDto.getName());
            assertEquals(1, getExecutions().size(), "native DTO shaping must retain the one-query path");
        } finally {
            rawUpdate("update CLIENT set TAX_CODE = 'ACME-001' where ID = ?", 100L);
            rawUpdate(
                    "update CLIENT set NAME = 'Bob', FIRST_NAME = 'Bob', LAST_NAME = 'Brown' where ID = ?",
                    101L
            );
            organizationCache.delete(100L);
        }
    }

    @Test
    public void testContentFetcherBranchMaskDtoAndTupleParity() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?", "STALE-100", "STALE-TAX-100", 100L);
            rawUpdate("update CLIENT set NAME = ? where ID = ?", "STALE-101", 101L);
            clearExecutions();
            List<Tuple2<ClientImplicitCatchAllView, String>> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(
                                    table.fetch(ClientImplicitCatchAllView.class),
                                    table.type()
                            )
                            .useObjectCache(ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            assertEquals("ORG", rows.get(0).get_2());
            assertEquals("Person", rows.get(1).get_2());
            ClientImplicitCatchAllView.Organization organization =
                    assertInstanceOf(ClientImplicitCatchAllView.Organization.class, rows.get(0).get_1());
            assertEquals(100L, organization.getId());
            assertEquals("Acme", organization.getName());
            assertEquals("ACME-001", organization.getTaxCode());
            ClientImplicitCatchAllView.Default person =
                    assertInstanceOf(ClientImplicitCatchAllView.Default.class, rows.get(1).get_1());
            assertEquals(101L, person.getId());
            assertEquals("Bob", person.getName());

            clearExecutions();
            Function<Client, Client> converter = value -> {
                CLIENT_CONVERSIONS.incrementAndGet();
                return value;
            };
            CLIENT_CONVERSIONS.set(0);
            List<Client> converted = new ArrayList<>();
            jdbc(con -> converted.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(new FetcherSelectionImpl<Client>(
                                    table,
                                    ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode()),
                                    converter
                            ))
                            .useObjectCache(ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, converted.size());
            assertEquals(2, CLIENT_CONVERSIONS.get());
        } finally {
            rawUpdate("update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001' where ID = ?", 100L);
            rawUpdate("update CLIENT set NAME = 'Bob' where ID = ?", 101L);
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherBranchWrongConcreteTypeAndSubtypeChangeFallBack() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        Fetcher<Client> projection =
                ClientFetcher.$
                        .name()
                        .forType(OrganizationFetcher.$.taxCode())
                        .forType(PersonFetcher.$.firstName().lastName());
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            // The DB row is an Organization; the cache holds a Person.
            clientCache.put(100L, PersonDraft.$.produce(draft -> draft.setId(100L)));
            clearExecutions();
            List<Client> wrong = new ArrayList<>();
            jdbc(con -> wrong.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(projection))
                            .useObjectCache(ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, wrong.size());
            Organization organization = assertInstanceOf(Organization.class, wrong.get(0));
            assertEquals("Acme", organization.name());
            assertEquals("ACME-001", organization.taxCode());
            assertInstanceOf(Person.class, wrong.get(1));
            assertCacheTouched(clientCache, 100L);

            clientCache.put(
                    100L,
                    OrganizationDraft.$.produce(draft -> {
                        draft.setId(100L);
                        draft.setName("Acme");
                        draft.setTaxCode("ACME-001");
                    })
            );
            rawUpdate(
                    "update CLIENT set CLIENT_TYPE = ?, FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Person", "Changed", "Person", 100L
            );
            clearExecutions();
            List<Client> changed = new ArrayList<>();
            jdbc(con -> changed.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(projection))
                            .useObjectCache(ClientFetcher.$.forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, changed.size());
            // Fresh discriminator wins over the cached Organization branch.
            Person changedPerson = assertInstanceOf(Person.class, changed.get(0));
            assertEquals("Changed", changedPerson.firstName());
            assertInstanceOf(Person.class, changed.get(1));
        } finally {
            rawUpdate(
                    "update CLIENT set CLIENT_TYPE = 'ORG', NAME = 'Acme', TAX_CODE = 'ACME-001', " +
                            "FIRST_NAME = null, LAST_NAME = null where ID = ?",
                    100L
            );
            rawUpdate(
                    "update CLIENT set CLIENT_TYPE = 'Person', NAME = 'Bob', FIRST_NAME = 'Bob', LAST_NAME = 'Brown' " +
                            "where ID = ?",
                    101L
            );
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherBranchMaskRejectsProtectedAndUnselectedContent() {
        org.babyfish.jimmer.sql.model.inheritance.logical.singletable.ClientTable logicalTable =
                org.babyfish.jimmer.sql.model.inheritance.logical.singletable.ClientTable.$;
        IllegalArgumentException[] logicalFailure = new IllegalArgumentException[1];
        jdbc(con -> logicalFailure[0] = assertThrows(
                IllegalArgumentException.class,
                () -> sqlClient.createQuery(logicalTable)
                        .where(logicalTable.id().eq(400L))
                        .select(logicalTable.fetch(
                                org.babyfish.jimmer.sql.model.inheritance.logical.singletable.ClientFetcher.$
                                        .name()
                                        .forType(
                                                org.babyfish.jimmer.sql.model.inheritance.logical.singletable.OrganizationFetcher.$
                                                        .taxCode()
                                                        .deleted()
                                        )
                        ))
                        .useObjectCache(
                                org.babyfish.jimmer.sql.model.inheritance.logical.singletable.ClientFetcher.$
                                        .forType(
                                                org.babyfish.jimmer.sql.model.inheritance.logical.singletable.OrganizationFetcher.$
                                                        .deleted()
                                        )
                        )
                        .execute(con)
        ));
        assertNotNull(logicalFailure[0]);
        assertTrue(
                logicalFailure[0].getMessage().contains("protected property"),
                logicalFailure[0].getMessage()
        );

        org.babyfish.jimmer.sql.model.inheritance.joinedtable.cascade.ClientTable versionTable =
                org.babyfish.jimmer.sql.model.inheritance.joinedtable.cascade.ClientTable.$;
        IllegalArgumentException[] versionFailure = new IllegalArgumentException[1];
        jdbc(con -> versionFailure[0] = assertThrows(
                IllegalArgumentException.class,
                () -> sqlClient.createQuery(versionTable)
                        .where(versionTable.id().eq(1L))
                        .select(versionTable.fetch(
                                org.babyfish.jimmer.sql.model.inheritance.joinedtable.cascade.ClientFetcher.$
                                        .name()
                                        .forType(
                                                org.babyfish.jimmer.sql.model.inheritance.joinedtable.cascade.OrganizationFetcher.$
                                                        .version()
                                        )
                        ))
                        .useObjectCache(
                                org.babyfish.jimmer.sql.model.inheritance.joinedtable.cascade.ClientFetcher.$
                                        .forType(
                                                org.babyfish.jimmer.sql.model.inheritance.joinedtable.cascade.OrganizationFetcher.$
                                                        .version()
                                        )
                        )
                        .execute(con)
        ));
        assertNotNull(versionFailure[0]);
        assertTrue(
                versionFailure[0].getMessage().contains("protected property"),
                versionFailure[0].getMessage()
        );

        ClientTable table = ClientTable.$;
        IllegalArgumentException[] subsetFailure = new IllegalArgumentException[1];
        jdbc(con -> subsetFailure[0] = assertThrows(
                IllegalArgumentException.class,
                () -> sqlClient.createQuery(table)
                        .where(table.id().eq(100L))
                        .select(table.fetch(ClientFetcher.$.name()))
                        .useObjectCache(ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode()))
                        .execute(con)
        ));
        assertNotNull(subsetFailure[0]);
        assertTrue(
                subsetFailure[0].getMessage().contains("does not select"),
                subsetFailure[0].getMessage()
        );
    }

    @Test
    public void testContentFetcherBranchMaskKeepsUniversallySelectedBaseNameFresh() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        Fetcher<Client> projection =
                ClientFetcher.$
                        .name()
                        .forType(OrganizationFetcher.$.name().taxCode())
                        .forType(PersonFetcher.$.firstName());
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?", "STALE-100", "STALE-TAX-100", 100L);
            rawUpdate("update CLIENT set NAME = ?, FIRST_NAME = ? where ID = ?", "STALE-101", "STALE-FIRST", 101L);
            clearExecutions();
            List<Client> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(projection))
                            .useObjectCache(ClientFetcher.$.forType(OrganizationFetcher.$.name().taxCode()))
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            Organization organization = assertInstanceOf(Organization.class, rows.get(0));
            // A universal base column stays fresh; fresh SQL wins the branch overlap.
            assertEquals("STALE-100", organization.name());
            assertEquals("ACME-001", organization.taxCode());
            Person person = assertInstanceOf(Person.class, rows.get(1));
            assertEquals("STALE-101", person.name());
            assertEquals("STALE-FIRST", person.firstName());
            assertCacheTouched(clientCache, 100L);
        } finally {
            rawUpdate("update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001' where ID = ?", 100L);
            rawUpdate("update CLIENT set NAME = 'Bob', FIRST_NAME = 'Bob' where ID = ?", 101L);
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherBranchInheritedLeafApprovalKeepsSiblingsFresh() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        Fetcher<Client> projection =
                ClientFetcher.$
                        .forType(OrganizationFetcher.$.name().taxCode())
                        .forType(PersonFetcher.$.firstName());
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?", "STALE-100", "STALE-TAX-100", 100L);
            rawUpdate("update CLIENT set NAME = ?, FIRST_NAME = ? where ID = ?", "STALE-101", "STALE-FIRST", 101L);
            clearExecutions();
            List<Client> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(projection))
                            .useObjectCache(ClientFetcher.$.forType(OrganizationFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            Organization organization = assertInstanceOf(Organization.class, rows.get(0));
            assertEquals("Acme", organization.name());
            assertEquals("STALE-TAX-100", organization.taxCode());
            Person person = assertInstanceOf(Person.class, rows.get(1));
            assertEquals("STALE-FIRST", person.firstName());
            assertCacheTouched(clientCache, 100L);
        } finally {
            rawUpdate("update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001' where ID = ?", 100L);
            rawUpdate("update CLIENT set NAME = 'Bob', FIRST_NAME = 'Bob' where ID = ?", 101L);
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherBaseNameApprovalCoversExplicitBranchDuplicate() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?", "STALE-100", "STALE-TAX-100", 100L);
            rawUpdate("update CLIENT set NAME = ?, FIRST_NAME = ? where ID = ?", "STALE-101", "STALE-FIRST-101", 101L);
            clearExecutions();
            List<Client> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(
                                    ClientFetcher.$.name()
                                            .forType(OrganizationFetcher.$.name().taxCode())
                                            .forType(PersonFetcher.$.firstName())
                            ))
                            .useObjectCache(ClientFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            Organization organization = assertInstanceOf(Organization.class, rows.get(0));
            assertEquals("Acme", organization.name(), () -> {
                StringBuilder message = new StringBuilder("statements:");
                for (Execution execution : getExecutions()) {
                    message.append('\n').append(execution.getSql());
                }
                Client cached = clientCache.map.get(100L);
                return message.append("\ncacheTouched=").append(!clientCache.getAllKeys.isEmpty())
                        .append("\ncachedName=").append(cached != null ? cached.name() : null)
                        .toString();
            });
            assertEquals("STALE-TAX-100", organization.taxCode());
            Person person = assertInstanceOf(Person.class, rows.get(1));
            assertEquals("Bob", person.name());
            assertEquals("STALE-FIRST-101", person.firstName());
            // A root approval must also reduce the explicit branch's duplicate inherited NAME.
            String sql = getExecutions().get(0).getSql();
            assertFalse(sql.contains(".NAME"), sql);
            assertTrue(sql.contains("TAX_CODE"), sql);
        } finally {
            rawUpdate("update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001' where ID = ?", 100L);
            rawUpdate("update CLIENT set NAME = 'Bob', FIRST_NAME = 'Bob' where ID = ?", 101L);
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherBranchMaskAcceptsInheritedBaseSelection() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?", "STALE-100", "STALE-TAX-100", 100L);
            rawUpdate("update CLIENT set NAME = ?, FIRST_NAME = ? where ID = ?", "STALE-101", "STALE-FIRST-101", 101L);
            // The Organization branch approves the inherited NAME the base selects, plus TAX_CODE.
            clearExecutions();
            List<Client> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode())))
                            .useObjectCache(ClientFetcher.$.forType(OrganizationFetcher.$.name().taxCode()))
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            Organization organization = assertInstanceOf(Organization.class, rows.get(0));
            assertEquals("STALE-100", organization.name());
            assertEquals("ACME-001", organization.taxCode());
            Person person = assertInstanceOf(Person.class, rows.get(1));
            assertEquals("STALE-101", person.name());
            String sql = getExecutions().get(0).getSql();
            assertTrue(sql.contains(".NAME"), sql);
            assertFalse(sql.contains("TAX_CODE"), sql);
        } finally {
            rawUpdate("update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001' where ID = ?", 100L);
            rawUpdate("update CLIENT set NAME = 'Bob', FIRST_NAME = 'Bob' where ID = ?", 101L);
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherBranchDefaultDtoSqlReduction() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?", "STALE-100", "STALE-TAX-100", 100L);
            rawUpdate("update CLIENT set NAME = ? where ID = ?", "STALE-101", 101L);
            clearExecutions();
            List<ClientDefaultView> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(table.fetch(ClientDefaultView.class))
                            .useObjectCache(ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            ClientDefaultView.Organization organization =
                    assertInstanceOf(ClientDefaultView.Organization.class, rows.get(0));
            assertEquals(100L, organization.getId());
            assertEquals("Acme", organization.getName());
            assertEquals("ACME-001", organization.getTaxCode());
            ClientDefaultView.Other person =
                    assertInstanceOf(ClientDefaultView.Other.class, rows.get(1));
            assertEquals(101L, person.getId());
            assertEquals("Bob", person.getName());
            String sql = getExecutions().get(0).getSql();
            assertFalse(sql.contains("tb_1_.NAME"), sql);
            assertFalse(sql.contains("TAX_CODE"), sql);
            assertCacheTouched(clientCache, 100L);
        } finally {
            rawUpdate("update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001' where ID = ?", 100L);
            rawUpdate("update CLIENT set NAME = 'Bob' where ID = ?", 101L);
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherBranchLateBadPayloadFallsBackBeforeConverter() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        Fetcher<Client> projection =
                ClientFetcher.$
                        .name()
                        .forType(OrganizationFetcher.$.taxCode())
                        .forType(PersonFetcher.$.firstName().lastName());
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            // The later id's warm payload is incomplete (no name loaded).
            clientCache.put(101L, PersonDraft.$.produce(draft -> draft.setId(101L)));
            Function<Client, Client> converter = value -> {
                CLIENT_CONVERSIONS.incrementAndGet();
                return value;
            };
            CLIENT_CONVERSIONS.set(0);
            clearExecutions();
            List<Client> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(CLIENT_IDS))
                            .orderBy(table.id())
                            .select(new FetcherSelectionImpl<Client>(
                                    table,
                                    projection,
                                    converter
                            ))
                            .useObjectCache(ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode()))
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            Organization organization = assertInstanceOf(Organization.class, rows.get(0));
            assertEquals("Acme", organization.name());
            assertEquals("ACME-001", organization.taxCode());
            Person person = assertInstanceOf(Person.class, rows.get(1));
            assertEquals("Bob", person.name());
            assertEquals("Bob", person.firstName());
            assertEquals(2, CLIENT_CONVERSIONS.get());
            assertCacheTouched(clientCache, 100L);
        } finally {
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @Test
    public void testContentFetcherNestedPolymorphicChildBranchStaysConcreteTypeScoped() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientProjectTable table = ClientProjectTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();
        try {
            rawUpdate("update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?", "STALE-100", "STALE-TAX-100", 100L);
            rawUpdate("update CLIENT set NAME = ? where ID = ?", "STALE-101", 101L);
            clearExecutions();
            List<Client> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(Arrays.asList(1000L, 1002L)))
                            .orderBy(table.id())
                            .select(table.client().fetch(
                                    ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode())
                            ))
                            .useObjectCache(
                                    ClientProjectFetcher.$.client(
                                            ClientFetcher.$.name().forType(OrganizationFetcher.$.taxCode())
                                    )
                            )
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            Organization organization = assertInstanceOf(Organization.class, rows.get(0));
            assertEquals("Acme", organization.name());
            assertEquals("ACME-001", organization.taxCode());
            Person person = assertInstanceOf(Person.class, rows.get(1));
            assertEquals("Bob", person.name());
            assertCacheTouched(clientCache, 100L);
        } finally {
            rawUpdate("update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001' where ID = ?", 100L);
            rawUpdate("update CLIENT set NAME = 'Bob' where ID = ?", 101L);
            clientCache.delete(100L);
            clientCache.delete(101L);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testContentFetcherUnsupportedPublicFetcherProjectionDeclinesSafely() {
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Book.class ? bookCache : null);
        BookTable table = BookTable.$;
        Object forwardProxy = ProxyRecorder.of(Fetcher.class)
                .delegatesTo(BookFetcher.$.name())
                .proxy();
        // Fetcher.class.cast is the checked narrowing; the element cast is erased generics.
        Fetcher<Book> forward = (Fetcher<Book>) Fetcher.class.cast(forwardProxy);
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Book.class, Collections.singletonList(learningGraphQLId1)));
        bookCache.clearHistory();
        try {
            rawUpdate("update BOOK set NAME = ? where ID = ?", "FRESH-NAME", learningGraphQLId1);
            clearExecutions();
            List<Book> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(table.fetch(forward))
                            .useObjectCache(BookFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("FRESH-NAME", rows.get(0).name());
            // The unsupported projection is declined before any cache access.
            assertTrue(bookCache.getAllKeys.isEmpty());
        } finally {
            rawUpdate("update BOOK set NAME = ? where ID = ?", "Learning GraphQL", learningGraphQLId1);
            bookCache.delete(learningGraphQLId1);
        }
    }

    @Test
    public void testContentFetcherDepth2FakeFkJoinPolicyDeclinesWholeFresh() {
        MapCache<TreeNode2> nodeCache = new MapCache<>(ImmutableType.get(TreeNode2.class));
        JSqlClient client = getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setForeignKeyEnabledByDefault(false);
            // Scope metadata to TreeNode2 so the FAKE default is validated only here.
            builder.setEntityManager(new EntityManager(TreeNode2.class));
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return type.getJavaClass() == TreeNode2.class ? nodeCache : null;
                }
            }));
        });
        // Precondition: the AUTO to-one is FAKE under this client metadata, not a real FK.
        assertFalse(
                ImmutableType.get(TreeNode2.class).getProp("parent").isTargetForeignKeyReal(
                        assertInstanceOf(JSqlClientImplementor.class, client).getMetadataStrategy()
                )
        );
        TreeNode2Table table = TreeNode2Table.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(TreeNode2.class, Arrays.asList(3L, 2L, 1L)));
        nodeCache.clearHistory();
        try {
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "FRESH-CHILD", 3L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "FRESH-PARENT", 2L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "FRESH-GRANDPARENT", 1L);
            clearExecutions();
            List<TreeNode2> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(3L))
                            .select(table.fetch(
                                    // Explicit join: AUTO resolves to a post-fetch SELECT, so the
                                    // fake-FK join policy would not be the effective fetch.
                                    TreeNode2Fetcher.$.name().parent(
                                            ReferenceFetchType.JOIN_ALWAYS,
                                            TreeNode2Fetcher.$.name().parent(
                                                    ReferenceFetchType.JOIN_ALWAYS,
                                                    TreeNode2Fetcher.$.name()
                                            )
                                    )
                            ))
                            .useObjectCache(
                                    TreeNode2Fetcher.$.parent(
                                            TreeNode2Fetcher.$.parent(TreeNode2Fetcher.$.name())
                                    )
                            )
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("FRESH-CHILD", rows.get(0).name());
            TreeNode2 parent = rows.get(0).parent();
            assertNotNull(parent);
            assertEquals("FRESH-PARENT", parent.name());
            TreeNode2 grandparent = parent.parent();
            assertNotNull(grandparent);
            assertEquals("FRESH-GRANDPARENT", grandparent.name());
            // The depth-2 fake-FK join is not reduced to an id-only reference: whole fresh.
            assertTrue(nodeCache.getAllKeys.isEmpty());
        } finally {
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Drinks", 3L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Food", 2L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Home", 1L);
            nodeCache.delete(3L);
            nodeCache.delete(2L);
            nodeCache.delete(1L);
        }
    }

    @Test
    public void testOrdinaryJoinedConcreteLeafKeepsPolymorphicBaseDiscriminator() {
        OrganizationProjectTable orgProject = OrganizationProjectTable.$;
        List<Tuple2<Organization, String>> joinedLeaves = new ArrayList<>();
        jdbc(con -> joinedLeaves.addAll(
                sqlClient.createQuery(orgProject)
                        .where(orgProject.id().eq(1001L))
                        .select(
                                orgProject.organization().fetch(OrganizationFetcher.$.name().taxCode()),
                                orgProject.name()
                        )
                        .execute(con)
        ));
        assertEquals(1, joinedLeaves.size());
        Organization organization = joinedLeaves.get(0).get_1();
        assertNotNull(organization);
        // A concrete joined leaf reads only its own columns; a stray discriminator shifts them.
        assertEquals("Acme", organization.name());
        assertEquals("ACME-001", organization.taxCode());
        assertEquals("Single organization project", joinedLeaves.get(0).get_2());

        ClientProjectTable clientProject = ClientProjectTable.$;
        List<Client> joinedBase = new ArrayList<>();
        jdbc(con -> joinedBase.addAll(
                sqlClient.createQuery(clientProject)
                        .where(clientProject.id().eq(1000L))
                        .select(clientProject.client().fetch(ClientFetcher.$.name()))
                        .execute(con)
        ));
        assertEquals(1, joinedBase.size());
        // The polymorphic base joined leaf still consumes its discriminator.
        assertEquals("Acme", assertInstanceOf(Organization.class, joinedBase.get(0)).name());
    }

    @Test
    public void testContentFetcherInheritedBranchChildSelectionStaysSafe() {
        MapCache<Department> departmentCache = new MapCache<>(ImmutableType.get(Department.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Department.class ? departmentCache : null);
        EmployeeTable table = EmployeeTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Department.class, Arrays.asList(6900L, 6901L)));
        departmentCache.clearHistory();
        try {
            rawUpdate("update STAFF_DEPARTMENT set NAME = ? where ID = ?", "FRESH-ENG", 6900L);
            rawUpdate("update STAFF_DEPARTMENT set NAME = ? where ID = ?", "FRESH-SALES", 6901L);
            clearExecutions();
            List<Employee> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(Arrays.asList(6000L, 6002L)))
                            .orderBy(table.id())
                            .select(table.fetch(
                                    EmployeeFetcher.$.department(DepartmentFetcher.$.name())
                                            .forType(FullTimeEmployeeFetcher.$.annualSalary())
                            ))
                            .useObjectCache(
                                    EmployeeFetcher.$.forType(
                                            FullTimeEmployeeFetcher.$.department(DepartmentFetcher.$.name())
                                    )
                            )
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            FullTimeEmployee fullTime = assertInstanceOf(FullTimeEmployee.class, rows.get(0));
            assertNotNull(fullTime.department());
            assertEquals("FRESH-ENG", fullTime.department().name());
            assertEquals(120000L, fullTime.annualSalary().longValue());
            PartTimeEmployee partTime = assertInstanceOf(PartTimeEmployee.class, rows.get(1));
            assertNotNull(partTime.department());
            assertEquals("FRESH-SALES", partTime.department().name());
            // The owning hint conservatively declines, so it never touches the cache.
            assertTrue(departmentCache.getAllKeys.isEmpty());
        } finally {
            rawUpdate("update STAFF_DEPARTMENT set NAME = ? where ID = ?", "Engineering", 6900L);
            rawUpdate("update STAFF_DEPARTMENT set NAME = ? where ID = ?", "Sales", 6901L);
            departmentCache.delete(6900L);
            departmentCache.delete(6901L);
        }
    }

    @Test
    public void testLegacyBooleanHintMatchesOrdinaryPolymorphicShape() {
        MapCache<Client> clientCache = new MapCache<>(ImmutableType.get(Client.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Client.class ? clientCache : null);
        ClientTable table = ClientTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Client.class, CLIENT_IDS));
        clientCache.clearHistory();

        List<Client> ordinary = new ArrayList<>();
        jdbc(con -> ordinary.addAll(
                client.createQuery(table).where(table.id().in(CLIENT_IDS)).orderBy(table.id())
                        .select(table).execute(con)
        ));
        clearExecutions();
        List<Client> noArg = new ArrayList<>();
        jdbc(con -> noArg.addAll(
                client.createQuery(table).where(table.id().in(CLIENT_IDS)).orderBy(table.id())
                        .select(table).useObjectCache().execute(con)
        ));
        clearExecutions();
        List<Client> trueArg = new ArrayList<>();
        jdbc(con -> trueArg.addAll(
                client.createQuery(table).where(table.id().in(CLIENT_IDS)).orderBy(table.id())
                        .select(table).useObjectCache(true).execute(con)
        ));

        assertSameLoadedShape(ordinary, noArg);
        assertSameLoadedShape(ordinary, trueArg);
        // The ordinary bare projection leaves unselected subtype props unloaded.
        assertFalse(assertInstanceOf(ImmutableSpi.class, ordinary.get(0)).__isLoaded("taxCode"));
        assertFalse(assertInstanceOf(ImmutableSpi.class, ordinary.get(1)).__isLoaded("firstName"));
    }

    @Test
    public void testContentFetcherPaginationAndMembershipParity() {
        BookTable table = BookTable.$;
        BookTableEx book = table.asTableEx();
        List<UUID> ids = Arrays.asList(learningGraphQLId1, learningGraphQLId2);
        jdbc(con -> sqlClient.getEntities().forConnection(con).findByIds(Book.class, ids));
        List<Book> ordinary = new ArrayList<>();
        jdbc(con -> ordinary.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().in(ids))
                        .orderBy(table.id(), book.authors().firstName())
                        .select(table.fetch(BookFetcher.$.name()))
                        .offset(1).limit(3)
                        .execute(con)
        ));
        clearExecutions();
        List<Book> hinted = new ArrayList<>();
        jdbc(con -> hinted.addAll(
                sqlClient.createQuery(table)
                        .where(table.id().in(ids))
                        .orderBy(table.id(), book.authors().firstName())
                        .select(table.fetch(BookFetcher.$.name()))
                        .offset(1).limit(3)
                        .useObjectCache(BookFetcher.$.name())
                        .execute(con)
        ));
        List<String> ordinaryRows = new ArrayList<>();
        for (Book row : ordinary) {
            ordinaryRows.add(row.id() + "|" + row.name());
        }
        List<String> hintedRows = new ArrayList<>();
        for (Book row : hinted) {
            hintedRows.add(row.id() + "|" + row.name());
        }
        assertEquals(ordinaryRows, hintedRows);
        assertEquals(3, hinted.size());
    }

    @Test
    public void testContentFetcherMixedTupleCachesJoinedChildNotBareRoot() {
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> {
            Class<?> javaClass = type.getJavaClass();
            if (javaClass == Book.class) {
                return bookCache;
            }
            if (javaClass == BookStore.class) {
                return storeCache;
            }
            return null;
        });
        BookTable table = BookTable.$;
        jdbc(con -> {
            client.getEntities().forConnection(con)
                    .findByIds(Book.class, Collections.singletonList(learningGraphQLId1));
            client.getEntities().forConnection(con)
                    .findByIds(BookStore.class, Collections.singletonList(oreillyId));
        });
        bookCache.clearHistory();
        storeCache.clearHistory();
        try {
            rawUpdate(
                    "update BOOK set NAME = ?, PRICE = ? where ID = ?",
                    "STALE-BOOK", new BigDecimal("999"), learningGraphQLId1
            );
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "STALE-STORE", oreillyId);
            clearExecutions();
            List<Tuple3<Book, BookStore, BigDecimal>> hinted = new ArrayList<>();
            jdbc(con -> hinted.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(
                                    table,
                                    table.store().fetch(BookStoreFetcher.$.name()),
                                    table.price()
                            )
                            .useObjectCache(BookFetcher.$.name().store(BookStoreFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, hinted.size());
            assertEquals(oreillyId, hinted.get(0).get_2().id());
            assertEquals("O'REILLY", hinted.get(0).get_2().name());
            assertCacheTouched(storeCache, oreillyId);
            assertEquals(0, hinted.get(0).get_1().price().compareTo(new BigDecimal("999")));
            assertEquals("Learning GraphQL", hinted.get(0).get_1().name());
            List<Execution> executions = getExecutions();
            assertEquals(1, executions.size());
            String sql = executions.get(0).getSql();
            assertTrue(sql.contains("BOOK_STORE"), sql);
            assertTrue(sql.contains("PRICE"), sql);
            assertFalse(sql.contains("tb_2_.NAME"), sql);
            assertFalse(sql.contains("tb_1_.NAME"), sql);
        } finally {
            rawUpdate(
                    "update BOOK set NAME = ?, PRICE = ? where ID = ?",
                    "Learning GraphQL", new BigDecimal("50"), learningGraphQLId1
            );
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            bookCache.delete(learningGraphQLId1);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testContentFetcherExplicitSelectChildStaysConsistent() {
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> {
            Class<?> javaClass = type.getJavaClass();
            if (javaClass == Book.class) {
                return bookCache;
            }
            if (javaClass == BookStore.class) {
                return storeCache;
            }
            return null;
        });
        BookTable table = BookTable.$;
        jdbc(con -> {
            client.getEntities().forConnection(con)
                    .findByIds(Book.class, Collections.singletonList(learningGraphQLId1));
            client.getEntities().forConnection(con)
                    .findByIds(BookStore.class, Collections.singletonList(oreillyId));
        });
        bookCache.clearHistory();
        storeCache.clearHistory();
        try {
            rawUpdate("update BOOK set NAME = ? where ID = ?", "STALE-BOOK", learningGraphQLId1);
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "STALE-STORE", oreillyId);
            clearExecutions();
            List<Book> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(table.fetch(
                                    BookFetcher.$
                                            .name()
                                            .store(ReferenceFetchType.SELECT, BookStoreFetcher.$.name())
                            ))
                            .useObjectCache(BookFetcher.$.name().store(BookStoreFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("Learning GraphQL", rows.get(0).name());
            assertEquals(oreillyId, rows.get(0).store().id());
            assertEquals("O'REILLY", rows.get(0).store().name());
        } finally {
            rawUpdate("update BOOK set NAME = ? where ID = ?", "Learning GraphQL", learningGraphQLId1);
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            bookCache.delete(learningGraphQLId1);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testContentFetcherMergedQueriesKeepUnapprovedSelectedChildFresh() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        BookTable table = BookTable.$;
        Function<UUID, ConfigurableRootQuery<BookTable, Book>> masked = id -> client.createQuery(table)
                .where(table.id().eq(id))
                .select(table.fetch(BookFetcher.$.name().store(
                        ReferenceFetchType.SELECT,
                        BookStoreFetcher.$.name().version()
                )))
                .useObjectCache(BookFetcher.$.name().store(BookStoreFetcher.$.name()));
        Function<UUID, ConfigurableRootQuery<BookTable, Book>> ordinary = id -> client.createQuery(table)
                .where(table.id().eq(id))
                .select(table.fetch(BookFetcher.$.name().store(
                        ReferenceFetchType.SELECT,
                        BookStoreFetcher.$.name().version()
                )));
        try {
            rawUpdate("update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?", "FRESH-STORE", 9, oreillyId);

            List<TypedRootQuery<Book>> merged = Arrays.asList(
                    TypedRootQuery.union(ordinary.apply(learningGraphQLId1), masked.apply(learningGraphQLId1)),
                    TypedRootQuery.unionAll(ordinary.apply(learningGraphQLId1),
                            TypedRootQuery.unionAll(ordinary.apply(learningGraphQLId1), masked.apply(learningGraphQLId1))),
                    TypedRootQuery.minus(masked.apply(learningGraphQLId1), ordinary.apply(learningGraphQLId2)),
                    TypedRootQuery.intersect(ordinary.apply(learningGraphQLId1), masked.apply(learningGraphQLId1))
            );
            int[] expectedSizes = {1, 3, 1, 1};
            for (int i = 0; i < merged.size(); i++) {
                TypedRootQuery<Book> query = merged.get(i);
                List<Book> rows = new ArrayList<>();
                jdbc(con -> rows.addAll(query.execute(con)));
                assertEquals(expectedSizes[i], rows.size());
                for (Book row : rows) {
                    assertEquals("FRESH-STORE", row.store().name());
                    assertEquals(9, row.store().version());
                }
            }

            List<Book> ordinaryMerged = new ArrayList<>();
            jdbc(con -> ordinaryMerged.addAll(TypedRootQuery.unionAll(
                    ordinary.apply(learningGraphQLId1),
                    ordinary.apply(learningGraphQLId1)
            ).execute(con)));
            assertEquals(2, ordinaryMerged.size());
            for (Book row : ordinaryMerged) {
                assertEquals("O'REILLY", row.store().name());
                assertEquals(0, row.store().version());
            }

            List<Book> booleanHintMerged = new ArrayList<>();
            jdbc(con -> booleanHintMerged.addAll(TypedRootQuery.unionAll(
                    ordinary.apply(learningGraphQLId1).useObjectCache(true),
                    ordinary.apply(learningGraphQLId1).useObjectCache(true)
            ).execute(con)));
            assertEquals(2, booleanHintMerged.size());
            for (Book row : booleanHintMerged) {
                assertEquals("O'REILLY", row.store().name());
                assertEquals(0, row.store().version());
            }

            List<String> iterated = new ArrayList<>();
            jdbc(con -> TypedRootQuery.unionAll(
                            ordinary.apply(learningGraphQLId1),
                            masked.apply(learningGraphQLId1)
                    ).forEach(con, row -> iterated.add(row.store().name() + ":" + row.store().version())));
            assertEquals(Arrays.asList("FRESH-STORE:9", "FRESH-STORE:9"), iterated);
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?", "O'REILLY", 0, oreillyId);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testNativePostgresCommittedRootChildEdgesAndDisplay() {
        NativeDatabases.assumeNativeDatabase();
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setDialect(new PostgresDialect());
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    Class<?> javaClass = type.getJavaClass();
                    if (javaClass == Book.class) {
                        return bookCache;
                    }
                    if (javaClass == BookStore.class) {
                        return storeCache;
                    }
                    return null;
                }
            }));
        });
        BookTable table = BookTable.$;
        jdbc(NativeDatabases.POSTGRES_DATA_SOURCE, false, con -> {
            client.getEntities().forConnection(con)
                    .findByIds(Book.class, Collections.singletonList(learningGraphQLId1));
            client.getEntities().forConnection(con)
                    .findByIds(BookStore.class, Collections.singletonList(oreillyId));
        });
        bookCache.clearHistory();
        storeCache.clearHistory();
        try {
            rawUpdate(
                    NativeDatabases.POSTGRES_DATA_SOURCE,
                    "update BOOK set NAME = ?, PRICE = ? where ID = ?",
                    "STALE-PG-BOOK", new BigDecimal("999"), learningGraphQLId1
            );
            rawUpdate(
                    NativeDatabases.POSTGRES_DATA_SOURCE,
                    "update BOOK_STORE set NAME = ? where ID = ?",
                    "STALE-PG-STORE", oreillyId
            );
            clearExecutions();
            List<Book> rows = new ArrayList<>();
            jdbc(NativeDatabases.POSTGRES_DATA_SOURCE, false, con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(learningGraphQLId1))
                            .select(table.fetch(
                                    BookFetcher.$.name().store(BookStoreFetcher.$.name())
                            ))
                            .useObjectCache(BookFetcher.$.name().store(BookStoreFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("Learning GraphQL", rows.get(0).name());
            assertEquals(oreillyId, rows.get(0).store().id());
            assertEquals("O'REILLY", rows.get(0).store().name());
            assertCacheTouched(bookCache, learningGraphQLId1);
            assertCacheTouched(storeCache, oreillyId);
            List<Execution> executions = getExecutions();
            assertEquals(1, executions.size());
            String sql = executions.get(0).getSql();
            assertTrue(sql.contains("STORE_ID"), sql);
            assertFalse(sql.contains("NAME"), sql);
        } finally {
            rawUpdate(
                    NativeDatabases.POSTGRES_DATA_SOURCE,
                    "update BOOK set NAME = ?, PRICE = ? where ID = ?",
                    "Learning GraphQL", new BigDecimal("50"), learningGraphQLId1
            );
            rawUpdate(
                    NativeDatabases.POSTGRES_DATA_SOURCE,
                    "update BOOK_STORE set NAME = ? where ID = ?",
                    "O'REILLY", oreillyId
            );
            bookCache.delete(learningGraphQLId1);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testFakeFkChildMaskWarmTargetServesApprovedContent() {
        MapCache<Issue1434User> userCache = new MapCache<>(ImmutableType.get(Issue1434User.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Issue1434User.class ? userCache : null);
        Issue1434MessageTable table = Issue1434MessageTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Issue1434User.class, Collections.singletonList(1L)));
        userCache.clearHistory();
        try {
            rawUpdate("update ISSUE_1434_USER set NAME = ? where ID = ?", "STALE-USER", 1L);
            clearExecutions();
            List<Tuple2<Issue1434Message, Issue1434User>> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(
                                    table,
                                    table.user().fetch(Issue1434UserFetcher.$.name())
                            )
                            .useObjectCache(Issue1434MessageFetcher.$.user(Issue1434UserFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals(1L, rows.get(0).get_2().id());
            assertEquals("user-1", rows.get(0).get_2().name());
            assertCacheTouched(userCache, 1L);
        } finally {
            rawUpdate("update ISSUE_1434_USER set NAME = ? where ID = ?", "user-1", 1L);
            userCache.delete(1L);
        }
    }

    @Test
    public void testFakeFkChildMaskDeletedTargetIsNotResurrected() {
        MapCache<Issue1434User> userCache = new MapCache<>(ImmutableType.get(Issue1434User.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Issue1434User.class ? userCache : null);
        Issue1434MessageTable table = Issue1434MessageTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Issue1434User.class, Collections.singletonList(1L)));
        userCache.clearHistory();
        try {
            rawUpdate("delete from ISSUE_1434_USER where ID = ?", 1L);

            clearExecutions();
            List<Issue1434Message> masked = new ArrayList<>();
            jdbc(con -> masked.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(Issue1434MessageFetcher.$.user(Issue1434UserFetcher.$.name())))
                            .useObjectCache(Issue1434MessageFetcher.$.user(Issue1434UserFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, masked.size(), "the root message row must be preserved");
            assertNull(masked.get(0).user(), "a deleted FAKE-FK target must never be resurrected from cache");

            clearExecutions();
            List<Issue1434Message> joined = new ArrayList<>();
            jdbc(con -> joined.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(Issue1434MessageFetcher.$.user(
                                    ReferenceFetchType.JOIN_ALWAYS, Issue1434UserFetcher.$.name()
                            )))
                            .useObjectCache(Issue1434MessageFetcher.$.user(Issue1434UserFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, joined.size());
            assertNull(joined.get(0).user());
            assertTrue(
                    getExecutions().get(0).getSql().contains("join ISSUE_1434_USER"),
                    getExecutions().get(0).getSql()
            );
        } finally {
            rawUpdate("insert into ISSUE_1434_USER(ID, NAME) values(1, ?)", "user-1");
            userCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherRejectsProtectedVersionLeaf() {
        BookStoreTable table = BookStoreTable.$;
        jdbc(con -> assertThrows(
                IllegalArgumentException.class,
                () -> sqlClient.createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(BookStoreFetcher.$.name().version()))
                        .useObjectCache(BookStoreFetcher.$.version())
                        .execute(con)
        ));
    }

    @Test
    public void testContentFetcherRejectsInheritedLogicalDeletedLeaf() {
        AdministratorTable table = AdministratorTable.$;
        jdbc(con -> assertThrows(
                IllegalArgumentException.class,
                () -> sqlClient.createQuery(table)
                        .where(table.id().eq(1L))
                        .select(table.fetch(AdministratorFetcher.$.name()))
                        .useObjectCache(AdministratorFetcher.$.deleted())
                        .execute(con)
        ));
    }

    @Test
    public void testContentFetcherRejectsLogicalDeletedLeaf() {
        PostTable table = PostTable.$;
        jdbc(con -> assertThrows(
                IllegalArgumentException.class,
                () -> sqlClient.createQuery(table)
                        .where(table.id().eq(1L))
                        .select(table.fetch(PostFetcher.$.name()))
                        .useObjectCache(PostFetcher.$.deletedUUID())
                        .execute(con)
        ));
    }

    @Test
    public void testContentFetcherLateIncompletePayloadFallsBackWholePageOnce() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookStoreTable table = BookStoreTable.$;
        List<UUID> ids = Arrays.asList(oreillyId, manningId);
        jdbc(con -> client.getEntities().forConnection(con).findByIds(BookStore.class, ids));
        storeCache.put(manningId, BookStoreDraft.$.produce(draft -> draft.setId(manningId)));
        storeCache.clearHistory();
        AtomicInteger conversions = new AtomicInteger();
        Function<BookStore, BookStore> converter = store -> {
            conversions.incrementAndGet();
            return store;
        };
        try {
            // NAME is unique, so each id needs its own fresh value.
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "FRESH-OREILLY", oreillyId);
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "FRESH-MANNING", manningId);
            clearExecutions();
            List<BookStore> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(ids))
                            .orderBy(Expression.string().caseBuilder()
                                    .when(table.id().eq(oreillyId), "A")
                                    .otherwise("B"))
                            .select(new FetcherSelectionImpl<BookStore>(
                                    table,
                                    BookStoreFetcher.$.name(),
                                    converter
                            ))
                            .useObjectCache(BookStoreFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(Arrays.asList(oreillyId, manningId), idsOf(rows), "valid-before-bad order");
            assertEquals(2, conversions.get(), "the whole fallback must run each converter exactly once");
            assertEquals("FRESH-OREILLY", rows.get(0).name());
            assertEquals("FRESH-MANNING", rows.get(1).name());
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "MANNING", manningId);
            storeCache.delete(oreillyId);
            storeCache.delete(manningId);
        }
    }

    @Test
    public void testContentFetcherMalformedCachedIdFallsBackWholeQuery() {
        assertCorruptCachePayloadFallsBack(
                BookStoreDraft.$.produce(draft -> draft.setName("cached-without-id"))
        );
    }

    @Test
    public void testContentFetcherMismatchedCachedIdFallsBackWholeQuery() {
        assertCorruptCachePayloadFallsBack(
                BookStoreDraft.$.produce(draft -> {
                    draft.setId(manningId);
                    draft.setName("cached-other");
                })
        );
    }

    private void assertCorruptCachePayloadFallsBack(BookStore payload) {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookStoreTable table = BookStoreTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        storeCache.put(oreillyId, payload);
        storeCache.clearHistory();
        try {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "FRESH-DB-NAME", oreillyId);
            clearExecutions();
            List<BookStore> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(oreillyId))
                            .select(table.fetch(BookStoreFetcher.$.name()))
                            .useObjectCache(BookStoreFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("FRESH-DB-NAME", rows.get(0).name());
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testContentFetcherThrowingCachePropagates() {
        JSqlClient client = createClient(
                type -> type.getJavaClass() == BookStore.class ? new ThrowingCache(type) : null
        );
        BookStoreTable table = BookStoreTable.$;
        jdbc(con -> assertThrows(
                IllegalStateException.class,
                () -> client.createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(BookStoreFetcher.$.name()))
                        .useObjectCache(BookStoreFetcher.$.name())
                        .execute(con)
        ));
    }

    @Test
    public void testContentFetcherInapplicableMaskNeverMisapplied() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookStoreTable table = BookStoreTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        storeCache.clearHistory();
        try {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "FRESH-DB-NAME", oreillyId);
            assertInapplicableMaskSafe(client, table, BookStoreFetcher.$.website());
            assertInapplicableMaskSafe(client, table, AuthorFetcher.$.firstName());
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            storeCache.delete(oreillyId);
        }
    }

    // An inapplicable mask may either raise a clear validation error or fall back fresh; both are safe.
    private static void assertInapplicableMaskSafe(JSqlClient client, BookStoreTable table, Fetcher<?> mask) {
        List<BookStore> rows = new ArrayList<>();
        try {
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(oreillyId))
                            .select(table.fetch(BookStoreFetcher.$.name()))
                            .useObjectCache(mask)
                            .execute(con)
            ));
        } catch (IllegalArgumentException expected) {
            return;
        }
        assertEquals(1, rows.size());
        assertEquals("FRESH-DB-NAME", rows.get(0).name());
    }

    @Test
    public void testContentFetcherAggregateShapeStaysFresh() {
        BookStoreTable table = BookStoreTable.$;
        clearExecutions();
        List<Long> counts = new ArrayList<>();
        jdbc(con -> counts.addAll(
                sqlClient.createQuery(table)
                        .select(table.id().count())
                        .useObjectCache(BookStoreFetcher.$.name())
                        .execute(con)
        ));
        assertEquals(1, counts.size());
        assertEquals(2L, counts.get(0).longValue());
    }

    @Test
    public void testContentFetcherSwappedSameTypePayloadsFallBackWholePageOnce() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookStoreTable table = BookStoreTable.$;
        List<UUID> ids = Arrays.asList(oreillyId, manningId);
        jdbc(con -> client.getEntities().forConnection(con).findByIds(BookStore.class, ids));
        storeCache.put(oreillyId, BookStoreDraft.$.produce(draft -> {
            draft.setId(manningId);
            draft.setName("cached-manning-in-oreilly-slot");
        }));
        storeCache.put(manningId, BookStoreDraft.$.produce(draft -> {
            draft.setId(oreillyId);
            draft.setName("cached-oreilly-in-manning-slot");
        }));
        storeCache.clearHistory();
        AtomicInteger conversions = new AtomicInteger();
        Function<BookStore, BookStore> converter = store -> {
            conversions.incrementAndGet();
            return store;
        };
        try {
            // NAME is unique, so each id needs its own fresh value.
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "FRESH-OREILLY", oreillyId);
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "FRESH-MANNING", manningId);
            clearExecutions();
            List<BookStore> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(ids))
                            .orderBy(Expression.string().caseBuilder()
                                    .when(table.id().eq(oreillyId), "A")
                                    .otherwise("B"))
                            .select(new FetcherSelectionImpl<BookStore>(
                                    table,
                                    BookStoreFetcher.$.name(),
                                    converter
                            ))
                            .useObjectCache(BookStoreFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(Arrays.asList(oreillyId, manningId), idsOf(rows));
            assertEquals(2, rows.size());
            assertEquals(2, conversions.get(), "the whole fallback must run each converter exactly once");
            assertEquals("FRESH-OREILLY", rows.get(0).name());
            assertEquals("FRESH-MANNING", rows.get(1).name());
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "MANNING", manningId);
            storeCache.delete(oreillyId);
            storeCache.delete(manningId);
        }
    }

    @Test
    public void testContentFetcherCachedNullEmbeddedDoesNotHideCurrentPresence() {
        MapCache<Machine> machineCache = new MapCache<>(ImmutableType.get(Machine.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Machine.class ? machineCache : null);
        MachineTable table = MachineTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Machine.class, Collections.singletonList(1L)));
        machineCache.clearHistory();
        try {
            rawUpdate(
                    "update MACHINE set SECONDARY_HOST = ?, SECONDARY_PORT = ? where ID = ?",
                    "secondary-host", 9090, 1L
            );
            clearExecutions();
            List<Machine> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(MachineFetcher.$.secondaryLocation(LocationFetcher.$.host().port())))
                            .useObjectCache(MachineFetcher.$.secondaryLocation(LocationFetcher.$.host().port()))
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertNotNull(rows.get(0).secondaryLocation(), "a cached null must not hide a current embedded value");
            assertEquals("secondary-host", rows.get(0).secondaryLocation().host());
            assertEquals(9090, rows.get(0).secondaryLocation().port().intValue());
        } finally {
            rawUpdate("update MACHINE set SECONDARY_HOST = null, SECONDARY_PORT = null where ID = ?", 1L);
            machineCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherStalePresentEmbeddedDoesNotHideCurrentAbsence() {
        MapCache<Machine> machineCache = new MapCache<>(ImmutableType.get(Machine.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Machine.class ? machineCache : null);
        MachineTable table = MachineTable.$;
        try {
            rawUpdate(
                    "update MACHINE set SECONDARY_HOST = ?, SECONDARY_PORT = ? where ID = ?",
                    "cached-secondary", 9091, 1L
            );
            jdbc(con -> client.getEntities().forConnection(con)
                    .findByIds(Machine.class, Collections.singletonList(1L)));
            machineCache.clearHistory();
            rawUpdate("update MACHINE set SECONDARY_HOST = null, SECONDARY_PORT = null where ID = ?", 1L);
            clearExecutions();
            List<Machine> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(MachineFetcher.$.secondaryLocation(LocationFetcher.$.host().port())))
                            .useObjectCache(MachineFetcher.$.secondaryLocation(LocationFetcher.$.host().port()))
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertNull(
                    rows.get(0).secondaryLocation(),
                    "a stale cached embedded value must not hide the current absence"
            );
        } finally {
            rawUpdate("update MACHINE set SECONDARY_HOST = null, SECONDARY_PORT = null where ID = ?", 1L);
            machineCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherIncompleteCachedEmbeddedFallsBackWholeQuery() {
        MapCache<Machine> machineCache = new MapCache<>(ImmutableType.get(Machine.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Machine.class ? machineCache : null);
        MachineTable table = MachineTable.$;
        // The warm embedded omits the approved host leaf, so it cannot satisfy the reduced read.
        machineCache.put(1L, MachineDraft.$.produce(draft -> {
            draft.setId(1L);
            draft.setLocation(LocationDraft.$.produce(location -> location.setPort(1234)));
        }));
        machineCache.clearHistory();
        try {
            rawUpdate("update MACHINE set HOST = ?, PORT = ? where ID = ?", "fresh-host", 9090, 1L);
            clearExecutions();
            List<Machine> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(MachineFetcher.$.location(LocationFetcher.$.host().port())))
                            .useObjectCache(MachineFetcher.$.location(LocationFetcher.$.host()))
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("fresh-host", rows.get(0).location().host());
            assertEquals(9090, rows.get(0).location().port().intValue());
            assertCacheTouched(machineCache, 1L);
        } finally {
            rawUpdate("update MACHINE set HOST = ?, PORT = ? where ID = ?", "localhost", 8080, 1L);
            machineCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherRequiredEmbeddedAllLeavesApprovedStaysPresent() {
        MapCache<Machine> machineCache = new MapCache<>(ImmutableType.get(Machine.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Machine.class ? machineCache : null);
        MachineTable table = MachineTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(Machine.class, Collections.singletonList(1L)));
        machineCache.put(1L, MachineDraft.$.produce(draft -> {
            draft.setId(1L);
            draft.setLocation(LocationDraft.$.produce(location -> location.setHost("cached-host")));
        }));
        machineCache.clearHistory();
        try {
            rawUpdate("update MACHINE set HOST = ?, PORT = ? where ID = ?", "fresh-host", 9090, 1L);
            clearExecutions();
            List<Machine> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1L))
                            .select(table.fetch(MachineFetcher.$.location(LocationFetcher.$.host().port())))
                            .useObjectCache(MachineFetcher.$.location())
                            .execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals("fresh-host", rows.get(0).location().host());
            assertEquals(9090, rows.get(0).location().port().intValue());
        } finally {
            rawUpdate("update MACHINE set HOST = ?, PORT = ? where ID = ?", "localhost", 8080, 1L);
            machineCache.delete(1L);
        }
    }

    @Test
    public void testContentFetcherExplicitJoinFetchChildCacheStillServed() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookTable table = BookTable.$;
        jdbc(con -> client.getEntities().forConnection(con).findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        storeCache.clearHistory();
        try {
            rawUpdate("update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?", "STALE-STORE", 9, oreillyId);
            for (ReferenceFetchType fetchType : new ReferenceFetchType[]{
                    ReferenceFetchType.JOIN_ALWAYS, ReferenceFetchType.JOIN_IF_NO_CACHE
            }) {
                clearExecutions();
                List<Book> rows = new ArrayList<>();
                jdbc(con -> rows.addAll(
                        client.createQuery(table)
                                .where(table.id().eq(learningGraphQLId1))
                                .select(table.fetch(
                                        BookFetcher.$.name().store(fetchType, BookStoreFetcher.$.name().version())
                                ))
                                .useObjectCache(BookFetcher.$.name().store(BookStoreFetcher.$.name()))
                                .execute(con)
                ));
                assertEquals(1, rows.size(), "fetch type " + fetchType);
                assertEquals("Learning GraphQL", rows.get(0).name(), "fetch type " + fetchType);
                assertEquals(oreillyId, rows.get(0).store().id(), "fetch type " + fetchType);
                assertEquals("O'REILLY", rows.get(0).store().name(), "fetch type " + fetchType);
                assertEquals(9, rows.get(0).store().version(), "fetch type " + fetchType);
            }
            assertCacheTouched(storeCache, oreillyId);
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?", "O'REILLY", 0, oreillyId);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testContentFetcherReselectAfterMaskKeepsUnapprovedNestedChildFresh() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        BookTable table = BookTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        storeCache.clearHistory();
        try {
            rawUpdate("update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?", "STALE-STORE", 9, oreillyId);
            clearExecutions();
            ConfigurableRootQuery<BookTable, Book> masked = client.createQuery(table)
                    .where(table.id().eq(learningGraphQLId1))
                    .select(table.fetch(BookFetcher.$.name()))
                    .useObjectCache(BookFetcher.$.name());
            ConfigurableRootQuery<BookTable, Book> reselected = masked.reselect(
                    (query, book) -> query.select(book.fetch(
                            BookFetcher.$.name().store(
                                    ReferenceFetchType.SELECT,
                                    BookStoreFetcher.$.name().version()
                            )
                    ))
            );
            List<Book> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(reselected.execute(con)));
            assertEquals(1, rows.size());
            assertEquals(learningGraphQLId1, rows.get(0).id());
            assertEquals("Learning GraphQL", rows.get(0).name());
            assertEquals(oreillyId, rows.get(0).store().id());
            assertEquals("STALE-STORE", rows.get(0).store().name());
            assertEquals(9, rows.get(0).store().version());
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?", "O'REILLY", 0, oreillyId);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testContentFetcherForEachAndStreamStayFresh() {
        MapCache<Book> bookCache = new MapCache<>(ImmutableType.get(Book.class));
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> {
            Class<?> javaClass = type.getJavaClass();
            if (javaClass == Book.class) {
                return bookCache;
            }
            if (javaClass == BookStore.class) {
                return storeCache;
            }
            return null;
        });
        BookTable table = BookTable.$;
        jdbc(con -> {
            client.getEntities().forConnection(con)
                    .findByIds(Book.class, Collections.singletonList(learningGraphQLId1));
            client.getEntities().forConnection(con)
                    .findByIds(BookStore.class, Collections.singletonList(oreillyId));
        });
        bookCache.clearHistory();
        storeCache.clearHistory();
        try {
            rawUpdate("update BOOK set NAME = ? where ID = ?", "STALE-BOOK", learningGraphQLId1);
            rawUpdate("update BOOK_STORE set VERSION = ? where ID = ?", 9, oreillyId);
            List<String> forEachValues = new ArrayList<>();
            jdbc(con -> client.createQuery(table)
                    .where(table.id().eq(learningGraphQLId1))
                    .select(table.fetch(BookFetcher.$.name().store(
                            ReferenceFetchType.JOIN_ALWAYS, BookStoreFetcher.$.version())))
                    .useObjectCache(BookFetcher.$.name())
                    .forEach(con, book -> forEachValues.add(book.name() + "|" + book.store().version())));
            List<String> batchValues = new ArrayList<>();
            jdbc(con -> client.createQuery(table)
                    .where(table.id().eq(learningGraphQLId1))
                    .select(table.fetch(BookFetcher.$.name().store(
                            ReferenceFetchType.JOIN_ALWAYS, BookStoreFetcher.$.version())))
                    .useObjectCache(BookFetcher.$.name())
                    .forEach(con, 1, book -> batchValues.add(book.name() + "|" + book.store().version())));
            List<String> streamValues = new ArrayList<>();
            jdbc(con -> client.createQuery(table)
                    .where(table.id().eq(learningGraphQLId1))
                    .select(table.fetch(BookFetcher.$.name().store(
                            ReferenceFetchType.JOIN_ALWAYS, BookStoreFetcher.$.version())))
                    .useObjectCache(BookFetcher.$.name())
                    .stream(con)
                    .forEach(book -> streamValues.add(book.name() + "|" + book.store().version())));
            assertEquals(Collections.singletonList("STALE-BOOK|9"), forEachValues);
            assertEquals(Collections.singletonList("STALE-BOOK|9"), batchValues);
            assertEquals(Collections.singletonList("STALE-BOOK|9"), streamValues);
        } finally {
            rawUpdate("update BOOK set NAME = ? where ID = ?", "Learning GraphQL", learningGraphQLId1);
            rawUpdate("update BOOK_STORE set VERSION = ? where ID = ?", 0, oreillyId);
            bookCache.delete(learningGraphQLId1);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testContentFetcherCommandPurposeFallbackKeepsNestedChildFresh() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        storeCache.clearHistory();
        try {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "STALE-DB-NAME", oreillyId);
            clearExecutions();
            ConfigurableRootQuery<Table<?>, Book> query = Queries.createQuery(
                    assertInstanceOf(JSqlClientImplementor.class, client),
                    ImmutableType.get(Book.class),
                    ExecutionPurpose.command(QueryReason.NONE),
                    FilterLevel.DEFAULT,
                    (q, table) -> q
                            .where(table.get("id").eq(learningGraphQLId1))
                            .select(((Table<Book>) table).fetch(
                                    BookFetcher.$.name().store(
                                            ReferenceFetchType.SELECT,
                                            BookStoreFetcher.$.name()
                                    )
                            ))
            );
            List<Book> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    query.useObjectCache(BookFetcher.$.name().store(BookStoreFetcher.$.name())).execute(con)
            ));
            assertEquals(1, rows.size());
            assertEquals(learningGraphQLId1, rows.get(0).id());
            assertEquals("Learning GraphQL", rows.get(0).name());
            assertEquals(oreillyId, rows.get(0).store().id());
            assertEquals("STALE-DB-NAME", rows.get(0).store().name());
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testContentFetcherInapplicableMaskSafeUnderLockingDistinctAndBareRoot() {
        BookStoreTable store = BookStoreTable.$;
        List<BookStore> locked = new ArrayList<>();
        try {
            jdbc(con -> locked.addAll(
                    sqlClient.createQuery(store)
                            .where(store.id().eq(oreillyId))
                            .select(store.fetch(BookStoreFetcher.$.name()))
                            .useObjectCache(BookStoreFetcher.$.version())
                            .forUpdate()
                            .execute(con)
            ));
            assertEquals(1, locked.size());
            assertEquals("O'REILLY", locked.get(0).name());
        } catch (IllegalArgumentException expected) {
        }
        List<BookStore> distinct = new ArrayList<>();
        try {
            jdbc(con -> distinct.addAll(
                    sqlClient.createQuery(store)
                            .where(store.id().eq(oreillyId))
                            .select(store.fetch(BookStoreFetcher.$.name()))
                            .useObjectCache(BookStoreFetcher.$.version())
                            .distinct()
                            .execute(con)
            ));
            assertEquals(1, distinct.size());
            assertEquals("O'REILLY", distinct.get(0).name());
        } catch (IllegalArgumentException expected) {
        }
        BookTable book = BookTable.$;
        List<Book> bare = new ArrayList<>();
        try {
            jdbc(con -> bare.addAll(
                    sqlClient.createQuery(book)
                            .where(book.id().eq(learningGraphQLId1))
                            .select(book)
                            .useObjectCache(BookFetcher.$.name().store(BookStoreFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(1, bare.size());
            assertEquals(learningGraphQLId1, bare.get(0).id());
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testContentFetcherNestedQueryInsideFieldFilterCompletesChildFresh() {
        MapCache<BookStore> storeCache = new MapCache<>(ImmutableType.get(BookStore.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == BookStore.class ? storeCache : null);
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(BookStore.class, Collections.singletonList(oreillyId)));
        storeCache.clearHistory();
        String[] observed = {null};
        try {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "STALE-DB-NAME", oreillyId);
            BookStoreTable store = BookStoreTable.$;
            jdbc(con -> client.createQuery(store)
                    .where(store.id().eq(oreillyId))
                    .select(store.fetch(BookStoreFetcher.$.name().books(
                            BookFetcher.$.name(),
                            it -> it.filter(args -> {
                                List<Book> nested = client.createQuery(BookTable.$)
                                        .where(BookTable.$.store().id().in(args.getKeys()))
                                        .select(BookTable.$.fetch(
                                                BookFetcher.$.name().store(
                                                        ReferenceFetchType.SELECT,
                                                        BookStoreFetcher.$.name()
                                                )
                                        ))
                                        .useObjectCache(BookFetcher.$.name())
                                        .execute(con);
                                Book first = nested.isEmpty() ? null : nested.get(0);
                                observed[0] = first == null || first.store() == null ?
                                        null : first.store().name();
                            })
                    )))
                    .execute(con));
            assertEquals("STALE-DB-NAME", observed[0]);
        } finally {
            rawUpdate("update BOOK_STORE set NAME = ? where ID = ?", "O'REILLY", oreillyId);
            storeCache.delete(oreillyId);
        }
    }

    @Test
    public void testFakeFkSiblingJoinedProofDoesNotAdmitDeletedReferenceTarget() {
        MapCache<Issue1434User> userCache = new MapCache<>(ImmutableType.get(Issue1434User.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Issue1434User.class ? userCache : null);
        Issue1434MessageTable table = Issue1434MessageTable.$;
        rawUpdate("insert into ISSUE_1434_USER(ID, NAME) values(?, ?)", 2L, "user-2");
        rawUpdate("insert into ISSUE_1434_MESSAGE(ID, USER_ID) values(?, ?)", 2L, 2L);
        try {
            jdbc(con -> client.getEntities().forConnection(con)
                    .findByIds(Issue1434User.class, Arrays.asList(1L, 2L)));
            userCache.clearHistory();
            rawUpdate("delete from ISSUE_1434_USER where ID = ?", 2L);
            clearExecutions();
            JSqlClient oracle = createClient(type -> null);
            List<Tuple2<Issue1434Message, Issue1434User>> baseline = new ArrayList<>();
            jdbc(con -> baseline.addAll(
                    oracle.createQuery(table)
                            .where(table.id().in(Arrays.asList(1L, 2L)))
                            .orderBy(table.id())
                            .select(
                                    table.fetch(Issue1434MessageFetcher.$.user(
                                            ReferenceFetchType.SELECT,
                                            Issue1434UserFetcher.$.name()
                                    )),
                                    table.user(JoinType.LEFT).fetch(Issue1434UserFetcher.$.name())
                            )
                            .execute(con)
            ));
            assertEquals(2, baseline.size(), "the fresh projection must define the full page");
            assertEquals(1L, baseline.get(0).get_1().id());
            assertEquals(2L, baseline.get(1).get_1().id());
            assertNull(baseline.get(1).get_1().user(), "the deleted SELECT target is NULL on the fresh baseline");
            assertNull(baseline.get(1).get_2(), "the deleted LEFT-joined slot is NULL on the fresh baseline");
            clearExecutions();
            List<Tuple2<Issue1434Message, Issue1434User>> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(Arrays.asList(1L, 2L)))
                            .orderBy(table.id())
                            .select(
                                    table.fetch(Issue1434MessageFetcher.$.user(
                                            ReferenceFetchType.SELECT,
                                            Issue1434UserFetcher.$.name()
                                    )),
                                    table.user(JoinType.LEFT).fetch(Issue1434UserFetcher.$.name())
                            )
                            .useObjectCache(Issue1434MessageFetcher.$.user(Issue1434UserFetcher.$.name()))
                            .execute(con)
            ));
            assertEquals(2, rows.size(), "the full page and its order must be preserved");
            assertEquals(1L, rows.get(0).get_1().id());
            assertEquals(2L, rows.get(1).get_1().id());
            assertNotNull(rows.get(0).get_2(), "the live sibling child must stay present");
            assertEquals("user-1", rows.get(0).get_2().name());
            assertNull(
                    rows.get(1).get_1().user(),
                    "a deleted FAKE-FK target must not be admitted for a non-proving id"
            );
            assertNull(rows.get(1).get_2(), "the deleted joined slot must stay NULL");
        } finally {
            rawUpdate("delete from ISSUE_1434_MESSAGE where ID = ?", 2L);
            rawUpdate("delete from ISSUE_1434_USER where ID = ?", 2L);
            userCache.delete(1L);
            userCache.delete(2L);
        }
    }

    @Test
    public void testRealFkSingleTableSubtypeChangeDoesNotAdmitStaleTarget() {
        MapCache<Organization> organizationCache = new MapCache<>(ImmutableType.get(Organization.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Organization.class ? organizationCache : null);
        JSqlClient oracleClient = createClient(type -> null);
        OrganizationProjectTable table = OrganizationProjectTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Organization.class, Collections.singletonList(100L)));
        organizationCache.clearHistory();
        try {
            rawUpdate(
                    "update CLIENT set CLIENT_TYPE = ?, FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Person", "Changed", "Person", 100L
            );
            List<OrganizationProject> fresh = new ArrayList<>();
            jdbc(con -> fresh.addAll(
                    oracleClient.createQuery(table)
                            .where(table.id().eq(1001L))
                            .select(table.fetch(OrganizationProjectFetcher.$.organization(
                                    OrganizationFetcher.$.name()
                            )))
                            .execute(con)
            ));
            assertEquals(1, fresh.size());
            assertNull(fresh.get(0).organization(), "the fresh row is no longer an Organization");
            clearExecutions();
            List<OrganizationProject> hinted = new ArrayList<>();
            jdbc(con -> hinted.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(1001L))
                            .select(table.fetch(OrganizationProjectFetcher.$.organization(
                                    OrganizationFetcher.$.name()
                            )))
                            .useObjectCache(OrganizationProjectFetcher.$.organization(
                                    OrganizationFetcher.$.name()
                            ))
                            .execute(con)
            ));
            assertEquals(1, hinted.size());
            assertNull(
                    hinted.get(0).organization(),
                    "a warm Organization must not be admitted for a changed single-table subtype"
            );
        } finally {
            rawUpdate(
                    "update CLIENT set CLIENT_TYPE = ?, FIRST_NAME = null, LAST_NAME = null where ID = ?",
                    "ORG", 100L
            );
            organizationCache.delete(100L);
        }
    }

    @Test
    public void testNullableAncestorRequiredEmbeddedGrandchildPresenceStaysSqlAuthoritative() {
        MapCache<Transform> transformCache = new MapCache<>(ImmutableType.get(Transform.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Transform.class ? transformCache : null);
        TransformTable table = TransformTable.$;
        Fetcher<Transform> content = TransformFetcher.$.target(
                RectFetcher.$.leftTop(PointFetcher.$.x().y())
        );
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Transform.class, Arrays.asList(1L, 2L)));
        transformCache.clearHistory();
        // Warm a present target for the row whose committed target columns are all NULL.
        transformCache.put(2L, TransformDraft.$.produce(draft -> {
            draft.setId(2L);
            draft.setTarget(RectDraft.$.produce(rect -> rect.setLeftTop(
                    PointDraft.$.produce(point -> {
                        point.setX(800);
                        point.setY(600);
                    })
            )));
        }));
        try {
            clearExecutions();
            List<Transform> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(Arrays.asList(1L, 2L)))
                            .orderBy(table.id())
                            .select(table.fetch(content))
                            .useObjectCache(content)
                            .execute(con)
            ));
            assertEquals(2, rows.size());
            assertNotNull(rows.get(0).target(), "a present target must not be zeroed out");
            assertNotNull(rows.get(0).target().leftTop(), "the required grandchild must stay present");
            assertNull(rows.get(1).target(), "a cached present target must not hide current absence");
        } finally {
            transformCache.delete(1L);
            transformCache.delete(2L);
        }
    }

    @Test
    public void testContentFetcherExplicitDependencyVisibilityAcrossFormulaMasks() {
        MapCache<Author> authorCache = new MapCache<>(ImmutableType.get(Author.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == Author.class ? authorCache : null);
        AuthorTable table = AuthorTable.$;
        jdbc(con -> client.getEntities().forConnection(con)
                .findByIds(Author.class, Collections.singletonList(alexId)));
        authorCache.clearHistory();
        try {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Zed", "Zulu", alexId
            );
            List<Author> approved = new ArrayList<>();
            jdbc(con -> approved.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(alexId))
                            .select(table.fetch(AuthorFetcher.$.firstName().fullName()))
                            .useObjectCache(AuthorFetcher.$.fullName())
                            .execute(con)
            ));
            assertEquals(1, approved.size());
            assertEquals("Alex Banks", approved.get(0).fullName());
            ImmutableSpi approvedSpi = assertInstanceOf(ImmutableSpi.class, approved.get(0));
            assertTrue(approvedSpi.__isVisible("firstName"), "explicitly selected dependency must stay visible");
            assertTrue(approvedSpi.__isVisible("fullName"), "the approved formula must stay visible");
            assertFalse(approvedSpi.__isVisible("lastName"), "an implicit formula dependency stays hidden");
            clearExecutions();
            List<Author> unapproved = new ArrayList<>();
            jdbc(con -> unapproved.addAll(
                    client.createQuery(table)
                            .where(table.id().eq(alexId))
                            .select(table.fetch(AuthorFetcher.$.firstName().fullName()))
                            .useObjectCache(AuthorFetcher.$.firstName())
                            .execute(con)
            ));
            assertEquals(1, unapproved.size());
            assertEquals("Zed Zulu", unapproved.get(0).fullName());
            assertEquals("Zed", unapproved.get(0).firstName());
            ImmutableSpi unapprovedSpi = assertInstanceOf(ImmutableSpi.class, unapproved.get(0));
            assertTrue(unapprovedSpi.__isVisible("firstName"), "explicitly selected dependency must stay visible");
            assertTrue(unapprovedSpi.__isVisible("fullName"), "the unapproved formula must stay visible");
        } finally {
            rawUpdate(
                    "update AUTHOR set FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                    "Alex", "Banks", alexId
            );
            authorCache.delete(alexId);
        }
    }

    @Test
    public void testContentFetcherRecursiveParentKeepsAncestorNamesFresh() {
        MapCache<TreeNode> nodeCache = new MapCache<>(ImmutableType.get(TreeNode.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == TreeNode.class ? nodeCache : null);
        TreeNodeTable table = TreeNodeTable.$;
        List<Long> ids = Arrays.asList(4L, 7L);
        List<Long> warmIds = Arrays.asList(1L, 2L, 3L, 4L, 6L, 7L);
        jdbc(con -> client.getEntities().forConnection(con).findByIds(TreeNode.class, warmIds));
        nodeCache.clearHistory();
        JSqlClient oracle = createClient(type -> null);
        try {
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-1", 1L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-2", 2L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-3", 3L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-4", 4L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-6", 6L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-7", 7L);
            clearExecutions();
            List<TreeNode> fresh = new ArrayList<>();
            jdbc(con -> fresh.addAll(
                    oracle.createQuery(table)
                            .where(table.id().in(ids))
                            .orderBy(table.id())
                            .select(table.fetch(TreeNodeFetcher.$.name().recursiveParent()))
                            .execute(con)
            ));
            assertEquals(2, fresh.size(), "the ordinary recursive projection defines the membership");
            assertEquals(4L, fresh.get(0).id());
            assertEquals("F-4", fresh.get(0).name());
            assertEquals("F-3", fresh.get(0).parent().name());
            assertEquals("F-2", fresh.get(0).parent().parent().name());
            assertEquals("F-1", fresh.get(0).parent().parent().parent().name());
            clearExecutions();
            List<TreeNode> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(ids))
                            .orderBy(table.id())
                            .select(table.fetch(TreeNodeFetcher.$.name().recursiveParent()))
                            .useObjectCache(TreeNodeFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(2, rows.size(), "the hinted page must keep the recursive membership");
            TreeNode coca = rows.get(0);
            assertEquals(4L, coca.id());
            assertTrue(
                    "Coca Cola".equals(coca.name()) || "F-4".equals(coca.name()),
                    "the approved root name must be a valid cached or fresh value: " + coca.name()
            );
            assertEquals(3L, coca.parent().id());
            assertEquals("F-3", coca.parent().name());
            assertEquals(2L, coca.parent().parent().id());
            assertEquals("F-2", coca.parent().parent().name());
            assertEquals(1L, coca.parent().parent().parent().id());
            assertEquals("F-1", coca.parent().parent().parent().name());
            assertNull(coca.parent().parent().parent().parent());
            TreeNode baguette = rows.get(1);
            assertEquals(7L, baguette.id());
            assertTrue(
                    "Baguette".equals(baguette.name()) || "F-7".equals(baguette.name()),
                    "the approved root name must be a valid cached or fresh value: " + baguette.name()
            );
            assertEquals(6L, baguette.parent().id());
            assertEquals("F-6", baguette.parent().name());
            assertEquals(2L, baguette.parent().parent().id());
            assertEquals("F-2", baguette.parent().parent().name());
            assertEquals(1L, baguette.parent().parent().parent().id());
            assertEquals("F-1", baguette.parent().parent().parent().name());
            assertNull(baguette.parent().parent().parent().parent());
        } finally {
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Home", 1L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Food", 2L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Drinks", 3L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Coca Cola", 4L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Bread", 6L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Baguette", 7L);
            for (Long id : warmIds) {
                nodeCache.delete(id);
            }
        }
    }

    @Test
    public void testContentFetcherRecursiveChildNodesKeepDescendantNamesFresh() {
        MapCache<TreeNode> nodeCache = new MapCache<>(ImmutableType.get(TreeNode.class));
        JSqlClient client = createClient(type -> type.getJavaClass() == TreeNode.class ? nodeCache : null);
        TreeNodeTable table = TreeNodeTable.$;
        List<Long> ids = Collections.singletonList(2L);
        List<Long> warmIds = Arrays.asList(2L, 3L, 4L, 5L, 6L, 7L, 8L);
        jdbc(con -> client.getEntities().forConnection(con).findByIds(TreeNode.class, warmIds));
        nodeCache.clearHistory();
        JSqlClient oracle = createClient(type -> null);
        try {
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-2", 2L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-3", 3L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-4", 4L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-5", 5L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-6", 6L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-7", 7L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "F-8", 8L);
            clearExecutions();
            List<TreeNode> fresh = new ArrayList<>();
            jdbc(con -> fresh.addAll(
                    oracle.createQuery(table)
                            .where(table.id().in(ids))
                            .select(table.fetch(TreeNodeFetcher.$.name().recursiveChildNodes()))
                            .execute(con)
            ));
            assertEquals(1, fresh.size(), "the ordinary recursive projection defines the membership");
            assertEquals("F-2", fresh.get(0).name());
            assertEquals(2, fresh.get(0).childNodes().size());
            clearExecutions();
            List<TreeNode> rows = new ArrayList<>();
            jdbc(con -> rows.addAll(
                    client.createQuery(table)
                            .where(table.id().in(ids))
                            .select(table.fetch(TreeNodeFetcher.$.name().recursiveChildNodes()))
                            .useObjectCache(TreeNodeFetcher.$.name())
                            .execute(con)
            ));
            assertEquals(1, rows.size(), "the hinted page must keep the recursive membership");
            TreeNode food = rows.get(0);
            assertEquals(2L, food.id());
            assertTrue(
                    "Food".equals(food.name()) || "F-2".equals(food.name()),
                    "the approved root name must be a valid cached or fresh value: " + food.name()
            );
            List<TreeNode> children = food.childNodes();
            assertEquals(2, children.size(), "childNodes ordered by id");
            TreeNode drinks = children.get(0);
            assertEquals(3L, drinks.id());
            assertEquals("F-3", drinks.name());
            TreeNode bread = children.get(1);
            assertEquals(6L, bread.id());
            assertEquals("F-6", bread.name());
            List<TreeNode> drinksChildren = drinks.childNodes();
            assertEquals(2, drinksChildren.size());
            assertEquals(4L, drinksChildren.get(0).id());
            assertEquals("F-4", drinksChildren.get(0).name());
            assertEquals(5L, drinksChildren.get(1).id());
            assertEquals("F-5", drinksChildren.get(1).name());
            List<TreeNode> breadChildren = bread.childNodes();
            assertEquals(2, breadChildren.size());
            assertEquals(7L, breadChildren.get(0).id());
            assertEquals("F-7", breadChildren.get(0).name());
            assertEquals(8L, breadChildren.get(1).id());
            assertEquals("F-8", breadChildren.get(1).name());
            assertTrue(drinksChildren.get(0).childNodes().isEmpty(), "the leaf level must be terminal");
        } finally {
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Food", 2L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Drinks", 3L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Coca Cola", 4L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Fanta", 5L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Bread", 6L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Baguette", 7L);
            rawUpdate("update TREE_NODE set NAME = ? where NODE_ID = ?", "Ciabatta", 8L);
            for (Long id : warmIds) {
                nodeCache.delete(id);
            }
        }
    }

    private static void rawUpdate(String sql, Object... args) {
        jdbc(null, false, con -> {
            try (PreparedStatement st = con.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    st.setObject(i + 1, args[i]);
                }
                st.executeUpdate();
            }
        });
    }

    private static void rawUpdate(DataSource dataSource, String sql, Object... args) {
        jdbc(dataSource, false, con -> {
            try (PreparedStatement st = con.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    st.setObject(i + 1, args[i]);
                }
                st.executeUpdate();
            }
        });
    }

    private static void assertCacheTouched(MapCache<?> cache, Object key) {
        for (Collection<Object> keys : cache.getAllKeys) {
            if (keys.contains(key)) {
                return;
            }
        }
        assertTrue(false, "the object cache must be consulted for " + key);
    }

    /** Asserts rows match on concrete type, id/name and every property's loaded state. */
    private static void assertSameLoadedShape(List<Client> expected, List<Client> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            Client expectedRow = expected.get(i);
            Client actualRow = actual.get(i);
            ImmutableSpi expectedSpi = assertInstanceOf(ImmutableSpi.class, expectedRow);
            ImmutableSpi actualSpi = assertInstanceOf(ImmutableSpi.class, actualRow);
            assertEquals(expectedSpi.__type(), actualSpi.__type());
            for (ImmutableProp prop : expectedSpi.__type().getProps().values()) {
                assertEquals(
                        expectedSpi.__isLoaded(prop.getId()),
                        actualSpi.__isLoaded(prop.getId()),
                        "load state of \"" + prop + "\""
                );
            }
            assertEquals(expectedRow.id(), actualRow.id());
            assertEquals(expectedRow.name(), actualRow.name());
        }
    }

    private static Book hintedBook(JSqlClient client, BookTable table) {
        List<Book> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                client.createQuery(table)
                        .where(table.id().eq(learningGraphQLId1))
                        .select(table.fetch(BookFetcher.$.name().store(BookStoreFetcher.$.name())))
                        .useObjectCache(BookFetcher.$.store(BookStoreFetcher.$.name()))
                        .execute(con)
        ));
        return rows.get(0);
    }

    private static TreeNode2 hintedNode(
            JSqlClient client,
            TreeNode2Table table,
            Fetcher<?> cachedContent
    ) {
        List<TreeNode2> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                client.createQuery(table)
                        .where(table.id().eq(2L))
                        .select(table.fetch(TreeNode2Fetcher.$.name().parent(TreeNode2Fetcher.$.name())))
                        .useObjectCache(cachedContent)
                        .execute(con)
        ));
        return rows.get(0);
    }

    @Test
    public void testConfiguredContentFieldsUseDtoProjectionAndKeepScalarSlotsFresh() {
        ImmutableType type = ImmutableType.get(BookStore.class);
        MapCache<BookStore> cache = new MapCache<>(type);
        JSqlClient client = createClient(it -> it == type ? cache : null,
                type, null, null, Collections.singletonList("name"));
        cache.put(oreillyId, BookStoreDraft.$.produce(draft -> {
            draft.setId(oreillyId);
            draft.setName("CACHED");
            draft.setWebsite("CACHED-WEBSITE");
            draft.setVersion(777);
        }));
        BookStoreTable table = BookStoreTable.$;
        ConfigurableRootQuery<BookStoreTable, Tuple3<ReusableBookStoreView, Integer, String>> query =
                client.createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(ReusableBookStoreView.class), table.version(), table.website())
                        .useObjectCache(BookStoreFetcher.$.website())
                        .useObjectCache();
        clearExecutions();
        List<Tuple3<ReusableBookStoreView, Integer, String>> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(query.execute(con)));
        assertEquals("CACHED", rows.get(0).get_1().getName());
        assertEquals(0, rows.get(0).get_2());
        assertNull(rows.get(0).get_3());
        assertCacheTouched(cache, oreillyId);
        assertEquals(1, getExecutions().size());
        assertFalse(getExecutions().get(0).getSql().contains(".NAME"));
        assertTrue(getExecutions().get(0).getSql().contains(".VERSION"));

        cache.clearHistory();
        rows.clear();
        jdbc(con -> rows.addAll(query.useObjectCache(false).execute(con)));
        assertEquals("O'REILLY", rows.get(0).get_1().getName());
        assertTrue(cache.getAllKeys.isEmpty());
        jdbc(con -> assertEquals("O'REILLY", client.getEntities().forConnection(con)
                .findById(ReusableBookStoreView.class, oreillyId).getName()));
        assertTrue(cache.getAllKeys.isEmpty());
    }

    @Test
    public void testConfiguredContentFieldsUsePolymorphicDtoBranches() {
        ImmutableType type = ImmutableType.get(Client.class);
        MapCache<Client> cache = new MapCache<>(type);
        JSqlClient client = createClient(it -> it == type ? cache : null,
                type, null, null, Collections.singletonList("taxCode"));
        cache.put(100L, OrganizationDraft.$.produce(draft -> {
            draft.setId(100L);
            draft.setName("CACHED-NAME");
            draft.setTaxCode("CACHED-TAX");
        }));
        ClientTable table = ClientTable.$;
        List<ClientImplicitCatchAllView> rows = new ArrayList<>();
        clearExecutions();
        jdbc(con -> rows.addAll(client.createQuery(table)
                .where(table.id().in(CLIENT_IDS))
                .orderBy(table.id())
                .select(table.fetch(ClientImplicitCatchAllView.class))
                .useObjectCache()
                .execute(con)));
        ClientImplicitCatchAllView.Organization organization =
                assertInstanceOf(ClientImplicitCatchAllView.Organization.class, rows.get(0));
        assertEquals("Acme", organization.getName());
        assertEquals("CACHED-TAX", organization.getTaxCode());
        assertEquals("Bob", assertInstanceOf(ClientImplicitCatchAllView.Default.class, rows.get(1)).getName());
        assertCacheTouched(cache, 100L);
        assertFalse(cache.getAllKeys.stream().anyMatch(keys -> keys.contains(101L)));
        assertEquals(1, getExecutions().size());
        assertFalse(getExecutions().get(0).getSql().contains(".TAX_CODE"));
        assertTrue(getExecutions().get(0).getSql().contains(".NAME"));
        assertNotNull(client.getCaches().getObjectCacheContentFetcher(ImmutableType.get(Organization.class)));
    }

    @Test
    public void testConfiguredContentFieldsRejectUnknownOrProtectedProperties() {
        ImmutableType type = ImmutableType.get(BookStore.class);
        for (String name : Arrays.asList("missing", "id", "version", "books", "avgPrice")) {
            assertThrows(IllegalArgumentException.class, () -> createClient(
                    it -> it == type ? new MapCache<>(type) : null,
                    type, null, null, Collections.singletonList(name)), name);
        }
        assertThrows(IllegalArgumentException.class, () -> getSqlClient(builder ->
                builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                    @Override
                    public Collection<String> getObjectCacheContentFields(ImmutableType cacheType) {
                        return Collections.singletonList("name");
                    }

                    @Override
                    public Cache<?, ?> createObjectCache(ImmutableType cacheType) {
                        return cacheType == type ? new MapCache<>(type) : null;
                    }
                }))));
    }

    private JSqlClient createClient(Function<ImmutableType, Cache<?, ?>> objectCacheFactory) {
        return createClient(objectCacheFactory, null);
    }

    private JSqlClient createClient(
            Function<ImmutableType, Cache<?, ?>> objectCacheFactory,
            ImmutableType contentOnlyType
    ) {
        return createClient(objectCacheFactory, contentOnlyType, null, null);
    }

    private JSqlClient createClient(
            Function<ImmutableType, Cache<?, ?>> objectCacheFactory,
            ImmutableType contentOnlyType,
            ImmutableProp cachedProp,
            Cache<?, ?> propCache
    ) {
        return createClient(objectCacheFactory, contentOnlyType, cachedProp, propCache, Collections.emptyList());
    }

    private JSqlClient createClient(
            Function<ImmutableType, Cache<?, ?>> objectCacheFactory,
            ImmutableType contentOnlyType,
            ImmutableProp cachedProp,
            Cache<?, ?> propCache,
            Collection<String> contentFields
    ) {
        return getSqlClient(builder -> {
            builder.setConnectionManager(NON_TX_MANAGER);
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public boolean isObjectCacheContentOnly(ImmutableType type) {
                    return type == contentOnlyType;
                }

                @Override
                public Collection<String> getObjectCacheContentFields(ImmutableType type) {
                    return type == contentOnlyType ? contentFields : Collections.emptyList();
                }

                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return objectCacheFactory.apply(type);
                }

                @Override
                public Cache<?, ?> createAssociatedIdCache(ImmutableProp prop) {
                    return prop == cachedProp ? propCache : null;
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

        /** Drops recorded {@code getAll} history so a later observation excludes warm reads. */
        void clearHistory() {
            getAllKeys.clear();
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
