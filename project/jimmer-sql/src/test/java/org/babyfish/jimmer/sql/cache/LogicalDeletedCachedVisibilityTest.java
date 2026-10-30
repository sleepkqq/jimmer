package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.common.CacheImpl;
import org.babyfish.jimmer.sql.filter.Filter;
import org.babyfish.jimmer.sql.filter.FilterArgs;
import org.babyfish.jimmer.sql.model.inheritance.Role;
import org.babyfish.jimmer.sql.model.inheritance.RoleTable;
import org.babyfish.jimmer.sql.model.inheritance.dto.RoleWithMappedSuperclassFragmentView;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in logical-delete filter is a comparison on the entity's own column and the
 * object cache stores that column, so a warm id read evaluates it on the cached row
 * instead of running a visible-id query. Fixture: {@code ROLE} holds one live row and one
 * logically deleted row.
 */
public class LogicalDeletedCachedVisibilityTest extends AbstractQueryTest {

    private static final long LIVE_ROLE_ID = 100L;

    private static final long DELETED_ROLE_ID = 200L;

    private static final long ABSENT_ROLE_ID = 999L;

    private static final ConnectionManager AUTO_COMMIT_MANAGER = new ConnectionManager() {
        @Override
        public <R> R execute(Connection con, Function<Connection, R> block) {
            return testConnectionManager().execute(con, block);
        }

        @Override
        public boolean isTransactionKnownInactive(Connection con) {
            return con != null;
        }
    };

    private JSqlClient sqlClient;

    @BeforeEach
    public void initialize() {
        sqlClient = createClient(builder -> {
        });
    }

    @Test
    public void testWarmLiveRowUsesCacheOnly() {
        findRoles(sqlClient, LIVE_ROLE_ID);
        clearExecutions();
        List<Role> roles = findRoles(sqlClient, LIVE_ROLE_ID);
        assertSqlCount(0);
        assertEquals(1, roles.size());
        assertEquals(LIVE_ROLE_ID, roles.get(0).getId());
    }

    @Test
    public void testWarmDeletedRowIsInvisibleWithoutSql() {
        findRoles(sqlClient, LIVE_ROLE_ID, DELETED_ROLE_ID, ABSENT_ROLE_ID);
        clearExecutions();
        assertNull(findRole(sqlClient, DELETED_ROLE_ID));
        assertSqlCount(0);
        clearExecutions();
        assertEquals(
                Arrays.asList(LIVE_ROLE_ID),
                idsOf(findRoles(sqlClient, LIVE_ROLE_ID, DELETED_ROLE_ID, ABSENT_ROLE_ID))
        );
        assertSqlCount(0);
    }

    @Test
    public void testColdReadLoadsOnceAndFiltersCachedRow() {
        clearExecutions();
        assertEquals(
                Arrays.asList(LIVE_ROLE_ID),
                idsOf(findRoles(sqlClient, LIVE_ROLE_ID, DELETED_ROLE_ID))
        );
        assertSqlCount(1);
        assertTrue(getExecutions().get(0).getSql().contains("ROLE"), sqls());
        clearExecutions();
        assertEquals(
                Arrays.asList(LIVE_ROLE_ID),
                idsOf(findRoles(sqlClient, LIVE_ROLE_ID, DELETED_ROLE_ID))
        );
        assertSqlCount(0);
    }

    @Test
    public void testWarmDtoViewReadUsesCacheOnly() {
        clearExecutions();
        assertEquals(
                Arrays.asList(LIVE_ROLE_ID),
                viewIdsOf(findViews(sqlClient, LIVE_ROLE_ID, DELETED_ROLE_ID, ABSENT_ROLE_ID))
        );
        assertSqlCount(1);
        clearExecutions();
        assertEquals(
                Arrays.asList(LIVE_ROLE_ID),
                viewIdsOf(findViews(sqlClient, LIVE_ROLE_ID, DELETED_ROLE_ID, ABSENT_ROLE_ID))
        );
        assertSqlCount(0);
    }

    @Test
    public void testAdditionalUserFilterKeepsVisibilitySql() {
        JSqlClient client = createClient(builder ->
                builder.addFilters(new Filter<RoleTable>() {
                    @Override
                    public void filter(FilterArgs<RoleTable> args) {
                    }
                })
        );
        findRoles(client, LIVE_ROLE_ID, DELETED_ROLE_ID);
        clearExecutions();
        assertEquals(
                Arrays.asList(LIVE_ROLE_ID),
                idsOf(findRoles(client, LIVE_ROLE_ID, DELETED_ROLE_ID))
        );
        assertSqlCount(1);
        assertTrue(getExecutions().get(0).getSql().contains("select tb_1_.ID"), sqls());
    }

