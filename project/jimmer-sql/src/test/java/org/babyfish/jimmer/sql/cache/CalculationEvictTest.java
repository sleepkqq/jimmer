package org.babyfish.jimmer.sql.cache;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.common.CacheImpl;
import org.babyfish.jimmer.sql.common.ParameterizedCaches;
import org.babyfish.jimmer.sql.filter.common.CacheableFileFilter;
import org.babyfish.jimmer.sql.model.Book;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.babyfish.jimmer.jackson.codec.JsonCodec.jsonCodec;

public class CalculationEvictTest extends AbstractQueryTest {
    private JSqlClient sqlClient;

    private List<String> deleteMessages;

    @BeforeEach
    public void initialize() {
        deleteMessages = new ArrayList<>();
        CalculationEvictTest that = this;
        sqlClient = getSqlClient(it -> {
            it.addFilters(new CacheableFileFilter());
            it.setCaches(cfg -> {
                cfg.setCacheFactory(
                        new CacheFactory() {
                            @Override
                            public Cache<?, ?> createObjectCache(@NotNull ImmutableType type) {
                                return new CacheImpl<>(type);
                            }

                            @Override
                            public Cache<?, ?> createAssociatedIdCache(@NotNull ImmutableProp prop) {
                                return ParameterizedCaches.create(prop, that::onPropCacheDelete);
                            }

                            @Override
                            public Cache<?, List<?>> createAssociatedIdListCache(@NotNull ImmutableProp prop) {
                                return ParameterizedCaches.create(prop, that::onPropCacheDelete);
                            }

                            @Override
                            public Cache<?, ?> createResolverCache(@NotNull ImmutableProp prop) {
                                return ParameterizedCaches.create(prop, that::onPropCacheDelete);
                            }
                        }
                );
            });
            it.setConnectionManager(testConnectionManager());
        });
    }

    private void onPropCacheDelete(Collection<String> keys) {
        deleteMessages.addAll(keys);
    }

    @Test
    public void testInsertBookEvictsNewOwnerMembershipAndCalculations() {
        UUID book = UUID.randomUUID();
        accept("book", null, bookRow(book, STORE_1, "first", 1, 10));
        assertDeleted(
                "Book.store-" + book,
                "BookStore.books-" + STORE_1,
                "BookStore.avgPrice-" + STORE_1,
                "BookStore.newestBooks-" + STORE_1
        );
    }

    @Test
    public void testDeleteBookEvictsOldOwnerMembershipAndCalculations() {
        UUID book = UUID.randomUUID();
        accept("book", bookRow(book, STORE_1, "first", 1, 10), null);
        assertDeleted(
                "Book.store-" + book,
                "BookStore.books-" + STORE_1,
                "BookStore.avgPrice-" + STORE_1,
                "BookStore.newestBooks-" + STORE_1
        );
    }

    @Test
    public void testMoveBookEvictsOldAndNewOwnerMembershipAndCalculations() {
        UUID book = UUID.randomUUID();
        accept("book", bookRow(book, STORE_1, "first", 1, 10), bookRow(book, STORE_2, "first", 1, 10));
        assertDeleted(
                "Book.store-" + book,
                "BookStore.books-" + STORE_1,
                "BookStore.books-" + STORE_2,
                "BookStore.avgPrice-" + STORE_1,
                "BookStore.avgPrice-" + STORE_2,
                "BookStore.newestBooks-" + STORE_1,
                "BookStore.newestBooks-" + STORE_2
        );
    }

    @Test
    public void testChangeBookPriceEvictsOwnerAveragePrice() {
        UUID book = UUID.randomUUID();
        accept("book", bookRow(book, STORE_1, "first", 1, 10), bookRow(book, STORE_1, "first", 1, 20));
        assertDeleted("BookStore.avgPrice-" + STORE_1);
    }

    @Test
    public void testChangeBookNameEvictsOwnerNewestBooks() {
        UUID book = UUID.randomUUID();
        accept("book", bookRow(book, STORE_1, "first", 1, 10), bookRow(book, STORE_1, "second", 1, 10));
        assertDeleted("BookStore.newestBooks-" + STORE_1);
    }

    @Test
    public void testBookPriceKeepsExactBigDecimalValue() {
        AtomicReference<BigDecimal> price = new AtomicReference<>();
        sqlClient.getTriggers().addEntityListener(Book.class, e -> {
            Book book = e.getNewEntity();
            if (book != null) {
                price.set(book.price());
            }
        });
        UUID book = UUID.randomUUID();
        accept("book", null, "{\"id\":\"" + book + "\",\"store_id\":\"" + STORE_1 + "\",\"price\":10}");
        Assertions.assertEquals(new BigDecimal("10"), price.get());
        accept("book", null, "{\"id\":\"" + book + "\",\"store_id\":\"" + STORE_1 + "\",\"price\":10.5}");
        Assertions.assertEquals(new BigDecimal("10.5"), price.get());
    }

    private void accept(String table, String oldRow, String newRow) {
        try {
            sqlClient.getBinLog().accept(
                    table,
                    oldRow != null ? jsonCodec().treeReader().read(oldRow) : null,
                    newRow != null ? jsonCodec().treeReader().read(newRow) : null
            );
        } catch (Exception ex) {
            Assertions.fail(ex);
        }
    }

    private static String bookRow(UUID bookId, UUID storeId, String name, int edition, int price) {
        return "{" +
                "\"id\":\"" + bookId + "\"," +
                "\"name\":\"" + name + "\"," +
                "\"edition\":" + edition + "," +
                "\"price\":" + price + "," +
                "\"store_id\":\"" + storeId + "\"" +
                "}";
    }

    private void assertDeleted(String... keys) {
        for (String key : keys) {
            Assertions.assertTrue(
                    deleteMessages.contains(key),
                    "Missing cache deletion \"" + key + "\", actual deletions: " + deleteMessages
            );
        }
    }

    private static final UUID STORE_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID STORE_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

}
