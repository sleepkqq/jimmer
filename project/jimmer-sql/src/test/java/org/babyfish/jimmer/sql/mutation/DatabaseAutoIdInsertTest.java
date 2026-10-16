package org.babyfish.jimmer.sql.mutation;

import org.babyfish.jimmer.sql.ast.mutation.SaveMode;
import org.babyfish.jimmer.sql.common.AbstractMutationTest;
import org.babyfish.jimmer.sql.common.NativeDatabases;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.dialect.MySqlDialect;
import org.babyfish.jimmer.sql.dialect.PostgresDialect;
import org.babyfish.jimmer.sql.meta.impl.IdentityIdGenerator;
import org.babyfish.jimmer.sql.model.TreeNode;
import org.babyfish.jimmer.sql.model.TreeNodeDraft;
import org.babyfish.jimmer.sql.runtime.DbLiteral;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DatabaseAutoIdInsertTest extends AbstractMutationTest {

    @Test
    public void testSequenceByH2() {
        executeAndExpectResult(
                getSqlClient(
                        it -> it.setDialect(new H2Dialect())
                ).getEntities().saveCommand(
                        TreeNodeDraft.$.produce(treeNode -> {
                            treeNode.setName("Computer");
                            treeNode.setParent(null);
                        })
                ).setMode(SaveMode.INSERT_ONLY),
                ctx -> {
                    ctx.statement(it -> {
                        it.sql("select nextval('tree_node_id_seq')");
                    });
                    ctx.statement(it -> {
                        it.sql("insert into TREE_NODE(NODE_ID, NAME, PARENT_ID) values(?, ?, ?)");
                        it.variables(UNKNOWN_VARIABLE, "Computer", new DbLiteral.DbNull(long.class));
                    });
                    ctx.entity(it -> {
                        it.modified(entity -> {
                            TreeNode node = (TreeNode) entity;
                            Assertions.assertTrue(node.id() > 0L);
                            Assertions.assertEquals("Computer", node.name());
                            Assertions.assertNull(node.parent());
                        });
                    });
                }
        );
    }

    @Test
    public void testSequenceByPostgres() {

        NativeDatabases.assumeNativeDatabase();

        jdbc(NativeDatabases.POSTGRES_DATA_SOURCE, false, con -> {
            con
                    .createStatement()
                    .executeUpdate("alter sequence tree_node_id_seq restart with 100");
        });

        executeAndExpectResult(
                NativeDatabases.POSTGRES_DATA_SOURCE,
                getSqlClient(
                        it -> it.setDialect(new PostgresDialect())
                ).getEntities().saveCommand(
                        TreeNodeDraft.$.produce(treeNode -> {
                            treeNode.setName("Computer");
                            treeNode.setParent(null);
                        })
                ).setMode(SaveMode.INSERT_ONLY),
                ctx -> {
                    ctx.statement(it -> {
                        it.sql("select nextval('tree_node_id_seq')");
                    });
                    ctx.statement(it -> {
                        it.sql("insert into TREE_NODE(NODE_ID, NAME, PARENT_ID) values(?, ?, ?)");
                        it.variables(100L, "Computer", new DbLiteral.DbNull(long.class));
                    });
                    ctx.entity(it -> {
                        it.modified("{\"id\":100,\"name\":\"Computer\",\"parent\":null}");
                    });
                }
        );
    }

    @Test
    public void testIdentityByMySql() {

        NativeDatabases.assumeNativeDatabase();

        jdbc(NativeDatabases.MYSQL_DATA_SOURCE, false, con -> {
            // A previous MySQL identity test may already have inserted a
            // tree_node row with id 100; in that case MySQL ignores the lower
            // requested auto_increment. Drop only these test rows first so the
            // requested value takes effect.
            con.createStatement()
                    .executeUpdate("delete from tree_node where name = 'Computer'");
            con.createStatement()
                    .executeUpdate("alter table tree_node auto_increment = 100");
        });

        executeAndExpectResult(
                NativeDatabases.MYSQL_DATA_SOURCE,
                getSqlClient(
                        it -> it
                                .setDialect(new MySqlDialect())
                                .setIdGenerator(TreeNode.class, IdentityIdGenerator.INSTANCE)
                ).getEntities().saveCommand(
                        TreeNodeDraft.$.produce(treeNode -> {
                            treeNode.setName("Computer");
                            treeNode.setParent(null);
                        })
                ).setMode(SaveMode.INSERT_ONLY),
                ctx -> {
                    ctx.statement(it -> {
                        it.sql("insert into TREE_NODE(NAME, PARENT_ID) values(?, ?)");
                        it.variables("Computer", new DbLiteral.DbNull(long.class));
                    });
                    ctx.entity(it -> {
                        it.modified("{\"id\":100,\"name\":\"Computer\",\"parent\":null}");
                    });
                }
        );
    }

    @Test
    public void testIdentityByMySqlBatch() {

        NativeDatabases.assumeNativeDatabase();

        jdbc(NativeDatabases.MYSQL_BATCH_DATA_SOURCE, false, con -> {
            // Drop rows left by an earlier run of this or the sibling identity test;
            // otherwise MySQL ignores a lower requested auto_increment.
            con
                    .createStatement()
                    .executeUpdate("delete from tree_node where name = 'Computer'");
            con
                    .createStatement()
                    .executeUpdate("alter table tree_node auto_increment = 100");
        });

        executeAndExpectResult(
                NativeDatabases.MYSQL_BATCH_DATA_SOURCE,
                getSqlClient(
                        it -> it
                                .setDialect(new MySqlDialect())
                                .setIdGenerator(TreeNode.class, IdentityIdGenerator.INSTANCE)
                ).getEntities().saveCommand(
                        TreeNodeDraft.$.produce(treeNode -> {
                            treeNode.setName("Computer");
                            treeNode.setParent(null);
                        })
                ).setMode(SaveMode.INSERT_ONLY),
                ctx -> {
                    ctx.statement(it -> {
                        it.sql("insert into TREE_NODE(NAME, PARENT_ID) values(?, ?)");
                        it.variables("Computer", new DbLiteral.DbNull(long.class));
                    });
                    ctx.entity(it -> {
                        it.modified("{\"id\":100,\"name\":\"Computer\",\"parent\":null}");
                    });
                }
        );
    }
}
