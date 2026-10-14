package org.babyfish.jimmer.sql.query;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.query.ConfigurableRootQuery;
import org.babyfish.jimmer.sql.ast.query.TypedSubQuery;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.cache.CacheLoader;
import org.babyfish.jimmer.sql.cache.ValueSerializer;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.common.CacheImpl;
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType;
import org.babyfish.jimmer.sql.filter.Filter;
import org.babyfish.jimmer.sql.filter.FilterArgs;
import org.babyfish.jimmer.sql.model.hr.DepartmentFetcher;
import org.babyfish.jimmer.sql.model.hr.EmployeeFetcher;
import org.babyfish.jimmer.sql.model.hr.EmployeeTable;
import org.babyfish.jimmer.sql.model.inheritance.*;
import org.babyfish.jimmer.sql.runtime.LogicalDeletedBehavior;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class GlobalFilterTest extends AbstractQueryTest {

    private LambdaClient lambdaClient;

    private LambdaClient lambdaClientForDeletedData;

    @BeforeEach
    public void initialize() {
        JSqlClient sqlClient = getSqlClient();
        JSqlClient sqlClientForDeletedData = sqlClient.filters(it -> {
            it.setBehavior(LogicalDeletedBehavior.REVERSED);
        });
        lambdaClient = new LambdaClient(sqlClient);
        lambdaClientForDeletedData = new LambdaClient(sqlClientForDeletedData);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testSelectedSubQueryFiltersSurvivePagination(boolean paged) {
        JSqlClient client = getSqlClient(it -> it.addFilters(new Filter<PermissionTable>() {
            @Override
            public void filter(FilterArgs<PermissionTable> args) {
                args.where(args.getTable().id().ne(1000L));
            }
        }));
        RoleTable role = RoleTable.$;
        PermissionTable permission = PermissionTable.$;
        TypedSubQuery<Long> count = client.createSubQuery(permission)
                .where(permission.role().id().eq(role.id()))
                .select(Expression.rowCount());
        ConfigurableRootQuery<RoleTable, Tuple2<Long, Long>> query = client.createQuery(role)
                .where(role.id().eq(100L))
                .orderBy(role.id())
                .select(role.id(), count);

        jdbc(con -> {
            List<Tuple2<Long, Long>> rows = paged ? query.fetchPage(0, 10, con).getRows() : query.execute(con);
            Assertions.assertEquals(1, rows.size());
            // Permission 1000 is hidden by the user filter; 1001 is logically deleted.
            Assertions.assertEquals(new Tuple2<>(100L, 0L), rows.get(0));
        });
    }

    @Test
    public void testCachedIdReadRechecksCurrentFilter() {
        AtomicLong visibleId = new AtomicLong(-1L);
        JSqlClient client = getSqlClient(it -> {
            it.addFilters(new Filter<PermissionTable>() {
                @Override
                public void filter(FilterArgs<PermissionTable> args) {
                    args.where(args.getTable().id().eq(visibleId.get()));
                }
            });
            it.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return new CacheImpl<>(type);
                }
            });
        });
        jdbc(con -> {
            Assertions.assertNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));
            visibleId.set(1000L);
            Assertions.assertNotNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));
            visibleId.set(-1L);
            Assertions.assertNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));
            Assertions.assertNull(client.getEntities().forConnection(con).findById(PermissionFetcher.$.name(), 1000L));
            visibleId.set(1000L);
            Assertions.assertNotNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));
            clearExecutions();
            Assertions.assertNotNull(client.getEntities().forConnection(con).forUpdate().findById(Permission.class, 1000L));
            Assertions.assertTrue(getExecutions().get(0).getSql().endsWith("for update"));
        });
    }

    @Test
    public void testContentOnlyObjectReadsStillApplyCurrentFiltersAndForUpdate() {
        AtomicLong visibleId = new AtomicLong(1000L);
        JSqlClient client = getSqlClient(it -> {
            it.addFilters(new Filter<PermissionTable>() {
                @Override
                public void filter(FilterArgs<PermissionTable> args) {
                    args.where(args.getTable().id().eq(visibleId.get()));
                }
            });
            it.setCacheFactory(new CacheFactory() {
                @Override
                public boolean isObjectCacheContentOnly(ImmutableType type) {
                    return type == ImmutableType.get(Permission.class);
                }

                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return new CacheImpl<>(type);
                }
            });
        });
        jdbc(con -> {
            Cache<Object, Permission> cache = client.getCaches()
                    .getObjectCache(ImmutableType.get(Permission.class));
            cache.getAll(Collections.<Object>singletonList(1000L), new CacheEnvironment<>(
                    client,
                    con,
                    CacheLoader.objectLoader(client, con, Permission.class),
                    false
            ));

            visibleId.set(-1L);
            Assertions.assertNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));
            visibleId.set(1000L);
            Assertions.assertNotNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));

            clearExecutions();
            Assertions.assertNotNull(client.getEntities().forConnection(con).forUpdate()
                    .findById(Permission.class, 1000L));
            Assertions.assertTrue(getExecutions().get(0).getSql().endsWith("for update"));
        });
    }

    @Test
    public void testStoredNegativeIdStillRechecksCurrentFilter() {
        // A real stored negative entry (an explicit serialized null, distinct from an
        // eviction) must still be gated by the current-filter visibility check on every
        // read, including the transition visible -> hidden -> visible. The stored null
        // alone would make every assertion pass, so this pins the SQL visibility check
        // and the absence of a content-cache lookup while the id is hidden.
        AtomicLong visibleId = new AtomicLong(1000L);
        ImmutableType permissionType = ImmutableType.get(Permission.class);
        Map<Object, byte[]> seeded = new HashMap<>();
        seeded.put(1000L, new ValueSerializer<Permission>(permissionType)
                .serialize(Collections.<Long, Permission>singletonMap(1000L, null))
                .get(1000L));
        CountingCache<Permission> cache = new CountingCache<>(permissionType, seeded);
        JSqlClient client = getSqlClient(it -> {
            it.addFilters(new Filter<PermissionTable>() {
                @Override
                public void filter(FilterArgs<PermissionTable> args) {
                    args.where(args.getTable().id().eq(visibleId.get()));
                }
            });
            it.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return type == permissionType ? cache : null;
                }
            });
        });
        jdbc(con -> {
            clearExecutions();
            Assertions.assertNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));
            Assertions.assertEquals(1, getExecutions().size(), "allowed read must run the current-filter visibility check");
            int afterAllowed = cache.invocationCount();

            visibleId.set(-1L);
            clearExecutions();
            Assertions.assertNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));
            Assertions.assertEquals(1, getExecutions().size(), "hidden read must still run the visibility check");
            Assertions.assertEquals(afterAllowed, cache.invocationCount(), "hidden id must not consult the content cache");

            visibleId.set(1000L);
            clearExecutions();
            Assertions.assertNull(client.getEntities().forConnection(con).findById(Permission.class, 1000L));
            Assertions.assertEquals(1, getExecutions().size(), "re-visible read must run the visibility check");
            Assertions.assertEquals(afterAllowed + 1, cache.invocationCount(), "negative entry must not be loaded as content");
        });
    }

    private static class CountingCache<T> extends CacheImpl<T> {

        private final List<Collection<Object>> keys = new ArrayList<>();

        private CountingCache(ImmutableType type, Map<Object, byte[]> seeded) {
            super(type, seeded);
        }

        @Override
        public Map<Object, T> getAll(Collection<Object> keys, CacheEnvironment<Object, T> env) {
            this.keys.add(new ArrayList<>(keys));
            return super.getAll(keys, env);
        }

        int invocationCount() {
            return keys.size();
        }
    }

    @Test
    public void testJoinFetchAppliesLogicalDeletedFilterToJoinedReference() {
        EmployeeTable table = EmployeeTable.$;
        executeAndExpect(
                getSqlClient()
                        .createQuery(table)
                        .where(table.id().eq(1L))
                        .select(
                                table.fetch(
                                        EmployeeFetcher.$
                                                .name()
                                                .department(
                                                        ReferenceFetchType.JOIN_ALWAYS,
                                                        DepartmentFetcher.$.name()
                                                )
                                )
                        ),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_2_.ID, tb_2_.NAME " +
                                    "from EMPLOYEE tb_1_ " +
                                    "left join DEPARTMENT tb_2_ " +
                                    "--->on tb_1_.DEPARTMENT_ID = tb_2_.ID " +
                                    "--->and tb_2_.DELETED_MILLIS = ? " +
                                    "where tb_1_.ID = ? and tb_1_.DELETED_MILLIS = ?"
                    );
                    ctx.variables(0L, 1L, 0L);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"id\":\"1\"," +
                                    "--->--->\"name\":\"Sam\"," +
                                    "--->--->\"department\":{\"id\":\"1\",\"name\":\"Market\"}" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryUndeletedRoleWithIdOnlyPermissions() {
        executeAndExpect(
                lambdaClient.createQuery(RoleTable.class, (q, role) -> {
                    return q.select(
                            role.fetch(
                                    RoleFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .permissions()
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.DELETED <> ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID " +
                                    "from PERMISSION tb_1_ " +
                                    "where tb_1_.ROLE_ID = ? " +
                                    "and tb_1_.DELETED <> ?"
                    ).variables(100L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"r_1\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"permissions\":[" +
                                    "--->--->--->{\"id\":1000}" +
                                    "--->--->]," +
                                    "--->--->\"id\":100" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryUndeletedRoleWithPermissions() {
        executeAndExpect(
                lambdaClient.createQuery(RoleTable.class, (q, role) -> {
                    return q.select(
                            role.fetch(
                                    RoleFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .permissions(
                                                    PermissionFetcher.$
                                                            .allScalarFields()
                                                            .deleted()
                                            )
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.DELETED <> ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from PERMISSION tb_1_ " +
                                    "where tb_1_.ROLE_ID = ? " +
                                    "and tb_1_.DELETED <> ?"
                    ).variables(100L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"r_1\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"permissions\":[" +
                                    "--->--->--->{" +
                                    "--->--->--->--->\"name\":\"p_1\"," +
                                    "--->--->--->--->\"deleted\":false," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":1000" +
                                    "--->--->--->}" +
                                    "--->--->]," +
                                    "--->--->\"id\":100" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryUndeletedPermissionAndIdOnlyRole() {
        executeAndExpect(
                lambdaClient.createQuery(PermissionTable.class, (q, permisson) -> {
                    return q.select(
                            permisson.fetch(
                                    PermissionFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .role()
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED, tb_1_.ROLE_ID " +
                                    "from PERMISSION tb_1_ " +
                                    "where tb_1_.DELETED <> ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID from ROLE tb_1_ " +
                                    "where tb_1_.ID in (?, ?) " +
                                    "and tb_1_.DELETED <> ?"
                    ).variables(100L, 200L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"p_1\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"role\":{\"id\":100},\"" +
                                    "--->--->id\":1000" +
                                    "--->},{" +
                                    "--->--->\"name\":\"p_3\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"role\":null," +
                                    "--->--->\"id\":3000" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryUndeletedPermissionAndRole() {
        executeAndExpect(
                lambdaClient.createQuery(PermissionTable.class, (q, permission) -> {
                    return q.select(
                            permission.fetch(
                                    PermissionFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .role(
                                                    RoleFetcher.$
                                                            .allScalarFields()
                                                            .deleted()
                                            )
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED, tb_1_.ROLE_ID " +
                                    "from PERMISSION tb_1_ " +
                                    "where tb_1_.DELETED <> ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.ID in (?, ?) and tb_1_.DELETED <> ?"
                    ).variables(100L, 200L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"p_1\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"role\":{" +
                                    "--->--->--->\"name\":\"r_1\"," +
                                    "--->--->--->\"deleted\":false," +
                                    "--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->\"id\":100" +
                                    "--->--->}," +
                                    "--->--->\"id\":1000" +
                                    "--->},{" +
                                    "--->--->\"name\":\"p_3\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"role\":null," +
                                    "--->--->\"id\":3000" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryUndeletedAdministratorWithIdOnlyRoles() {
        executeAndExpect(
                lambdaClient.createQuery(AdministratorTable.class, (q, administrator) -> {
                    return q.select(
                            administrator.fetch(
                                    AdministratorFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .roles()
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ADMINISTRATOR tb_1_ " +
                                    "where tb_1_.DELETED <> ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_2_.ADMINISTRATOR_ID, tb_1_.ID " +
                                    "from ROLE tb_1_ " +
                                    "inner join ADMINISTRATOR_ROLE_MAPPING tb_2_ on tb_1_.ID = tb_2_.ROLE_ID " +
                                    "where tb_2_.ADMINISTRATOR_ID in (?, ?) " +
                                    "and tb_1_.DELETED <> ?"
                    ).variables(1L, 3L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"a_1\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[" +
                                    "--->--->--->{\"id\":100}" +
                                    "--->--->]," +
                                    "--->--->\"id\":1" +
                                    "--->},{" +
                                    "--->--->\"name\":\"a_3\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[" +
                                    "--->--->--->{\"id\":100}" +
                                    "--->--->]," +
                                    "--->--->\"id\":3" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryUndeletedAdministratorWithRoles() {
        executeAndExpect(
                lambdaClient.createQuery(AdministratorTable.class, (q, administrator) -> {
                    return q.select(
                            administrator.fetch(
                                    AdministratorFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .roles(
                                                    RoleFetcher.$
                                                            .allScalarFields()
                                                            .deleted()
                                            )
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ADMINISTRATOR tb_1_ " +
                                    "where tb_1_.DELETED <> ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_2_.ADMINISTRATOR_ID, tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "inner join ADMINISTRATOR_ROLE_MAPPING tb_2_ on tb_1_.ID = tb_2_.ROLE_ID " +
                                    "where tb_2_.ADMINISTRATOR_ID in (?, ?) " +
                                    "and tb_1_.DELETED <> ?"
                    ).variables(1L, 3L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"a_1\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[" +
                                    "--->--->--->{" +
                                    "--->--->--->--->\"name\":\"r_1\"," +
                                    "--->--->--->--->\"deleted\":false," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":100" +
                                    "--->--->--->}" +
                                    "--->--->]," +
                                    "--->--->\"id\":1" +
                                    "--->},{" +
                                    "--->--->\"name\":\"a_3\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[" +
                                    "--->--->--->{" +
                                    "--->--->--->--->\"name\":\"r_1\"," +
                                    "--->--->--->--->\"deleted\":false," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":100" +
                                    "--->--->--->}" +
                                    "--->--->]," +
                                    "--->--->\"id\":3" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryUndeletedRoleAndIdOnlyAdministrators() {
        executeAndExpect(
                lambdaClient.createQuery(RoleTable.class, (q, role) -> {
                    return q.select(
                            role.fetch(
                                    RoleFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .administrators()
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.DELETED <> ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID " +
                                    "from ADMINISTRATOR tb_1_ " +
                                    "inner join ADMINISTRATOR_ROLE_MAPPING tb_2_ on tb_1_.ID = tb_2_.ADMINISTRATOR_ID " +
                                    "where tb_2_.ROLE_ID = ? and tb_1_.DELETED <> ?"
                    ).variables(100L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"r_1\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"administrators\":[" +
                                    "--->--->--->{\"id\":1}," +
                                    "--->--->--->{\"id\":3}" +
                                    "--->--->]," +
                                    "--->--->\"id\":100" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryUndeletedRoleAndAdministrators() {
        executeAndExpect(
                lambdaClient.createQuery(RoleTable.class, (q, role) -> {
                    return q.select(
                            role.fetch(
                                    RoleFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .administrators(
                                                    AdministratorFetcher.$
                                                            .allScalarFields()
                                                            .deleted()
                                            )
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.DELETED <> ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ADMINISTRATOR tb_1_ " +
                                    "inner join ADMINISTRATOR_ROLE_MAPPING tb_2_ on tb_1_.ID = tb_2_.ADMINISTRATOR_ID " +
                                    "where tb_2_.ROLE_ID = ? and tb_1_.DELETED <> ?"
                    ).variables(100L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"r_1\"," +
                                    "--->--->\"deleted\":false," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"administrators\":[" +
                                    "--->--->--->{" +
                                    "--->--->--->--->\"name\":\"a_1\"," +
                                    "--->--->--->--->\"deleted\":false," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":1" +
                                    "--->--->--->},{" +
                                    "--->--->--->--->\"name\":\"a_3\"," +
                                    "--->--->--->--->\"deleted\":false," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":3" +
                                    "--->--->--->}" +
                                    "--->--->]," +
                                    "--->--->\"id\":100" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryDeletedRoleWithIdOnlyPermissions() {
        executeAndExpect(
                lambdaClientForDeletedData.createQuery(RoleTable.class, (q, role) -> {
                    return q.select(
                            role.fetch(
                                    RoleFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .permissions()
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.DELETED = ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID " +
                                    "from PERMISSION tb_1_ " +
                                    "where tb_1_.ROLE_ID = ? and tb_1_.DELETED = ?"
                    ).variables(200L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"r_2\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"permissions\":[" +
                                    "--->--->--->{\"id\":4000}" +
                                    "--->--->]," +
                                    "--->--->\"id\":200" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryDeletedRoleWithPermissions() {
        executeAndExpect(
                lambdaClientForDeletedData.createQuery(RoleTable.class, (q, role) -> {
                    return q.select(
                            role.fetch(
                                    RoleFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .permissions(
                                                    PermissionFetcher.$
                                                            .allScalarFields()
                                                            .deleted()
                                            )
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.DELETED = ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from PERMISSION tb_1_ " +
                                    "where tb_1_.ROLE_ID = ? and tb_1_.DELETED = ?"
                    ).variables(200L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"r_2\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"permissions\":[" +
                                    "--->--->--->{" +
                                    "--->--->--->--->\"name\":\"p_4\"," +
                                    "--->--->--->--->\"deleted\":true," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":4000" +
                                    "--->--->--->}" +
                                    "--->--->]," +
                                    "--->--->\"id\":200" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryDeletedPermissionAndIdOnlyRole() {
        executeAndExpect(
                lambdaClientForDeletedData.createQuery(PermissionTable.class, (q, permission) -> {
                    return q.select(
                            permission.fetch(
                                    PermissionFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .role()
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED, tb_1_.ROLE_ID " +
                                    "from PERMISSION tb_1_ " +
                                    "where tb_1_.DELETED = ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID from ROLE tb_1_ " +
                                    "where tb_1_.ID in (?, ?) " +
                                    "and tb_1_.DELETED = ?"
                    ).variables(100L, 200L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"p_2\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"role\":null," +
                                    "--->--->\"id\":2000" +
                                    "--->},{" +
                                    "--->--->\"name\":\"p_4\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"role\":{\"id\":200}," +
                                    "--->--->\"id\":4000" +
                                    "--->}]"
                    );
                }
        );
    }

    @Test
    public void testQueryDeletedPermissionAndRole() {
        executeAndExpect(
                lambdaClientForDeletedData.createQuery(PermissionTable.class, (q, permission) -> {
                    return q.select(
                            permission.fetch(
                                    PermissionFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .role(
                                                    RoleFetcher.$
                                                            .allScalarFields()
                                                            .deleted()
                                            )
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED, tb_1_.ROLE_ID " +
                                    "from PERMISSION tb_1_ " +
                                    "where tb_1_.DELETED = ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.ID in (?, ?) and tb_1_.DELETED = ?"
                    ).variables(100L, 200L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"p_2\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"role\":null," +
                                    "--->--->\"id\":2000" +
                                    "--->},{" +
                                    "--->--->\"name\":\"p_4\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"role\":{" +
                                    "--->--->--->\"name\":\"r_2\"," +
                                    "--->--->--->\"deleted\":true," +
                                    "--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->\"id\":200" +
                                    "--->--->}," +
                                    "--->--->\"id\":4000" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryDeletedAdministratorWithIdOnlyRoles() {
        executeAndExpect(
                lambdaClientForDeletedData.createQuery(AdministratorTable.class, (q, administrator) -> {
                    return q.select(
                            administrator.fetch(
                                    AdministratorFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .roles()
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ADMINISTRATOR tb_1_ " +
                                    "where tb_1_.DELETED = ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_2_.ADMINISTRATOR_ID, tb_1_.ID " +
                                    "from ROLE tb_1_ " +
                                    "inner join ADMINISTRATOR_ROLE_MAPPING tb_2_ on tb_1_.ID = tb_2_.ROLE_ID " +
                                    "where tb_2_.ADMINISTRATOR_ID in (?, ?, ?) " +
                                    "and tb_1_.DELETED = ?"
                    ).variables(-1L, 2L, 4L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"a_-1\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[]," +
                                    "--->--->\"id\":-1" +
                                    "--->}," +
                                    "--->{" +
                                    "--->--->\"name\":\"a_2\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[" +
                                    "--->--->--->{\"id\":200}" +
                                    "--->--->]," +
                                    "--->--->\"id\":2" +
                                    "--->},{" +
                                    "--->--->\"name\":\"a_4\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[" +
                                    "--->--->--->{\"id\":200}" +
                                    "--->--->]," +
                                    "--->--->\"id\":4" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryDeletedAdministratorWithRoles() {
        executeAndExpect(
                lambdaClientForDeletedData.createQuery(AdministratorTable.class, (q, administrator) -> {
                    return q.select(
                            administrator.fetch(
                                    AdministratorFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .roles(
                                                    RoleFetcher.$
                                                            .allScalarFields()
                                                            .deleted()
                                            )
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ADMINISTRATOR tb_1_ " +
                                    "where tb_1_.DELETED = ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_2_.ADMINISTRATOR_ID, tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "inner join ADMINISTRATOR_ROLE_MAPPING tb_2_ on tb_1_.ID = tb_2_.ROLE_ID " +
                                    "where tb_2_.ADMINISTRATOR_ID in (?, ?, ?) " +
                                    "and tb_1_.DELETED = ?"
                    ).variables(-1L, 2L, 4L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"a_-1\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[]," +
                                    "--->--->\"id\":-1" +
                                    "--->}," +
                                    "--->{" +
                                    "--->--->\"name\":\"a_2\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[" +
                                    "--->--->--->{" +
                                    "--->--->--->--->\"name\":\"r_2\"," +
                                    "--->--->--->--->\"deleted\":true," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":200" +
                                    "--->--->--->}" +
                                    "--->--->]," +
                                    "--->--->\"id\":2" +
                                    "--->},{" +
                                    "--->--->\"name\":\"a_4\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"roles\":[" +
                                    "--->--->--->{" +
                                    "--->--->--->--->\"name\":\"r_2\"," +
                                    "--->--->--->--->\"deleted\":true," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":200" +
                                    "--->--->--->}" +
                                    "--->--->]," +
                                    "--->--->\"id\":4" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryDeletedRoleAndIdOnlyAdministrators() {
        executeAndExpect(
                lambdaClientForDeletedData.createQuery(RoleTable.class, (q, role) -> {
                    return q.select(
                            role.fetch(
                                    RoleFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .administrators()
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.DELETED = ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID " +
                                    "from ADMINISTRATOR tb_1_ " +
                                    "inner join ADMINISTRATOR_ROLE_MAPPING tb_2_ on tb_1_.ID = tb_2_.ADMINISTRATOR_ID " +
                                    "where tb_2_.ROLE_ID = ? and tb_1_.DELETED = ?"
                    ).variables(200L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"r_2\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"administrators\":[" +
                                    "--->--->--->{\"id\":2}," +
                                    "--->--->--->{\"id\":4}" +
                                    "--->--->]," +
                                    "--->--->\"id\":200" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }

    @Test
    public void testQueryDeletedRoleAndAdministrators() {
        executeAndExpect(
                lambdaClientForDeletedData.createQuery(RoleTable.class, (q, role) -> {
                    return q.select(
                            role.fetch(
                                    RoleFetcher.$
                                            .allScalarFields()
                                            .deleted()
                                            .administrators(
                                                    AdministratorFetcher.$
                                                            .allScalarFields()
                                                            .deleted()
                                            )
                            )
                    );
                }),
                ctx -> {
                    ctx.sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ROLE tb_1_ " +
                                    "where tb_1_.DELETED = ?"
                    ).variables(true);
                    ctx.statement(1).sql(
                            "select tb_1_.ID, tb_1_.NAME, tb_1_.CREATED_TIME, tb_1_.MODIFIED_TIME, tb_1_.DELETED " +
                                    "from ADMINISTRATOR tb_1_ " +
                                    "inner join ADMINISTRATOR_ROLE_MAPPING tb_2_ on tb_1_.ID = tb_2_.ADMINISTRATOR_ID " +
                                    "where tb_2_.ROLE_ID = ? and tb_1_.DELETED = ?"
                    ).variables(200L, true);
                    ctx.rows(
                            "[" +
                                    "--->{" +
                                    "--->--->\"name\":\"r_2\"," +
                                    "--->--->\"deleted\":true," +
                                    "--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->\"administrators\":[" +
                                    "--->--->--->{" +
                                    "--->--->--->--->\"name\":\"a_2\"," +
                                    "--->--->--->--->\"deleted\":true," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":2" +
                                    "--->--->--->},{" +
                                    "--->--->--->--->\"name\":\"a_4\"," +
                                    "--->--->--->--->\"deleted\":true," +
                                    "--->--->--->--->\"createdTime\":\"2022-10-03 00:00:00\"," +
                                    "--->--->--->--->\"modifiedTime\":\"2022-10-03 00:10:00\"," +
                                    "--->--->--->--->\"id\":4" +
                                    "--->--->--->}" +
                                    "--->--->]," +
                                    "--->--->\"id\":200" +
                                    "--->}" +
                                    "]"
                    );
                }
        );
    }
}
