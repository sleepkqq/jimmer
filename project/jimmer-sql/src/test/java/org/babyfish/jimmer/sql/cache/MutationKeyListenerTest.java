package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;
import org.babyfish.jimmer.sql.common.AbstractMutationTest;
import org.babyfish.jimmer.sql.common.CacheImpl;
import org.babyfish.jimmer.sql.event.TriggerType;
import org.babyfish.jimmer.sql.model.Book;
import org.babyfish.jimmer.sql.model.BookDraft;
import org.babyfish.jimmer.sql.model.BookProps;
import org.babyfish.jimmer.sql.model.BookTable;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.MutationKeyListener;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.babyfish.jimmer.sql.common.Constants.*;
import static org.junit.jupiter.api.Assertions.*;

public class MutationKeyListenerTest extends AbstractMutationTest {

    @Test
    public void testInsert() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
            sqlClient.saveCommand(
                    BookDraft.$.produce(draft -> {
                        draft.setId(id);
                        draft.setName("New Book");
                        draft.setEdition(1);
                        draft.setPrice(BigDecimal.valueOf(10));
                        draft.setStoreId(oreillyId);
                    })
            ).setMode(SaveMode.INSERT_ONLY).execute(con);
            assertEquals(
                    setOf(id),
                    listener.entityKeys(Book.class)
            );
            assertEquals(0, listener.associationEvents.size());
        });
    }

    @Test
    public void testUpdate() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            sqlClient.saveCommand(
                    BookDraft.$.produce(draft -> {
                        draft.setId(learningGraphQLId1);
                        draft.setName("Changed");
                    })
            ).setMode(SaveMode.UPDATE_ONLY).execute(con);
            assertEquals(setOf(learningGraphQLId1), listener.entityKeys(Book.class));
        });
    }

    @Test
    public void testUpsert() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            sqlClient.saveCommand(
                    BookDraft.$.produce(draft -> {
                        draft.setName("Learning GraphQL");
                        draft.setEdition(1);
                        draft.setPrice(BigDecimal.valueOf(51));
                        draft.setStoreId(oreillyId);
                    })
            ).setMode(SaveMode.UPSERT).execute(con);
            assertEquals(setOf(learningGraphQLId1), listener.entityKeys(Book.class));
        });
    }

    @Test
    public void testDeleteById() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            sqlClient.getEntities().deleteCommand(Book.class, learningGraphQLId1).execute(con);
            assertEquals(setOf(learningGraphQLId1), listener.entityKeys(Book.class));
        });
    }

    @Test
    public void testDeleteByWhere() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            sqlClient.createDelete(BookTable.$)
                    .where(BookTable.$.name().eq("Learning GraphQL"))
                    .execute(con);
            assertEquals(
                    setOf(learningGraphQLId1, learningGraphQLId2, learningGraphQLId3),
                    listener.entityKeys(Book.class)
            );
        });
    }

    @Test
    public void testBulkUpdate() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            sqlClient.createUpdate(BookTable.$)
                    .set(BookTable.$.price(), BigDecimal.valueOf(60))
                    .where(BookTable.$.name().eq("Learning GraphQL"))
                    .execute(con);
            assertEquals(
                    setOf(learningGraphQLId1, learningGraphQLId2, learningGraphQLId3),
                    listener.entityKeys(Book.class)
            );
        });
    }

    @Test
    public void testBulkUpdateReturning() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            List<UUID> ids = sqlClient.createUpdate(BookTable.$)
                    .set(BookTable.$.price(), BigDecimal.valueOf(60))
                    .where(BookTable.$.name().eq("Learning GraphQL"))
                    .returning(BookTable.$.id())
                    .execute(con);
            assertEquals(
                    setOf(learningGraphQLId1, learningGraphQLId2, learningGraphQLId3),
                    listener.entityKeys(Book.class)
            );
            assertEquals(
                    setOf(learningGraphQLId1, learningGraphQLId2, learningGraphQLId3),
                    new LinkedHashSet<>(ids)
            );
        });
    }

    @Test
    public void testAssociationAppend() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            sqlClient.getAssociations(BookProps.AUTHORS)
                    .saveCommand(learningGraphQLId1, sammerId)
                    .execute(con);
            assertEquals(
                    setOf(learningGraphQLId1),
                    listener.associationSources(BookProps.AUTHORS.unwrap())
            );
            assertEquals(0, listener.entityEvents.size());
        });
    }

    @Test
    public void testAssociationDelete() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.BINLOG_ONLY, listener);
        jdbc(con -> {
            sqlClient.getAssociations(BookProps.AUTHORS)
                    .deleteCommand(learningGraphQLId1, alexId)
                    .execute(con);
            assertEquals(
                    setOf(learningGraphQLId1),
                    listener.associationSources(BookProps.AUTHORS.unwrap())
            );
        });
    }

    @Test
    public void testTransactionTriggerDoesNotFire() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        JSqlClient sqlClient = sqlClient(TriggerType.TRANSACTION_ONLY, listener);
        jdbc(con -> {
            sqlClient.saveCommand(
                    BookDraft.$.produce(draft -> {
                        draft.setId(learningGraphQLId1);
                        draft.setName("Changed");
                    })
            ).setMode(SaveMode.UPDATE_ONLY).execute(con);
            assertTrue(listener.entityEvents.isEmpty());
            assertTrue(listener.associationEvents.isEmpty());
        });
    }

    @Test
    public void testNoListenerKeepsSqlUnchanged() {
        List<String> withoutListener = updateSql(
                sqlClient(TriggerType.BINLOG_ONLY, testConnectionManager())
        );
        // Without a listener the mutation stays a single statement: no key pre-select.
        assertEquals(1, withoutListener.size());
        assertTrue(withoutListener.get(0).startsWith("update BOOK"));
    }

    @Test
    public void testListenerWithoutCachesKeepsSqlUnchanged() {
        RecordingConnectionManager listener = new RecordingConnectionManager();
        List<String> updates = updateSql(sqlClientWithoutCaches(listener));
        List<String> deletes = deleteSql(sqlClientWithoutCaches(listener));
        // No cache is keyed by the mutated ids, so no key pre-select and nothing to report.
        assertEquals(updateSql(sqlClientWithoutCaches(testConnectionManager())), updates);
        assertEquals(deleteSql(sqlClientWithoutCaches(testConnectionManager())), deletes);
        assertTrue(listener.entityEvents.isEmpty());
        assertTrue(listener.associationEvents.isEmpty());
    }

    @Test
    public void testDeleteByWhereSqlUnchangedWithoutListener() {
        List<String> withListener = deleteSql(
                sqlClient(TriggerType.BINLOG_ONLY, new RecordingConnectionManager())
        );
        List<String> withoutListener = deleteSql(
                sqlClient(TriggerType.BINLOG_ONLY, testConnectionManager())
        );
        assertEquals(withoutListener, withListener);
    }

    private List<String> updateSql(JSqlClient sqlClient) {
        List<String> sqlList = new ArrayList<>();
        jdbc(con -> {
            clearExecutions();
            sqlClient.createUpdate(BookTable.$)
                    .set(BookTable.$.price(), BigDecimal.valueOf(60))
                    .where(BookTable.$.name().eq("Learning GraphQL"))
                    .execute(con);
            for (Execution execution : getExecutions()) {
                sqlList.add(execution.getSql());
            }
        });
        return sqlList;
    }

    private List<String> deleteSql(JSqlClient sqlClient) {
        List<String> sqlList = new ArrayList<>();
        jdbc(con -> {
            clearExecutions();
            sqlClient.createDelete(BookTable.$)
                    .where(BookTable.$.name().eq("Learning GraphQL"))
                    .execute(con);
            for (Execution execution : getExecutions()) {
                sqlList.add(execution.getSql());
            }
        });
        return sqlList;
    }

    private JSqlClient sqlClient(TriggerType triggerType, ConnectionManager connectionManager) {
        return getSqlClient(builder -> builder
                .setTriggerType(triggerType)
                .setConnectionManager(connectionManager)
                .setCaches(cfg -> cfg.setCacheFactory(
                        new CacheFactory() {
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
                        }
                ))
        );
    }

    private JSqlClient sqlClientWithoutCaches(ConnectionManager connectionManager) {
        return getSqlClient(builder -> builder
                .setTriggerType(TriggerType.BINLOG_ONLY)
                .setConnectionManager(connectionManager)
        );
    }

    private static Set<Object> setOf(Object... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static class RecordingConnectionManager implements ConnectionManager, MutationKeyListener {

        private final List<ImmutableType> entityEvents = new ArrayList<>();
        private final List<Collection<?>> entityKeys = new ArrayList<>();
        private final List<ImmutableProp> associationEvents = new ArrayList<>();
        private final List<Collection<?>> associationKeys = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
            Object[] resultBox = new Object[1];
            if (con == null) {
                jdbc(c -> resultBox[0] = block.apply(c));
            } else {
                resultBox[0] = block.apply(con);
            }
            return (R) resultBox[0];
        }

        @Override
        public void entitiesMutated(Connection con, ImmutableType type, Collection<?> ids) {
            entityEvents.add(type);
            entityKeys.add(new LinkedHashSet<>(ids));
        }

        @Override
        public void associationsMutated(Connection con, ImmutableProp prop, Collection<?> sourceIds) {
            associationEvents.add(prop);
            associationKeys.add(new LinkedHashSet<>(sourceIds));
        }

        Set<Object> entityKeys(Class<?> entityType) {
            Set<Object> ret = new LinkedHashSet<>();
            for (int i = 0; i < entityEvents.size(); i++) {
                if (entityEvents.get(i).getJavaClass() == entityType) {
                    ret.addAll(entityKeys.get(i));
                }
            }
            return ret;
        }

        Set<Object> associationSources(ImmutableProp prop) {
            Set<Object> ret = new LinkedHashSet<>();
            for (int i = 0; i < associationEvents.size(); i++) {
                if (associationEvents.get(i) == prop) {
                    ret.addAll(associationKeys.get(i));
                }
            }
            return ret;
        }
    }
}
