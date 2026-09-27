package org.babyfish.jimmer.sql.json;

import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.mutation.BatchSaveResult;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.ast.tuple.Tuple5;
import org.babyfish.jimmer.sql.model.pg.JsonWrapper;
import org.babyfish.jimmer.sql.model.pg.JsonWrapperDraft;
import org.babyfish.jimmer.sql.model.pg.JsonWrapperFetcher;
import org.babyfish.jimmer.sql.model.pg.JsonWrapperTable;
import org.babyfish.jimmer.sql.model.pg.Point;
import org.babyfish.jimmer.sql.runtime.DbLiteral;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.util.*;

public class ScalarProviderTest extends AbstractJsonTest {

    @Test
    public void test() {
        sqlClient().getEntities().save(
                JsonWrapperDraft.$.produce(draft -> {
                    draft.setId(1L);
                    draft.setPoint(new Point(3, 4));
                    draft.setTags(Arrays.asList("java", "kotlin"));
                    draft.setScores(Collections.singletonMap(1L, 100));
                    draft.setComplexList(
                            Arrays.asList(
                                    Arrays.asList("1-1", "1-2"),
                                    Arrays.asList("2-1", "2-2")
                            )
                    );
                    draft.setComplexMap(
                            Collections.singletonMap(
                                    "key",
                                    Collections.singletonMap("nested-key", "value")
                            )
                    );
                })
        );
        JsonWrapper wrapper = sqlClient().getEntities().findById(JsonWrapper.class, 1L);
        Assertions.assertEquals(
                "{" +
                        "\"id\":1," +
                        "\"point\":{\"_x\":3,\"_y\":4}," +
                        "\"tags\":[\"java\",\"kotlin\"]," +
                        "\"scores\":{\"1\":100}," +
                        "\"complexList\":[[\"1-1\",\"1-2\"],[\"2-1\",\"2-2\"]]," +
                        "\"complexMap\":{\"key\":{\"nested-key\":\"value\"}}" +
                        "}",
                wrapper.toString()
        );

        JsonWrapperTable table = JsonWrapperTable.$;

        Tuple5<Point, List<String>, Map<Long, Integer>, List<List<String>>, Map<String, Map<String, String>>> tuple =
                sqlClient()
                        .createQuery(table)
                        .where(table.id().eq(1L))
                        .select(
                                table.point(),
                                table.tags(),
                                table.scores(),
                                table.complexList(),
                                table.complexMap()
                        )
                        .fetchOne();
        Assertions.assertEquals(
                "Tuple5(" +
                        "_1=Point{x=3, y=4}, " +
                        "_2=[java, kotlin], " +
                        "_3={1=100}, " +
                        "_4=[[1-1, 1-2], [2-1, 2-2]], " +
                        "_5={key={nested-key=value}}" +
                        ")",
                tuple.toString()
        );

        sqlClient().getEntities().save(
                JsonWrapperDraft.$.produce(draft -> {
                    draft.setId(1L);
                    draft.setPoint(new Point(4, 3));
                    draft.setTags(Arrays.asList("kotlin", "java"));
                })
        );
        wrapper = sqlClient().getEntities().findById(JsonWrapper.class, 1L);
        Assertions.assertEquals(
                "{" +
                        "\"id\":1," +
                        "\"point\":{\"_x\":4,\"_y\":3}," +
                        "\"tags\":[\"kotlin\",\"java\"]," +
                        "\"scores\":{\"1\":100}," +
                        "\"complexList\":[[\"1-1\",\"1-2\"],[\"2-1\",\"2-2\"]]," +
                        "\"complexMap\":{\"key\":{\"nested-key\":\"value\"}}" +
                        "}",
                wrapper.toString()
        );

        sqlClient()
                .createUpdate(table)
                .set(table.tags(), Arrays.asList("java", "kotlin", "scala"))
                .where(table.tags().eq(Arrays.asList("kotlin", "java")))
                .execute();
        wrapper = sqlClient().getEntities().findById(JsonWrapper.class, 1L);
        Assertions.assertEquals(
                "{" +
                        "\"id\":1," +
                        "\"point\":{\"_x\":4,\"_y\":3}," +
                        "\"tags\":[\"java\",\"kotlin\",\"scala\"]," +
                        "\"scores\":{\"1\":100}," +
                        "\"complexList\":[[\"1-1\",\"1-2\"],[\"2-1\",\"2-2\"]]," +
                        "\"complexMap\":{\"key\":{\"nested-key\":\"value\"}}" +
                        "}",
                wrapper.toString()
        );

        sqlClient()
                .createUpdate(table)
                .set(table.scores(), Collections.singletonMap(2L, 200))
                .where(
                        Expression.tuple(table.tags(), table.scores()).eq(
                                new Tuple2<>(
                                        Arrays.asList("java", "kotlin", "scala"),
                                        Collections.singletonMap(1L, 100)
                                )
                        )
                )
                .execute();
        wrapper = sqlClient().getEntities().findById(JsonWrapper.class, 1L);
        Assertions.assertEquals(
                "{" +
                        "\"id\":1," +
                        "\"point\":{\"_x\":4,\"_y\":3}," +
                        "\"tags\":[\"java\",\"kotlin\",\"scala\"]," +
                        "\"scores\":{\"2\":200}," +
                        "\"complexList\":[[\"1-1\",\"1-2\"],[\"2-1\",\"2-2\"]],\"complexMap\":{\"key\":{\"nested-key\":\"value\"}}" +
                        "}",
                wrapper.toString()
        );
    }

