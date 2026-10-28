package example;

import com.fasterxml.jackson.databind.ObjectMapper;
import example.dto.AssetView;
import example.dto.ItemView;
import example.dto.WarehousePatch;
import java.util.List;
import java.util.Set;
import java.sql.DriverManager;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.jackson.v2.ImmutableModuleV2;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.DissociateAction;
import org.babyfish.jimmer.sql.ast.mutation.*;
import org.babyfish.jimmer.sql.dialect.DefaultDialect;
import org.babyfish.jimmer.sql.dialect.Dialect;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;

public class Smoke {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper().registerModule(new ImmutableModuleV2());
        var omitted = mapper.readValue("{\"id\":1}", WarehousePatch.class).toEntity();
        check(!ImmutableObjects.isLoaded(omitted, "note"), "omitted note loaded");
        check(!ImmutableObjects.isLoaded(omitted, "bins"), "omitted bins loaded");

        var cleared = mapper.readValue(
            "{\"id\":1,\"note\":null,\"bins\":[]}", WarehousePatch.class).toEntity();
        check(ImmutableObjects.isLoaded(cleared, "note") && cleared.note() == null,
            "explicit null lost");
        check(ImmutableObjects.isLoaded(cleared, "bins") && cleared.bins().isEmpty(),
            "empty collection lost");
        check(ImmutableType.get(Bin.class).getProp("warehouse").getDissociateAction()
            == DissociateAction.DELETE, "owning-side dissociation missing");

