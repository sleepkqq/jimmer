package example;

import com.fasterxml.jackson.databind.ObjectMapper;
import example.dto.BookPatch;
import example.dto.BookSpec;
import example.dto.BookView;
import example.dto.StorePatch;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import org.babyfish.jimmer.ImmutableObjects;
import org.babyfish.jimmer.Page;
import org.babyfish.jimmer.jackson.ImmutableModule;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.exception.SaveException;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;

public class Examples {
    public static Page<BookView> search(JSqlClient sqlClient, String name, int page, int size) {
        var b = Tables.BOOK_TABLE;
        return sqlClient.createQuery(b)
            .where(b.name().likeIf(name))
            .orderBy(b.name().asc(), b.id().asc())
            .select(b.fetch(BookView.class))
            .fetchPage(page, size);
    }

    public static int increasePrice(JSqlClient sqlClient, long id, int expectedVersion, BigDecimal delta) {
        var b = Tables.BOOK_TABLE;
        return sqlClient.createUpdate(b)
            .set(b.price(), b.price().plus(delta))
            .set(b.version(), b.version().plus(1))
            .where(b.id().eq(id), b.version().eq(expectedVersion))
            .execute();
    }

    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper().registerModule(new ImmutableModule());
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:java");
             var statement = connection.createStatement();
             var schema = Examples.class.getResourceAsStream("/schema.sql")) {
            for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            connection.setAutoCommit(false);
            var manager = ConnectionManager.singleConnectionManager(connection);
            var sql = JSqlClient.newBuilder().setDialect(new H2Dialect())
                .setConnectionManager(manager).build();

            Book book = Immutables.createBook(d -> {
                d.setName("Jimmer Guide");
                d.setPrice(new BigDecimal("29.90"));
                d.setTenantId("a");
                d.setStore(Immutables.createBookStore(s -> s.setId(10L)));
            });
            BookView created = sql.saveCommand(book).setMode(SaveMode.INSERT_ONLY)
                .execute(BookView.class).getModifiedView();
            long id = created.getId();
            check(created.getStore().getName().equals("Library"), "save View association");
            check(search(sql, "Guide", 0, 10).getRows().size() == 1, "View page");
            check(search(sql, null, 0, 10).getTotalRowCount() == 1, "optional predicate");

            var fetcher = Fetchers.BOOK_FETCHER.name().price().store(Fetchers.BOOK_STORE_FETCHER.name()).displayName();
            Book fetched = sql.findByIds(fetcher, List.of(id)).get(0);
            check(fetched.displayName().equals("Book: Jimmer Guide"), "formula");
            check(!ImmutableObjects.isLoaded(fetched, BookProps.TENANT_ID), "explicit shape");

            BookPatch omitted = mapper.readValue("{\"id\":" + id + "}", BookPatch.class);
            check(!ImmutableObjects.isLoaded(omitted.toEntity(), BookProps.STORE), "omitted store");
            sql.saveCommand(omitted).setMode(SaveMode.UPDATE_ONLY).execute();
            check(sql.findById(Fetchers.BOOK_FETCHER.storeId(), id).storeId() == 10L, "omission preserves FK");
            BookPatch cleared = mapper.readValue("{\"id\":" + id + ",\"store\":null}", BookPatch.class);
            check(ImmutableObjects.isLoaded(cleared.toEntity(), BookProps.STORE), "explicit null loaded");
            sql.saveCommand(cleared).setMode(SaveMode.UPDATE_ONLY).execute();
            check(sql.findById(Fetchers.BOOK_FETCHER.storeId(), id).storeId() == null, "null clears FK");
            BookPatch value = mapper.readValue("{\"id\":" + id + ",\"store\":{\"id\":10}}", BookPatch.class);
            sql.saveCommand(value).setMode(SaveMode.UPDATE_ONLY).execute();

            StorePatch storeOmitted = mapper.readValue("{\"id\":10}", StorePatch.class);
            StorePatch empty = mapper.readValue("{\"id\":10,\"books\":[]}", StorePatch.class);
            check(!ImmutableObjects.isLoaded(storeOmitted.toEntity(), BookStoreProps.BOOKS), "omitted collection");
            check(ImmutableObjects.isLoaded(empty.toEntity(), BookStoreProps.BOOKS), "empty collection loaded");
            sql.saveCommand(storeOmitted).setMode(SaveMode.UPDATE_ONLY).execute();
            for (var mode : List.of(AssociatedSaveMode.MERGE, AssociatedSaveMode.REPLACE)) {
                sql.saveCommand(empty).setMode(SaveMode.UPDATE_ONLY).setAssociatedModeAll(mode).execute();
                Long storeId = sql.findById(Fetchers.BOOK_FETCHER.storeId(), id).storeId();
                check(mode == AssociatedSaveMode.MERGE ? storeId != null : storeId == null,
                    "association mode " + mode);
            }

            int version = sql.findById(Fetchers.BOOK_FETCHER.version(), id).version();
            check(increasePrice(sql, id, version, BigDecimal.ONE) == 1, "conditional update");
            check(increasePrice(sql, id, version, BigDecimal.ONE) == 0, "stale update rejected");
            check(sql.findById(Fetchers.BOOK_FETCHER.price(), id).price().compareTo(new BigDecimal("30.90")) == 0,
                "atomic update value");
            Book stale = Immutables.createBook(d -> {
                d.setId(id); d.setVersion(version); d.setName("Stale");
            });
            try {
                sql.saveCommand(stale).setMode(SaveMode.UPDATE_ONLY).execute();
                throw new AssertionError("stale save succeeded");
            } catch (SaveException.OptimisticLockError expected) {
                // The graph save conflicts; bulk conditional update above returns zero.
            }

            var b = Tables.BOOK_TABLE;
            var base = sql.createBaseQuery(b).addSelect(b.id()).addSelect(b.name()).asBaseTable();
            check(sql.createQuery(base).where(base.get_2().like("Guide"))
                .select(base.get_1(), base.get_2()).execute().size() == 1, "base query");
            BookSpec spec = new BookSpec();
            spec.setName("guide");
            check(sql.createQuery(b).where(spec).select(b.id()).exists(), "Specification");
            check(sql.createQuery(b).orderBy(b.id()).select(b.id()).fetchSlice(1, 0).isTail(), "slice");
            int[] visited = {0};
            sql.createQuery(b).select(b.fetch(fetcher)).forEach(1, row -> visited[0]++);
            check(visited[0] == 1, "batched traversal");

            sql.saveCommand(value).setMode(SaveMode.UPDATE_ONLY).execute();
            var s = Tables.BOOK_STORE_TABLE;
            check(sql.createQuery(s).where(s.books(child -> child.name().eq("Jimmer Guide")))
                .select(s).execute().size() == 1, "collection membership");
            for (String tenant : List.of("a", "b")) {
                var scoped = JSqlClient.newBuilder().setDialect(new H2Dialect())
                    .setConnectionManager(manager).addFilters(new TenantFilter(tenant)).build();
                check(search(scoped, null, 0, 10).getRows().size() == (tenant.equals("a") ? 1 : 0),
                    "tenant isolation " + tenant);
            }
            connection.rollback();
            check(search(sql, null, 0, 10).getRows().isEmpty(), "rollback");
        }
        System.out.println("Java 0.9.111: APT/DTO, queries, PATCH, association modes, locking, filters and rollback passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