    @Test
    public void testNull() {
        sqlClient().getEntities().saveCommand(
                JsonWrapperDraft.$.produce(draft -> {
                    draft.setId(1L);
                    draft.setPoint(null);
                    draft.setTags(null);
                    draft.setScores(null);
                    draft.setComplexList(null);
                    draft.setComplexMap(null);
                })
        ).setMode(SaveMode.INSERT_ONLY).execute();
        batchSql(
                "insert into pg_json_wrapper(ID, json_1, json_2, json_3, json_4, json_5) " +
                        "values(?, ?, ?, ?, ?, ?)",
                Arrays.asList(
                    1L,
                    new DbLiteral.DbNull(PGobject.class),
                    new DbLiteral.DbNull(PGobject.class),
                    new DbLiteral.DbNull(PGobject.class),
                    new DbLiteral.DbNull(PGobject.class),
                    new DbLiteral.DbNull(PGobject.class)
                )
        );
    }

    @Test
    public void testUpdateReturningJsonNull() {
        sqlClient().getEntities().saveCommand(
                JsonWrapperDraft.$.produce(draft -> draft
                        .setId(1L)
                        .setTags(Collections.singletonList("persisted"))
                        .setScores(Collections.singletonMap(1L, 100))
                        .setComplexMap(Collections.singletonMap("key", Collections.singletonMap("nested", "old"))))
        ).setMode(SaveMode.INSERT_ONLY).execute();

        JsonWrapper modified = sqlClient().getEntities().saveCommand(
                JsonWrapperDraft.$.produce(draft -> draft
                        .setId(1L)
                        .setScores(null)
                        .setComplexMap(null))
        ).setMode(SaveMode.UPDATE_ONLY)
                .setSaveResultReadsAllProperties()
                .execute(JsonWrapperFetcher.$.tags().scores().complexMap())
                .getModifiedEntity();

        Assertions.assertEquals(Collections.singletonList("persisted"), modified.tags());
        Assertions.assertNull(modified.scores());
        Assertions.assertNull(modified.complexMap());
        JsonWrapperTable table = JsonWrapperTable.$;
        Assertions.assertEquals(
                Collections.singletonList(1L),
                sqlClient().createQuery(table)
                        .where(table.scores().isNull(), table.complexMap().isNull())
                        .select(table.id())
                        .execute()
        );
    }

    @Test
    public void testBatchUpdateReturningJsonValues() {
        sqlClient().saveEntitiesCommand(Arrays.asList(
                JsonWrapperDraft.$.produce(draft -> draft.setId(1L).setTags(Collections.singletonList("persisted"))),
                JsonWrapperDraft.$.produce(draft -> draft.setId(2L).setTags(Collections.singletonList("persisted")))
        )).setMode(SaveMode.INSERT_ONLY).execute();

        Map<Long, Integer> scores = Collections.singletonMap(7L, 70);
        Map<String, Map<String, String>> json = Collections.singletonMap("key", Collections.singletonMap("nested", "new"));
        for (boolean clear : new boolean[] { false, true }) {
            BatchSaveResult<JsonWrapper> result = sqlClient().saveEntitiesCommand(Arrays.asList(
                    JsonWrapperDraft.$.produce(draft -> draft.setId(1L).setScores(null).setComplexMap(null)),
                    JsonWrapperDraft.$.produce(draft -> draft.setId(2L)
                            .setScores(clear ? null : scores)
                            .setComplexMap(clear ? null : json))
            )).setMode(SaveMode.UPDATE_ONLY)
                    .setSaveResultReadsAllProperties()
                    .execute(JsonWrapperFetcher.$.tags().scores().complexMap());

            Assertions.assertEquals(2, result.getItems().size());
            for (BatchSaveResult.Item<JsonWrapper> item : result.getItems()) {
                JsonWrapper modified = item.getModifiedEntity();
                boolean hasJson = modified.id() == 2L && !clear;
                Assertions.assertEquals(Collections.singletonList("persisted"), modified.tags());
                Assertions.assertEquals(hasJson ? scores : null, modified.scores());
                Assertions.assertEquals(hasJson ? json : null, modified.complexMap());
            }
            JsonWrapperTable table = JsonWrapperTable.$;
            Assertions.assertEquals(
                    clear ? Arrays.asList(1L, 2L) : Collections.singletonList(1L),
                    sqlClient().createQuery(table)
                            .where(table.scores().isNull(), table.complexMap().isNull())
                            .orderBy(table.id())
                            .select(table.id())
                            .execute()
            );
        }
    }

