package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.runtime.DraftSpi;
import org.babyfish.jimmer.runtime.ImmutableSpi;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.impl.query.FilterLevel;
import org.babyfish.jimmer.sql.ast.impl.query.Queries;
import org.babyfish.jimmer.sql.ast.mutation.QueryReason;
import org.babyfish.jimmer.sql.ast.query.ConfigurableRootQuery;
import org.babyfish.jimmer.sql.ast.table.Table;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.common.AbstractTest;
import org.babyfish.jimmer.sql.common.CacheImpl;
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType;
import org.babyfish.jimmer.sql.model.*;
import org.babyfish.jimmer.sql.model.dto.ReusableBookStoreView;
import org.babyfish.jimmer.sql.model.inheritance.joinedtable.*;
import org.babyfish.jimmer.sql.model.inheritance.joinedtable.Organization;
import org.babyfish.jimmer.sql.model.issue1252.TreeNode2;
import org.babyfish.jimmer.sql.model.issue1252.TreeNode2Fetcher;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.babyfish.jimmer.sql.common.Constants.*;

public class ObjectCacheTest extends AbstractQueryTest {

    private JSqlClient sqlClient;

    @BeforeEach
    public void initialize() {
        sqlClient = newClient(NON_TX_MANAGER);
    }

    private JSqlClient newClient(ConnectionManager connectionManager) {
        return newClient(connectionManager, false);
    }

    private JSqlClient newClient(ConnectionManager connectionManager, boolean contentOnly) {
        return getSqlClient(builder -> {
            builder.setConnectionManager(connectionManager);
            builder.setCaches(cfg ->
                    cfg.setCacheFactory(
                            new CacheFactory() {

                                @Override
                                public boolean isObjectCacheContentOnly(ImmutableType type) {
                                    return contentOnly && type == ImmutableType.get(BookStore.class);
                                }

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

                                @Override
                                public Cache<?, ?> createResolverCache(@NotNull ImmutableProp prop) {
                                    return new CacheImpl<>(prop);
                                }
                            }
                    )
            );
        });
    }

    @Test
    public void testObject() {
        for (int i = 0; i < 2; i++) {
            boolean useSql = i == 0;
            connectAndExpect(
                    con -> {
                        return sqlClient
                                .getEntities()
                                .forConnection(con)
                                .findById(BookStore.class, oreillyId);
                    }, ctx -> {
                        if (useSql) {
                            ctx.sql(
                                    "select tb_1_.ID, tb_1_.NAME, tb_1_.WEBSITE, tb_1_.VERSION " +
                                            "from BOOK_STORE tb_1_ " +
                                            "where tb_1_.ID = ?"
                            );
                            ctx.variables(oreillyId);
                        }
                        ctx.rows(
                                "[" +
                                        "--->{" +
                                        "--->--->\"id\":\"d38c10da-6be8-4924-b9b9-5e81899612a0\"," +
                                        "--->--->\"name\":\"O'REILLY\"," +
                                        "--->--->\"website\":null," +
                                        "--->--->\"version\":0" +
                                        "--->}" +
                                        "]"
                        );
                    }
            );
        }
    }

    @Test
    public void testDto() {
        for (int i = 0; i < 2; i++) {
            boolean useSql = i == 0;
            connectAndExpect(
                    con -> {
                        ReusableBookStoreView view = sqlClient
                                .getEntities()
                                .forConnection(con)
                                .findById(ReusableBookStoreView.class, oreillyId);
                        Assertions.assertNotNull(view);
                        Assertions.assertEquals(oreillyId, view.getId());
                        Assertions.assertEquals("O'REILLY", view.getName());
                        return view;
                    }, ctx -> {
                        if (useSql) {
                            ctx.sql(
                                    "select tb_1_.ID, tb_1_.NAME, tb_1_.WEBSITE, tb_1_.VERSION " +
                                            "from BOOK_STORE tb_1_ " +
                                            "where tb_1_.ID = ?"
                            );
                            ctx.variables(oreillyId);
                        }
                    }
            );
        }
    }

    @Test
    public void testCalculatedAssociation() {
        BookStoreTable table = BookStoreTable.$;
        for (int i = 0; i < 2; i++) {
            boolean useSql = i == 0;
            executeAndExpect(
                    sqlClient
                            .createQuery(table)
                            .select(
                                    table.fetch(
                                            BookStoreFetcher.$
                                                    .allScalarFields()
                                                    .newestBooks(
                                                            BookFetcher.$
                                                                    .allScalarFields()
                                                    )
                                    )
                            ),
                    ctx -> {
                        ctx.sql("select tb_1_.ID, tb_1_.NAME, tb_1_.WEBSITE, tb_1_.VERSION from BOOK_STORE tb_1_");
                        if (useSql) {
                            ctx.statement(1).sql("select tb_1_.ID, tb_2_.ID from BOOK_STORE tb_1_ inner join BOOK tb_2_ on tb_1_.ID = tb_2_.STORE_ID where (tb_2_.NAME, tb_2_.EDITION) in (select tb_3_.NAME, max(tb_3_.EDITION) from BOOK tb_3_ where tb_3_.STORE_ID in (?, ?) group by tb_3_.NAME)");
                            ctx.statement(2).sql("select tb_1_.ID, tb_1_.NAME, tb_1_.EDITION, tb_1_.PRICE, tb_1_.STORE_ID from BOOK tb_1_ where tb_1_.ID in (?, ?, ?, ?)");
                        }
                    }
            );
        }
    }