    @Test
    public void testIgnoredLogicalDeleteKeepsVisibilitySql() {
        JSqlClient client = sqlClient.filters(cfg -> cfg.setBehavior(LogicalDeletedBehavior.IGNORED));
        findRoles(client, LIVE_ROLE_ID, DELETED_ROLE_ID);
        clearExecutions();
        assertEquals(
                Arrays.asList(LIVE_ROLE_ID, DELETED_ROLE_ID),
                idsOf(findRoles(client, LIVE_ROLE_ID, DELETED_ROLE_ID))
        );
        assertSqlCount(1);
    }

    @Test
    public void testLockingReadKeepsSqlPath() {
        findRoles(sqlClient, LIVE_ROLE_ID);
        clearExecutions();
        List<Role> roles = new ArrayList<>();
        jdbc(con -> {
            Role role = sqlClient
                    .getEntities()
                    .forConnection(con)
                    .forUpdate()
                    .findById(Role.class, LIVE_ROLE_ID);
            if (role != null) {
                roles.add(role);
            }
        });
        assertSqlCount(1);
        assertEquals(1, roles.size());
        assertEquals(LIVE_ROLE_ID, roles.get(0).getId());
        assertTrue(getExecutions().get(0).getSql().toLowerCase().contains("for update"), sqls());
    }

    @Test
    public void testContentOnlyPolicyKeepsSqlPath() {
        JSqlClient client = createClient(
                builder -> {
                },
                ImmutableType.get(Role.class)
        );
        for (int i = 0; i < 2; i++) {
            clearExecutions();
            assertEquals(
                    Arrays.asList(LIVE_ROLE_ID),
                    idsOf(findRoles(client, LIVE_ROLE_ID, DELETED_ROLE_ID))
            );
            assertSqlCount(1);
        }
    }

    private JSqlClient createClient(Consumer<JSqlClient.Builder> block) {
        return createClient(block, null);
    }

    private JSqlClient createClient(Consumer<JSqlClient.Builder> block, ImmutableType contentOnlyType) {
        return getSqlClient(builder -> {
            builder.setConnectionManager(AUTO_COMMIT_MANAGER);
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public boolean isObjectCacheContentOnly(ImmutableType type) {
                    return type == contentOnlyType;
                }

                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return new CacheImpl<>(type);
                }
            }));
            block.accept(builder);
        });
    }

    private List<Role> findRoles(JSqlClient client, long... ids) {
        List<Role> roles = new ArrayList<>();
        jdbc(con -> roles.addAll(
                client.getEntities()
                        .forConnection(con)
                        .findByIds(Role.class, idList(ids))
        ));
        return roles;
    }

    private Role findRole(JSqlClient client, long id) {
        Role[] roleBox = new Role[1];
        jdbc(con -> roleBox[0] = client
                .getEntities()
                .forConnection(con)
                .findById(Role.class, id));
        return roleBox[0];
    }

    private List<RoleWithMappedSuperclassFragmentView> findViews(JSqlClient client, long... ids) {
        List<RoleWithMappedSuperclassFragmentView> views = new ArrayList<>();
        jdbc(con -> views.addAll(
                client.getEntities()
                        .forConnection(con)
                        .findByIds(RoleWithMappedSuperclassFragmentView.class, idList(ids))
        ));
        return views;
    }

    private static List<Long> idList(long... ids) {
        List<Long> list = new ArrayList<>(ids.length);
        for (long id : ids) {
            list.add(id);
        }
        return list;
    }

    private static List<Long> idsOf(List<Role> roles) {
        List<Long> ids = new ArrayList<>(roles.size());
        for (Role role : roles) {
            ids.add(role.getId());
        }
        return ids;
    }

    private static List<Long> viewIdsOf(List<RoleWithMappedSuperclassFragmentView> views) {
        List<Long> ids = new ArrayList<>(views.size());
        for (RoleWithMappedSuperclassFragmentView view : views) {
            ids.add(view.getId());
        }
        return ids;
    }

    private void assertSqlCount(int expected) {
        assertEquals(expected, getExecutions().size(), sqls());
    }

    private String sqls() {
        StringBuilder builder = new StringBuilder("executed SQL:");
        for (Execution execution : getExecutions()) {
            builder.append('\n').append(execution.getSql());
        }
        return builder.toString();
    }
}