    @Test
    public void testWhere() {
        sqlClient().getEntities().save(
                JsonWrapperDraft.$.produce(draft -> {
                    draft.setId(1L);
                    draft.setPoint(new Point(3, 4));
                    draft.setTags(Arrays.asList("java", "kotlin"));
                    draft.setScores(Collections.singletonMap(1L, 100));
                    draft.setComplexList(
                            Arrays.asList(
                                    Arrays.asList("1-1", "1-2"),
                                    Arrays.asList("2-1", "2-2")
                            )
                    );
                    draft.setComplexMap(
                            Collections.singletonMap(
                                    "key",
                                    Collections.singletonMap("nested-key", "value")
                            )
                    );
                })
        );

        JsonWrapperTable table = JsonWrapperTable.$;

        List<JsonWrapper> jsonWrappers = sqlClient()
                .createQuery(table)
                .where(table.point().eq(new Point(3, 4)))
                .where(table.tags().eq(Arrays.asList("java", "kotlin")))
                .select(table)
                .execute();
        Assertions.assertEquals(
                "[" +
                        "{" +
                        "\"id\":1," +
                        "\"point\":{\"_x\":3,\"_y\":4}," +
                        "\"tags\":[\"java\",\"kotlin\"]," +
                        "\"scores\":{\"1\":100}," +
                        "\"complexList\":[[\"1-1\",\"1-2\"],[\"2-1\",\"2-2\"]]," +
                        "\"complexMap\":{\"key\":{\"nested-key\":\"value\"}}" +
                        "}" +
                        "]",
                jsonWrappers.toString()
        );

        jsonWrappers = sqlClient()
                .createQuery(table)
                .where(
                        Expression.tuple(table.point(), table.tags()).eq(
                                new Tuple2<>(
                                        new Point(3, 4),
                                        Arrays.asList("java", "kotlin")
                                )
                        )
                )
                .select(table)
                .execute();
        Assertions.assertEquals(
                "[" +
                        "{" +
                        "\"id\":1," +
                        "\"point\":{\"_x\":3,\"_y\":4}," +
                        "\"tags\":[\"java\",\"kotlin\"]," +
                        "\"scores\":{\"1\":100}," +
                        "\"complexList\":[[\"1-1\",\"1-2\"],[\"2-1\",\"2-2\"]]," +
                        "\"complexMap\":{\"key\":{\"nested-key\":\"value\"}}" +
                        "}" +
                        "]",
                jsonWrappers.toString()
        );
    }

    @Test
    public void testDML() {
        sqlClient().getEntities().save(
                JsonWrapperDraft.$.produce(draft -> {
                    draft.setId(1L);
                    draft.setPoint(new Point(3, 4));
                    draft.setTags(Arrays.asList("java", "kotlin"));
                    draft.setScores(Collections.singletonMap(1L, 100));
                    draft.setComplexList(
                            Arrays.asList(
                                    Arrays.asList("1-1", "1-2"),
                                    Arrays.asList("2-1", "2-2")
                            )
                    );
                    draft.setComplexMap(
                            Collections.singletonMap(
                                    "key",
                                    Collections.singletonMap("nested-key", "value")
                            )
                    );
                })
        );

        JsonWrapperTable table = JsonWrapperTable.$;

        int updatedCount = sqlClient()
                .createUpdate(table)
                .where(table.point().eq(new Point(3, 4)))
                .where(table.tags().eq(Arrays.asList("java", "kotlin")))
                .set(
                        table.scores(),
                        MapBuilder.<Long, Integer>builder()
                                .add(1L, 100)
                                .add(10L, 100)
                                .build()
                )
                .execute();

        Assertions.assertEquals(1, updatedCount);
    }

    private static class MapBuilder<K, V> {

        private Map<K, V> map = new LinkedHashMap<>();

        public static <K, V> MapBuilder<K, V> builder() {
            return new MapBuilder<>();
        }

        public MapBuilder<K, V> add(K key, V value) {
            map.put(key, value);
            return this;
        }

        public Map<K, V> build() {
            return map;
        }
    }
}