    @Test
    public void testIssue1221ByObject() {
        for (int i = 0; i < 2; i++) {
            boolean useSql = i == 0;
            connectAndExpect(
                    con -> {
                        return sqlClient
                                .getEntities()
                                .forConnection(con)
                                .findById(
                                        AuthorFetcher.$.fullName(),
                                        alexId
                                );
                    }, ctx -> {
                        if (useSql) {
                            ctx.sql(
                                    "select tb_1_.ID, " +
                                            "tb_1_.FIRST_NAME, " +
                                            "tb_1_.LAST_NAME, " +
                                            "tb_1_.GENDER, " +
                                            "length(tb_1_.FIRST_NAME) + length(tb_1_.LAST_NAME), " +
                                            "concat(tb_1_.FIRST_NAME, ' ', tb_1_.LAST_NAME) " +
                                            "from AUTHOR tb_1_ where tb_1_.ID = ?"
                            );
                        }
                        ctx.rows(
                                "[{" +
                                        "--->\"id\":\"1e93da94-af84-44f4-82d1-d8a9fd52ea94\"," +
                                        "--->\"fullName\":\"Alex Banks\"" +
                                        "}]"
                        );
                    }
            );
        }
    }

    @Test
    public void testIssue1221ByAssociation() {
        for (int i = 0; i < 2; i++) {
            boolean useSql = i == 0;
            connectAndExpect(
                    con -> {
                        return sqlClient
                                .getEntities()
                                .forConnection(con)
                                .findById(
                                        BookFetcher.$.storeId(),
                                        graphQLInActionId3
                                );
                    }, ctx -> {
                        if (useSql) {
                            ctx.sql(
                                    "select tb_1_.ID, tb_1_.NAME, tb_1_.EDITION, tb_1_.PRICE, tb_1_.STORE_ID " +
                                            "from BOOK tb_1_ " +
                                            "where tb_1_.ID = ?"
                            );
                        }
                        ctx.rows(
                                "[{" +
                                        "--->\"id\":\"780bdf07-05af-48bf-9be9-f8c65236fecc\"," +
                                        "--->\"storeId\":\"2fa3955e-3e83-49b9-902e-0465c109c779\"" +
                                        "}]"
                        );
                    }
            );
        }
    }

    @Test
    public void testIssue1252() {
        connectAndExpect(
                con -> {
                    return sqlClient
                            .getEntities()
                            .forConnection(con)
                            .findById(
                                    TreeNode2Fetcher.$.name()
                                            .childNodes(
                                                    TreeNode2Fetcher.$
                                                            .name()
                                            ),
                                    1L
                            );
                },
                ctx -> {
                    ctx.sql(
                            "select tb_1_.NODE_ID, tb_1_.NAME, tb_1_.PARENT_ID " +
                                    "from TREE_NODE_2 tb_1_ " +
                                    "where tb_1_.NODE_ID = ?"
                    );
                    ctx.statement(1).sql(
                            "select tb_1_.NODE_ID " +
                                    "from TREE_NODE_2 tb_1_ " +
                                    "where tb_1_.PARENT_ID = ? " +
                                    "order by tb_1_.NAME asc"
                    );
                    ctx.statement(2).sql(
                            "select tb_1_.NODE_ID, tb_1_.NAME, tb_1_.PARENT_ID " +
                                    "from TREE_NODE_2 tb_1_ " +
                                    "where tb_1_.NODE_ID in (?, ?)"
                    );
                    ctx.rows(
                            "[{" +
                                    "--->\"id\":1," +
                                    "--->\"name\":\"Home\"," +
                                    "--->\"childNodes\":[" +
                                    "--->--->{\"id\":9,\"name\":\"Clothing\"}," +
                                    "--->--->{\"id\":2,\"name\":\"Food\"}]" +
                                    "}]"
                    );
                }
        );
        connectAndExpect(
                con -> {
                    updateTreeNodeName(con, "clothing");
                    sqlClient.getCaches().getObjectCache(TreeNode2.class).delete(9L);
                    return sqlClient
                            .getEntities()
                            .forConnection(con)
                            .findById(
                                    TreeNode2Fetcher.$.name()
                                            .childNodes(
                                                    TreeNode2Fetcher.$
                                                            .name()
                                            ),
                                    1L
                            );
                },
                ctx -> {
                    ctx.sql(
                            "select tb_1_.NODE_ID, tb_1_.NAME, tb_1_.PARENT_ID " +
                                    "from TREE_NODE_2 tb_1_ " +
                                    "where tb_1_.NODE_ID = ?"
                    );
                    ctx.rows(
                            "[{" +
                                    "--->\"id\":1," +
                                    "--->\"name\":\"Home\"," +
                                    "--->\"childNodes\":[" +
                                    "--->--->{\"id\":2,\"name\":\"Food\"}," +
                                    "--->--->{\"id\":9,\"name\":\"clothing\"}]" +
                                    "}]"
                    );
                }
        );
    }