        var book = BookAssetDraft.$.produce(d -> {
            d.setId(1L);
            d.setName("Synthetic book");
            d.setIsbn("TEST-001");
        });
        var view = AssetView.METADATA.getConverter().apply(book);
        check(view.toEntity() instanceof BookAsset, "polymorphic DTO lost subtype");
        mutationRoundTrip(new H2Dialect(), omitted, cleared);
        mutationRoundTrip(DefaultDialect.INSTANCE, omitted, cleared);
        rejectedViewRoundTrip();
        System.out.println("DTO presence, owning-side mapping and polymorphic conversion passed");
        System.out.println("Insert, conditional upsert, rejection and returning passed on native and materialized plans");
        System.out.println("DTO omission preserves children; loaded-empty MERGE preserves and REPLACE dissociates them");
        System.out.println("Rejected required-ID View reproduced; entity-Fetcher acceptance-first conversion passed");
    }

    static List<Long> copy(JSqlClient sql) {
        var warehouse = WarehouseTable.$;
        var archive = WarehouseArchiveTable.$;
        WarehouseTable source = sql.createBaseQuery(warehouse)
            .where(warehouse.active().eq(true)).select(warehouse).asBaseTable();
        return sql.createInsert(archive, source)
            .set(archive.id(), source.id()).set(archive.name(), source.name())
            .onConflictDoNothing(archive.id()).returning(archive.id()).execute();
    }

    static List<Long> synchronize(JSqlClient sql) {
        var warehouse = WarehouseTable.$;
        var archive = WarehouseArchiveTable.$;
        var source = sql.createBaseQuery(warehouse)
            .where(warehouse.active().eq(true))
            .addSelect(warehouse.id()).addSelect(warehouse.name()).asBaseTable();
        return sql.createUpsert(archive, source)
            .key(archive.id(), source.get_1())
            .merge(archive.name(), source.get_2())
            .updateWhere(archive.name().ne(source.get_2()))
            .returning(archive.id()).execute();
    }

    static void mutationRoundTrip(Dialect dialect, Warehouse omitted, Warehouse cleared) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + dialect.getClass().getSimpleName());
             var statement = connection.createStatement()) {
            statement.execute("create table WAREHOUSE(ID bigint primary key, NAME varchar not null, ACTIVE boolean not null, NOTE varchar)");
            statement.execute("create table WAREHOUSE_ARCHIVE(ID bigint primary key, NAME varchar not null)");
            statement.execute("create table BIN(ID bigint primary key, WAREHOUSE_ID bigint not null references WAREHOUSE(ID))");
            statement.execute("create table CATALOG_REVISION(ID bigint primary key, VERSION integer not null, PAYLOAD varchar not null)");
            statement.execute("insert into WAREHOUSE values (1, 'Revised', true, null), (2, 'Same', true, null), (3, 'New', true, null)");
            statement.execute("insert into WAREHOUSE_ARCHIVE values (1, 'Original')");
            statement.execute("insert into BIN values (11, 1)");
            connection.setAutoCommit(false);
            var sql = JSqlClient.newBuilder().setDialect(dialect)
                .setConnectionManager(ConnectionManager.singleConnectionManager(connection)).build();

            check(Set.copyOf(copy(sql)).equals(Set.of(2L, 3L)), "insert returning included a conflict");
            try (var row = statement.executeQuery("select NAME from WAREHOUSE_ARCHIVE where ID=1")) {
                check(row.next() && row.getString(1).equals("Original"), "insert-if-absent updated a conflict");
            }
            statement.execute("delete from WAREHOUSE_ARCHIVE where ID=3");
            check(Set.copyOf(synchronize(sql)).equals(Set.of(1L, 3L)), "upsert returning did not exclude rejected update");
            try (var row = statement.executeQuery("select NAME from WAREHOUSE_ARCHIVE order by ID")) {
                for (String expected : List.of("Revised", "Same", "New")) {
                    check(row.next() && row.getString(1).equals(expected), "incorrect upsert row state");
                }
                check(!row.next(), "upsert created extra rows");
            }
            for (int version : new int[]{2, 1, 3}) {
                var incoming = CatalogRevisionDraft.$.produce(d -> {
                    d.setId(10L);
                    d.setVersion(version);
                    d.setPayload("revision-" + version);
                });
                check(ingest(sql, incoming) == (version != 1), "incorrect save acceptance");
            }
            try (var row = statement.executeQuery("select VERSION, PAYLOAD from CATALOG_REVISION where ID=10")) {
                check(row.next() && row.getInt(1) == 3 && row.getString(2).equals("revision-3"),
                    "external version was incremented or stale input accepted");
            }
            sql.saveCommand(omitted).setMode(SaveMode.UPDATE_ONLY).execute();
            try (var row = statement.executeQuery("select count(*) from BIN")) {
                check(row.next() && row.getInt(1) == 1, "omitted association removed a child");
            }
            for (var mode : new AssociatedSaveMode[]{AssociatedSaveMode.MERGE, AssociatedSaveMode.REPLACE}) {
                sql.saveCommand(cleared).setMode(SaveMode.UPDATE_ONLY)
                    .setAssociatedMode(WarehouseProps.BINS, mode).execute();
                try (var row = statement.executeQuery("select count(*) from BIN")) {
                    check(row.next() && row.getInt(1) == (mode == AssociatedSaveMode.MERGE ? 1 : 0),
                        "loaded-empty association ignored its save policy");
                }
            }
            connection.rollback();
        }
    }

    static void rejectedViewRoundTrip() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:rejectedView");
             var statement = connection.createStatement()) {
            statement.execute("create table ITEM(ID bigint generated by default as identity primary key, SKU varchar not null unique, NAME varchar not null)");
            statement.execute("insert into ITEM(SKU, NAME) values ('A', 'Original')");
            var sql = JSqlClient.newBuilder().setDialect(new H2Dialect())
                .setConnectionManager(ConnectionManager.singleConnectionManager(connection)).build();
            var conflict = ItemDraft.$.produce(d -> d.setSku("A").setName("Attempt"));
            boolean failedBeforeAcceptance = false;
            try {
                sql.saveCommand(conflict).setMode(SaveMode.INSERT_IF_ABSENT).execute(ItemView.class);
            } catch (org.babyfish.jimmer.UnloadedException ex) {
                failedBeforeAcceptance = true;
            }
            check(failedBeforeAcceptance, "expected eager View conversion to read an unloaded ID");

            for (String sku : new String[]{"A", "B"}) {
                var input = ItemDraft.$.produce(d -> d.setSku(sku).setName("Attempt"));
                var result = sql.saveCommand(input).setMode(SaveMode.INSERT_IF_ABSENT)
                    .execute(ItemView.METADATA.getFetcher());
                check(result.isAccepted() == sku.equals("B"), "wrong insert acceptance");
                var table = ItemTable.$;
                ItemView view = result.isAccepted()
                    ? new ItemView(result.getModifiedEntity())
                    : sql.createQuery(table).where(table.sku().eq(sku))
                        .select(table.fetch(ItemView.class)).execute().get(0);
                check(view.getName().equals(sku.equals("A") ? "Original" : "Attempt"),
                    "wrong accepted/existing-row result");
            }
        }
    }

    static boolean ingest(JSqlClient sql, CatalogRevision incoming) {
        return sql.saveCommand(incoming)
            .setMode(SaveMode.UPSERT).setVersionMode(VersionMode.ASSIGNMENT)
            .setUpdateWhere(CatalogRevisionTable.class,
                (table, values) -> values.newNumber(CatalogRevisionProps.VERSION).gt(table.version()))
            .execute().isAccepted();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