    private static void updateTreeNodeName(Connection con, String name) {
        try (PreparedStatement stmt = con.prepareStatement(
                "update tree_node set name = ? where node_id = ?"
        )) {
            stmt.setString(1, name);
            stmt.setLong(2, 9L);
            stmt.executeUpdate();
        } catch (SQLException ex) {
            Assertions.fail("SQL error", ex);
        }
    }

    @Test
    public void testPolymorphicRootFetcher() {
        for (int i = 0; i < 2; i++) {
            final boolean useSql = i == 0;
            connectAndExpect(con -> {
                List<Client> clients = sqlClient
                        .getEntities()
                        .forConnection(con)
                        .findByIds(
                                ClientFetcher.$
                                        .name()
                                        .forType(OrganizationFetcher.$.taxCode())
                                        .forType(PersonFetcher.$.firstName()),
                                Arrays.asList(200L, 201L)
                        );
                Assertions.assertEquals(2, clients.size());
                Organization organization = (Organization) clients.get(0);
                Assertions.assertEquals("Globex", organization.name());
                Assertions.assertEquals("GLOBEX-001", organization.taxCode());
                Assertions.assertFalse(organization instanceof DraftSpi);
                Person person = (Person) clients.get(1);
                Assertions.assertEquals("Alice", person.name());
                Assertions.assertEquals("Alice", person.firstName());
                Assertions.assertFalse(person instanceof DraftSpi);
                return clients;
            }, ctx -> {
                if (useSql) {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.CLIENT_TYPE, tb_1_.NAME, tb_1_.DESCRIPTION, " +
                                    "tb_2_.TAX_CODE, tb_2_.STATUS, tb_3_.FIRST_NAME, tb_3_.LAST_NAME " +
                                    "from JOINED_CLIENT tb_1_ " +
                                    "left join JOINED_ORGANIZATION tb_2_ " +
                                    "on tb_1_.ID = tb_2_.ID and tb_1_.CLIENT_TYPE = ? " +
                                    "left join JOINED_PERSON tb_3_ " +
                                    "on tb_1_.ID = tb_3_.ID and tb_1_.CLIENT_TYPE = ? " +
                                    "where tb_1_.ID in (?, ?)"
                    );
                }
                ctx.rows(
                        "[" +
                                "--->{\"id\":200,\"name\":\"Globex\",\"taxCode\":\"GLOBEX-001\"}," +
                                "--->{\"id\":201,\"name\":\"Alice\",\"firstName\":\"Alice\"}" +
                                "]"
                );
            });
        }
        connectAndExpect(con -> {
            List<Client> clients = sqlClient
                    .getEntities()
                    .forConnection(con)
                    .findByIds(Client.class, Arrays.asList(200L, 201L));
            Assertions.assertEquals(Organization.class, ((ImmutableSpi) clients.get(0)).__type().getJavaClass());
            Assertions.assertEquals(Person.class, ((ImmutableSpi) clients.get(1)).__type().getJavaClass());
            assertLoadState(clients.get(0), "type", "id", "name", "description");
            assertLoadState(clients.get(1), "type", "id", "name", "description");
            return clients;
        }, ctx -> ctx.rows(
                "[" +
                        "--->{\"type\":\"ORG\",\"id\":200,\"name\":\"Globex\",\"description\":\"DEFAULT_CLIENT_DESCRIPTION\"}," +
                        "--->{\"type\":\"Person\",\"id\":201,\"name\":\"Alice\",\"description\":\"DEFAULT_CLIENT_DESCRIPTION\"}" +
                        "]"
        ));
    }

    @Test
    public void testMixedPolymorphicObjectCacheHitAndMiss() {
        connectAndExpect(con -> {
            Client cachedClient = sqlClient
                    .getEntities()
                    .forConnection(con)
                    .findById(Client.class, 200L);
            Assertions.assertInstanceOf(Organization.class, cachedClient);
            assertLoadState(cachedClient, "type", "id", "name", "description");
            List<Client> clients = sqlClient
                    .getEntities()
                    .forConnection(con)
                    .findByIds(Client.class, Arrays.asList(200L, 202L));
            Assertions.assertEquals(2, clients.size());
            for (Client client : clients) {
                Assertions.assertInstanceOf(Organization.class, client);
            }
            assertLoadState(clients, "type", "id", "name", "description");
            return clients;
        }, ctx -> {
            ctx.sql(
                    "select tb_1_.ID, tb_1_.CLIENT_TYPE, tb_1_.NAME, tb_1_.DESCRIPTION, " +
                            "tb_2_.TAX_CODE, tb_2_.STATUS, tb_3_.FIRST_NAME, tb_3_.LAST_NAME " +
                            "from JOINED_CLIENT tb_1_ " +
                            "left join JOINED_ORGANIZATION tb_2_ " +
                            "on tb_1_.ID = tb_2_.ID and tb_1_.CLIENT_TYPE = ? " +
                            "left join JOINED_PERSON tb_3_ " +
                            "on tb_1_.ID = tb_3_.ID and tb_1_.CLIENT_TYPE = ? " +
                            "where tb_1_.ID = ?"
            );
            ctx.variables("ORG", "Person", 200L);
            ctx.statement(1).sql(
                    "select tb_1_.ID, tb_1_.CLIENT_TYPE, tb_1_.NAME, tb_1_.DESCRIPTION, " +
                            "tb_2_.TAX_CODE, tb_2_.STATUS, tb_3_.FIRST_NAME, tb_3_.LAST_NAME " +
                            "from JOINED_CLIENT tb_1_ " +
                            "left join JOINED_ORGANIZATION tb_2_ " +
                            "on tb_1_.ID = tb_2_.ID and tb_1_.CLIENT_TYPE = ? " +
                            "left join JOINED_PERSON tb_3_ " +
                            "on tb_1_.ID = tb_3_.ID and tb_1_.CLIENT_TYPE = ? " +
                            "where tb_1_.ID = ?"
            );
            ctx.statement(1).variables("ORG", "Person", 202L);
            ctx.rows(
                    "[" +
                            "--->{\"type\":\"ORG\",\"id\":200,\"name\":\"Globex\",\"description\":\"DEFAULT_CLIENT_DESCRIPTION\"}," +
                            "--->{\"type\":\"ORG\",\"id\":202,\"name\":\"Initech\",\"description\":\"DEFAULT_CLIENT_DESCRIPTION\"}" +
                            "]"
            );
        });
    }

    @Test
    public void testIssue1154WithId() {
        for (int i = 0; i < 2; i++) {
            final boolean useSql = i == 0;
            connectAndExpect(con -> {
                Organization org = sqlClient
                        .getEntities()
                        .forConnection(con)
                        .findById(OrganizationFetcher.$.allScalarFields(), 200L);
                Assertions.assertFalse(org instanceof DraftSpi);
                return org;
            }, ctx -> {
                if (useSql) {
                    ctx.sql(
                            "select " +
                            "--->tb_1_.ID, tb_1_.CLIENT_TYPE, tb_1_.NAME, tb_1_.DESCRIPTION, tb_1__sub.TAX_CODE, tb_1__sub.STATUS " +
                            "from JOINED_CLIENT tb_1_ " +
                            "--->inner join JOINED_ORGANIZATION tb_1__sub on tb_1_.ID = tb_1__sub.ID " +
                            "where tb_1_.ID = ? and tb_1_.CLIENT_TYPE = ?"
                    );
                }
                ctx.rows(
                        "[{" +
                        "--->\"type\":\"ORG\"," +
                        "--->\"id\":200," +
                        "--->\"name\":\"Globex\"," +
                        "--->\"description\":\"DEFAULT_CLIENT_DESCRIPTION\"," +
                        "--->\"taxCode\":\"GLOBEX-001\"," +
                        "--->\"status\":\"DEFAULT_ORGANIZATION_STATUS\"" +
                        "}]"
                );
            });
        }
    }

    @Test
    public void testIssue1154WithIds() {
        for (int i = 0; i < 2; i++) {
            final boolean useSql = i == 0;
            connectAndExpect(con -> {
                List<Organization> orgs = sqlClient
                        .getEntities()
                        .forConnection(con)
                        .findByIds(OrganizationFetcher.$.allScalarFields(), Arrays.asList(200L, 202L));
                for (Organization org : orgs) {
                    Assertions.assertFalse(org instanceof DraftSpi);
                }
                return orgs;
            }, ctx -> {
                if (useSql) {
                    ctx.sql(
                            "select " +
                                    "--->tb_1_.ID, tb_1_.CLIENT_TYPE, tb_1_.NAME, tb_1_.DESCRIPTION, tb_1__sub.TAX_CODE, tb_1__sub.STATUS " +
                                    "from JOINED_CLIENT tb_1_ " +
                                    "--->inner join JOINED_ORGANIZATION tb_1__sub on tb_1_.ID = tb_1__sub.ID " +
                                    "where tb_1_.ID in (?, ?) and tb_1_.CLIENT_TYPE = ?"
                    );
                }
                ctx.rows(
                        "[" +
                                "--->{" +
                                "--->--->\"type\":\"ORG\"," +
                                "--->--->\"id\":200," +
                                "--->--->\"name\":\"Globex\"," +
                                "--->--->\"description\":\"DEFAULT_CLIENT_DESCRIPTION\"," +
                                "--->--->\"taxCode\":\"GLOBEX-001\"," +
                                "--->--->\"status\":\"DEFAULT_ORGANIZATION_STATUS\"" +
                                "--->},{" +
                                "--->--->\"type\":\"ORG\"," +
                                "--->--->\"id\":202," +
                                "--->--->\"name\":\"Initech\"," +
                                "--->--->\"description\":\"DEFAULT_CLIENT_DESCRIPTION\"," +
                                "--->--->\"taxCode\":\"INI-001\"," +
                                "--->--->\"status\":\"DEFAULT_ORGANIZATION_STATUS\"" +
                                "--->}" +
                                "]"
                );
            });
        }
    }

    private static final String HINT_SKELETON_SQL =
            "select tb_1_.ID from BOOK_STORE tb_1_ where tb_1_.ID = ?";

    private static final String HINT_WIDE_SQL =
            "select tb_1_.ID, tb_1_.NAME, tb_1_.WEBSITE, tb_1_.VERSION " +
                    "from BOOK_STORE tb_1_ where tb_1_.ID = ?";

    private static final String HINT_TUPLE_SQL =
            "select tb_1_.ID, tb_1_.NAME from BOOK_STORE tb_1_ where tb_1_.ID = ?";

    /**
     * A genuinely non-transactional test connection manager. The optional object-cache
     * hint only activates when the manager can positively prove that the connection is
     * not inside a transaction. This standalone JDBC fixture has no ambient framework
     * transaction, so for a plain H2 connection that proof is exactly
     * {@link Connection#getAutoCommit()}; a connection that really is in a local JDBC
     * transaction (the default rollback fixture) is correctly denied instead of being
     * assumed safe. No readiness flag and no fake proof is involved.
     */
    private static final ConnectionManager NON_TX_MANAGER = new ConnectionManager() {
        @Override
        @SuppressWarnings("unchecked")
        public <R> R execute(Connection con, Function<Connection, R> block) {
            if (con != null) {
                return block.apply(con);
            }
            R[] resultBox = (R[]) new Object[1];
            jdbc(null, false, c -> {
                c.setAutoCommit(true);
                resultBox[0] = block.apply(c);
            });
            return resultBox[0];
        }

        @Override
        public boolean isTransactionKnownInactive(Connection con) {
            if (con == null) {
                return false;
            }
            try {
                return con.getAutoCommit();
            } catch (SQLException ex) {
                return false;
            }
        }
    };

    /**
     * Runs read-only bodies on a fresh H2 connection whose auto-commit is genuinely
     * enabled, so nothing is ever committed and the connection is exactly what a
     * non-transactional caller looks like.
     */
    private static void nontransactional(AbstractTest.SqlConsumer<Connection> block) {
        jdbc(null, false, con -> {
            con.setAutoCommit(true);
            block.accept(con);
        });
    }

    /**
     * An "unknown" manager that never overrides {@code isTransactionKnownInactive}, so
     * the default {@code false} proof is exercised even on a genuinely auto-commit
     * connection: the hint must stay in ordinary SQL.
     */
    private static final ConnectionManager UNKNOWN_MANAGER = new ConnectionManager() {
        @Override
        @SuppressWarnings("unchecked")
        public <R> R execute(Connection con, Function<Connection, R> block) {
            if (con != null) {
                return block.apply(con);
            }
            R[] resultBox = (R[]) new Object[1];
            jdbc(null, false, c -> {
                c.setAutoCommit(true);
                resultBox[0] = block.apply(c);
            });
            return resultBox[0];
        }
    };

    @Test
    public void testObjectCacheHintEntity() {
        BookStoreTable table = BookStoreTable.$;
        for (int i = 0; i < 2; i++) {
            final boolean warmMiss = i == 0;
            clearExecutions();
            List<BookStore> rows = new ArrayList<>();
            nontransactional(con -> rows.addAll(
                    sqlClient
                            .createQuery(table)
                            .where(table.id().eq(oreillyId))
                            .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                            .useObjectCache()
                            .execute(con)
            ));
            Assertions.assertEquals(1, rows.size());
            Assertions.assertEquals("O'REILLY", rows.get(0).name());
            assertHintStatements(warmMiss, HINT_SKELETON_SQL);
        }
    }

    @Test
    public void testContentOnlyObjectCacheDoesNotServeOrdinaryEntityOrDtoReads() {
        sqlClient = newClient(NON_TX_MANAGER, true);
        BookStoreTable table = BookStoreTable.$;
        List<BookStore> warm = new ArrayList<>();
        nontransactional(con -> warm.addAll(sqlClient
                .createQuery(table)
                .where(table.id().eq(oreillyId))
                .select(table.fetch(BookStoreFetcher.$.name()))
                .useObjectCache(BookStoreFetcher.$.name())
                .execute(con)));
        Assertions.assertEquals(1, warm.size());
        Assertions.assertEquals("O'REILLY", warm.get(0).name());
        clearExecutions();
        nontransactional(con -> sqlClient
                .createQuery(table)
                .where(table.id().eq(oreillyId))
                .select(table.fetch(BookStoreFetcher.$.name()))
                .useObjectCache(BookStoreFetcher.$.name())
                .execute(con));
        Assertions.assertEquals(1, getExecutions().size());
        Assertions.assertEquals(HINT_SKELETON_SQL, getExecutions().get(0).getSql());

        String pendingName = "O'REILLY-pending";
        jdbc(con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "update BOOK_STORE set NAME = ? where ID = ?"
            )) {
                ps.setString(1, pendingName);
                ps.setObject(2, oreillyId);
                Assertions.assertEquals(1, ps.executeUpdate());
            }
            BookStore store = sqlClient.getEntities().forConnection(con).findById(
                    BookStore.class,
                    oreillyId
            );
            Assertions.assertEquals(pendingName, store.name());
            List<BookStore> wholeEntity = sqlClient.createQuery(table)
                    .where(table.id().eq(oreillyId))
                    .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                    .useObjectCache()
                    .execute(con);
            Assertions.assertEquals(1, wholeEntity.size());
            Assertions.assertEquals(pendingName, wholeEntity.get(0).name());
            ReusableBookStoreView view = sqlClient.getEntities().forConnection(con).findById(
                    ReusableBookStoreView.class,
                    oreillyId
            );
            Assertions.assertEquals(pendingName, view.getName());
        });
    }

    @Test
    public void testContentOnlyAssociationSubtreeDoesNotReuseAmbientCacheContext() {
        sqlClient = newClient(NON_TX_MANAGER, true);
        Cache<Object, Author> authorCache = sqlClient.getCaches().getObjectCache(ImmutableType.get(Author.class));
        nontransactional(con -> authorCache.getAll(
                Collections.<Object>singletonList(sammerId),
                new CacheEnvironment<>(sqlClient, con, keys -> Collections.singletonMap(
                        sammerId,
                        AuthorDraft.$.produce(draft -> {
                            draft.setId(sammerId);
                            draft.setFirstName("STALE");
                        })
                ), false)
        ));
        BookStoreFetcher storeFetcher = BookStoreFetcher.$.name().books(
                BookFetcher.$.name().authors(AuthorFetcher.$.firstName())
        );
        List<BookStore> stores = new ArrayList<>();
        nontransactional(con -> stores.add(sqlClient.getEntities().forConnection(con)
                .findById(storeFetcher, manningId)));
        BookTable table = BookTable.$;
        nontransactional(con -> stores.add(sqlClient.createQuery(table)
                .where(table.id().eq(graphQLInActionId1))
                .select(table.fetch(BookFetcher.$.store(
                        ReferenceFetchType.SELECT,
                        storeFetcher
                )))
                .execute(con).get(0).store()));
        for (BookStore store : stores) {
            Assertions.assertFalse(store.books().isEmpty());
            for (Book book : store.books()) {
                Assertions.assertFalse(book.authors().isEmpty());
                Assertions.assertEquals("Samer", book.authors().get(0).firstName());
            }
        }
        nontransactional(con -> Assertions.assertEquals("STALE", sqlClient.getEntities().forConnection(con)
                .findById(AuthorFetcher.$.firstName(), sammerId).firstName()));
    }

    @Test
    public void testContentOnlyBooleanFetcherHintKeepsCommittedFieldsFresh() {
        sqlClient = newClient(NON_TX_MANAGER, true);
        BookStoreTable table = BookStoreTable.$;
        String originalName = sqlClient.getEntities().findById(BookStore.class, oreillyId).name();
        int originalVersion = sqlClient.createQuery(table)
                .where(table.id().eq(oreillyId))
                .select(table.version())
                .execute()
                .get(0);
        int updatedVersion = originalVersion + 1;

        // Explicitly cache only display content; version remains SQL-authoritative.
        nontransactional(con -> sqlClient.createQuery(table)
                .where(table.id().eq(oreillyId))
                .select(table.fetch(BookStoreFetcher.$.name()))
                .useObjectCache(BookStoreFetcher.$.name())
                .execute(con));

        String committedName = originalName + "-committed";
        try {
            nontransactional(con -> {
                try (PreparedStatement ps = con.prepareStatement(
                        "update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?"
                )) {
                    ps.setString(1, committedName);
                    ps.setInt(2, updatedVersion);
                    ps.setObject(3, oreillyId);
                    Assertions.assertEquals(1, ps.executeUpdate());
                }
            });

            List<BookStore> ordinary = sqlClient.createQuery(table)
                    .where(table.id().eq(oreillyId))
                    .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                    .useObjectCache()
                    .execute();
            Assertions.assertEquals(1, ordinary.size());
            Assertions.assertEquals(committedName, ordinary.get(0).name());
            Assertions.assertEquals(updatedVersion, ordinary.get(0).version());
            List<Tuple2<BookStore, Integer>> booleanTuple = sqlClient.createQuery(table)
                    .where(table.id().eq(oreillyId))
                    .select(table.fetch(BookStoreFetcher.$.allScalarFields()), table.version())
                    .useObjectCache()
                    .execute();
            Assertions.assertEquals(1, booleanTuple.size());
            Assertions.assertEquals(committedName, booleanTuple.get(0).get_1().name());
            Assertions.assertEquals(updatedVersion, booleanTuple.get(0).get_2());
            Assertions.assertEquals(committedName,
                    sqlClient.getEntities().findById(BookStore.class, oreillyId).name());
            Assertions.assertEquals(committedName,
                    sqlClient.getEntities().findById(ReusableBookStoreView.class, oreillyId).getName());
            List<ReusableBookStoreView> booleanDto = sqlClient.createQuery(table)
                    .where(table.id().eq(oreillyId))
                    .select(table.fetch(ReusableBookStoreView.class))
                    .useObjectCache()
                    .execute();
            Assertions.assertEquals(1, booleanDto.size());
            Assertions.assertEquals(committedName, booleanDto.get(0).getName());

            nontransactional(con -> Assertions.assertTrue(sqlClient.createQuery(table)
                    .where(table.id().eq(oreillyId))
                    .select(table.id())
                    .exists(con)));
            nontransactional(con -> {
                BookStore locked = sqlClient.getEntities().forUpdate().forConnection(con)
                        .findById(BookStore.class, oreillyId);
                Assertions.assertEquals(committedName, locked.name());
            });

            List<BookStore> masked = sqlClient.createQuery(table)
                    .where(table.id().eq(oreillyId))
                    .select(table.fetch(BookStoreFetcher.$.name().version()))
                    .useObjectCache(BookStoreFetcher.$.name())
                    .execute();
            Assertions.assertEquals(1, masked.size());
            Assertions.assertEquals(originalName, masked.get(0).name());
            Assertions.assertEquals(updatedVersion, masked.get(0).version());
            List<Tuple2<BookStore, Integer>> maskedTuple = sqlClient.createQuery(table)
                    .where(table.id().eq(oreillyId))
                    .select(table.fetch(BookStoreFetcher.$.name().version()), table.version())
                    .useObjectCache(BookStoreFetcher.$.name())
                    .execute();
            Assertions.assertEquals(1, maskedTuple.size());
            Assertions.assertEquals(originalName, maskedTuple.get(0).get_1().name());
            Assertions.assertEquals(updatedVersion, maskedTuple.get(0).get_2());

            Cache<Object, BookStore> cache = sqlClient.getCaches()
                    .<Object, BookStore>getObjectCache(ImmutableType.get(BookStore.class));
            Assertions.assertNotNull(cache);
            cache.delete(oreillyId);
            nontransactional(con -> cache.getAll(
                    Collections.<Object>singletonList(oreillyId),
                    new CacheEnvironment<>(sqlClient, con, keys -> Collections.emptyMap(), false)
            ));
            Assertions.assertEquals(committedName,
                    sqlClient.getEntities().findById(BookStore.class, oreillyId).name());
        } finally {
            nontransactional(con -> {
                try (PreparedStatement ps = con.prepareStatement(
                        "update BOOK_STORE set NAME = ?, VERSION = ? where ID = ?"
                )) {
                    ps.setString(1, originalName);
                    ps.setInt(2, originalVersion);
                    ps.setObject(3, oreillyId);
                    Assertions.assertEquals(1, ps.executeUpdate());
                }
            });
        }
    }

    @Test
    public void testObjectCacheHintTupleWithScalar() {
        BookStoreTable table = BookStoreTable.$;
        for (int i = 0; i < 2; i++) {
            final boolean warmMiss = i == 0;
            clearExecutions();
            List<Tuple2<BookStore, String>> rows = new ArrayList<>();
            nontransactional(con -> rows.addAll(
                    sqlClient
                            .createQuery(table)
                            .where(table.id().eq(oreillyId))
                            .select(
                                    table.fetch(BookStoreFetcher.$.allScalarFields()),
                                    table.name()
                            )
                            .useObjectCache()
                            .execute(con)
            ));
            Assertions.assertEquals(1, rows.size());
            Assertions.assertEquals("O'REILLY", rows.get(0).get_1().name());
            Assertions.assertEquals("O'REILLY", rows.get(0).get_2());
            assertHintStatements(warmMiss, HINT_TUPLE_SQL);
        }
    }

    @Test
    public void testObjectCacheHintDisabledFallsBack() {
        BookStoreTable table = BookStoreTable.$;
        clearExecutions();
        List<BookStore> rows = new ArrayList<>();
        nontransactional(con -> rows.addAll(
                sqlClient
                        .createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                        .useObjectCache(false)
                        .execute(con)
        ));
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("O'REILLY", rows.get(0).name());
        Assertions.assertEquals(1, getExecutions().size());
        Assertions.assertEquals(HINT_WIDE_SQL, getExecutions().get(0).getSql());
    }

    @Test
    public void testObjectCacheHintForUpdateFallsBack() {
        warmBookStore(sqlClient);
        BookStoreTable table = BookStoreTable.$;
        clearExecutions();
        List<BookStore> rows = new ArrayList<>();
        nontransactional(con -> rows.addAll(
                sqlClient
                        .createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                        .useObjectCache()
                        .forUpdate()
                        .execute(con)
        ));
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("O'REILLY", rows.get(0).name());
        // forUpdate is checked before the manager: the wide entity SQL must run even
        // though the object cache is warm and the connection is provably inactive.
        Assertions.assertEquals(1, getExecutions().size());
        assertWideEntitySql(getExecutions().get(0).getSql());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testObjectCacheHintCommandPurposeFallsBack() {
        warmBookStore(sqlClient);
        clearExecutions();
        ConfigurableRootQuery<Table<?>, BookStore> query = Queries.createQuery(
                (JSqlClientImplementor) sqlClient,
                ImmutableType.get(BookStore.class),
                ExecutionPurpose.command(QueryReason.NONE),
                FilterLevel.DEFAULT,
                (q, table) -> q
                        .where(table.get("id").eq(oreillyId))
                        .select(((Table<BookStore>) table).fetch(BookStoreFetcher.$.allScalarFields()))
        );
        List<BookStore> rows = new ArrayList<>();
        nontransactional(con -> rows.addAll(query.useObjectCache().execute(con)));
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("O'REILLY", rows.get(0).name());
        // A command purpose can never be served from the object cache.
        Assertions.assertEquals(1, getExecutions().size());
        assertWideEntitySql(getExecutions().get(0).getSql());
    }

    @Test
    public void testObjectCacheHintUnknownManagerFallsBack() {
        JSqlClient local = newClient(UNKNOWN_MANAGER);
        warmBookStore(local);
        BookStoreTable table = BookStoreTable.$;
        clearExecutions();
        List<BookStore> rows = new ArrayList<>();
        nontransactional(con -> rows.addAll(
                local
                        .createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                        .useObjectCache()
                        .execute(con)
        ));
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("O'REILLY", rows.get(0).name());
        // The default manager proof is false, so a genuinely auto-commit connection
        // still must not activate the hint.
        Assertions.assertEquals(1, getExecutions().size());
        assertWideEntitySql(getExecutions().get(0).getSql());
    }

    @Test
    public void testObjectCacheHintExplicitAutoCommitFalseFallsBack() {
        warmBookStore(sqlClient);
        BookStoreTable table = BookStoreTable.$;
        clearExecutions();
        List<BookStore> rows = new ArrayList<>();
        jdbc(con -> rows.addAll(
                sqlClient
                        .createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                        .useObjectCache()
                        .execute(con)
        ));
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("O'REILLY", rows.get(0).name());
        // The supplied connection really is a local JDBC transaction (autoCommit=false).
        Assertions.assertEquals(1, getExecutions().size());
        Assertions.assertEquals(HINT_WIDE_SQL, getExecutions().get(0).getSql());
    }

    @Test
    public void testObjectCacheHintColdDirtyRootReadIsNotPublished() {
        BookStoreTable table = BookStoreTable.$;
        // Warm the shared object cache with the committed state.
        warmBookStore(sqlClient);

        String pending = "O'REILLY-pending";
        // A real local JDBC transaction with an uncommitted raw update: the hinted
        // ROOT query must decline and observe the pending value through ordinary SQL.
        jdbc(con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "update BOOK_STORE set NAME = ? where ID = ?"
            )) {
                ps.setString(1, pending);
                ps.setObject(2, oreillyId);
                Assertions.assertEquals(1, ps.executeUpdate());
            }
            List<BookStore> inTx = sqlClient
                    .createQuery(table)
                    .where(table.id().eq(oreillyId))
                    .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                    .useObjectCache()
                    .execute(con);
            Assertions.assertEquals(1, inTx.size());
            Assertions.assertEquals(pending, inTx.get(0).name());
        });

        // The rollback restored the committed row and the uncommitted value must not
        // have been published into the shared object cache.
        clearExecutions();
        List<BookStore> after = new ArrayList<>();
        nontransactional(con -> after.addAll(
                sqlClient
                        .createQuery(table)
                        .where(table.id().eq(oreillyId))
                        .select(table.fetch(BookStoreFetcher.$.allScalarFields()))
                        .useObjectCache()
                        .execute(con)
        ));
        Assertions.assertEquals(1, after.size());
        Assertions.assertEquals("O'REILLY", after.get(0).name());
        // The warm cache served the entity: exactly one id-only skeleton statement and
        // no entity reload. A missing/polluted cache would emit the wide SQL or return
        // the pending value instead.
        Assertions.assertEquals(1, getExecutions().size());
        Assertions.assertEquals(HINT_SKELETON_SQL, getExecutions().get(0).getSql());
    }

    private static void warmBookStore(JSqlClient client) {
        nontransactional(con ->
                client.getEntities().forConnection(con).findById(BookStore.class, oreillyId)
        );
    }

    private void assertHintStatements(boolean warmMiss, String skeletonSql) {
        List<Execution> executions = getExecutions();
        Assertions.assertEquals(warmMiss ? 2 : 1, executions.size());
        Assertions.assertEquals(skeletonSql, executions.get(0).getSql());
        Assertions.assertEquals(oreillyId, executions.get(0).getVariables(0).get(0));
        if (warmMiss) {
            Assertions.assertEquals(HINT_WIDE_SQL, executions.get(1).getSql());
            Assertions.assertEquals(oreillyId, executions.get(1).getVariables(0).get(0));
        }
    }

    private void assertWideEntitySql(String sql) {
        Assertions.assertTrue(sql.contains("tb_1_.WEBSITE"), sql);
        Assertions.assertTrue(sql.contains("from BOOK_STORE tb_1_"), sql);
    }
}
