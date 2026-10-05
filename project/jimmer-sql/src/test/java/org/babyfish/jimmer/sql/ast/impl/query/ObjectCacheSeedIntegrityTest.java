package org.babyfish.jimmer.sql.ast.impl.query;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.impl.EntitiesImpl;
import org.babyfish.jimmer.sql.ast.mutation.QueryReason;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.common.AbstractQueryTest;
import org.babyfish.jimmer.sql.common.Constants;
import org.babyfish.jimmer.sql.filter.Filter;
import org.babyfish.jimmer.sql.filter.FilterArgs;
import org.babyfish.jimmer.sql.model.Author;
import org.babyfish.jimmer.sql.model.Book;
import org.babyfish.jimmer.sql.model.BookStore;
import org.babyfish.jimmer.sql.model.BookTable;
import org.babyfish.jimmer.sql.model.Country;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.EntityManager;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Integrity of the authenticated object-cache query seed bridge
 * ({@link EntitiesImpl#findMapByIdsForQuery}) and of the raw hot path it feeds.
 *
 * <p>The seed may skip the redundant per-id visibility query, but only for the exact
 * id-to-concrete-type group the filtered skeleton admitted. These tests pin that a
 * hostile {@code ids} iterable cannot widen the admitted group by mutating the caller's
 * map during iteration, and that every forged or mismatched seed declines before any
 * cache or SQL access.</p>
 */
public class ObjectCacheSeedIntegrityTest extends AbstractQueryTest {

    private static final ConnectionManager INACTIVE_MANAGER = manager(true);

    private static final ConnectionManager ACTIVE_MANAGER = manager(false);

    private static ConnectionManager manager(boolean inactive) {
        return new ConnectionManager() {
            @Override
            public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
                return AbstractQueryTest.testConnectionManager().execute(con, block);
            }

            @Override
            public boolean isTransactionKnownInactive(Connection con) {
                return inactive && con != null;
            }
        };
    }

    @Test
    public void hostileIdsIterableCannotWidenTheAuthenticatedGroup() {
        RecordingCache cache = new RecordingCache();
        JSqlClient client = filteredClient(cache);
        Map<UUID, Book> bodies = loadBooks();
        Book bookA = bodies.get(Constants.learningGraphQLId1);
        Book bookB = bodies.get(Constants.learningGraphQLId2);
        Assertions.assertNotNull(bookA);
        Assertions.assertNotNull(bookB);
        cache.put(Constants.learningGraphQLId1, bookA);
        cache.put(Constants.learningGraphQLId2, bookB);

        Map<Object, ImmutableType> group = new LinkedHashMap<>();
        group.put(Constants.learningGraphQLId1, ImmutableType.get(Book.class));

        jdbc(con -> {
            EntitiesImpl entities = (EntitiesImpl) client.getEntities().forConnection(con);
            ObjectCacheQuerySeed seed = new ObjectCacheQuerySeed(
                    (JSqlClientImplementor) client,
                    con,
                    INACTIVE_MANAGER,
                    ImmutableType.get(Book.class),
                    ImmutableType.get(Book.class),
                    group
            );
            // During iteration the hostile iterable rewrites the caller's admitted group
            // from the admitted id to a same-concrete-type id that was never admitted.
            Iterable<UUID> hostile =
                    () ->
                            new Iterator<UUID>() {
                                private boolean first = true;

                                @Override
                                public boolean hasNext() {
                                    return first;
                                }

                                @Override
                                public UUID next() {
                                    first = false;
                                    group.clear();
                                    group.put(Constants.learningGraphQLId2, ImmutableType.get(Book.class));
                                    return Constants.learningGraphQLId1;
                                }
                            };
            Map<UUID, Book> result = entities.<UUID, Book>findMapByIdsForQuery(
                    ImmutableType.get(Book.class),
                    null,
                    ImmutableType.get(Book.class),
                    hostile,
                    group,
                    seed
            );
            Assertions.assertTrue(
                    result.containsKey(Constants.learningGraphQLId1),
                    "the admitted id must be returned"
            );
            Assertions.assertFalse(
                    result.containsKey(Constants.learningGraphQLId2),
                    "an id injected during iteration must never be admitted or loaded"
            );
        });
        for (Collection<Object> keys : cache.getAllKeys) {
            Assertions.assertFalse(
                    keys.contains(Constants.learningGraphQLId2),
                    "the content cache must never be queried for the injected id"
            );
        }
    }

    @Test
    public void seedDeclinesForWrongClient() {
        RecordingCache cache1 = new RecordingCache();
        RecordingCache cache2 = new RecordingCache();
        JSqlClient client1 = filteredClient(cache1);
        JSqlClient client2 = filteredClient(cache2);
        Map<Object, ImmutableType> group = group(Constants.learningGraphQLId1);
        jdbc(con -> {
            ObjectCacheQuerySeed seed = seed(client1, con, group);
            clearExecutions();
            Map<UUID, Book> result = ((EntitiesImpl) client2.getEntities().forConnection(con))
                    .<UUID, Book>findMapByIdsForQuery(
                            ImmutableType.get(Book.class),
                            null,
                            ImmutableType.get(Book.class),
                            Collections.singletonList(Constants.learningGraphQLId1),
                            group,
                            seed
                    );
            Assertions.assertTrue(result.isEmpty(), "a seed bound to another client must decline");
            Assertions.assertTrue(getExecutions().isEmpty(), "rejected seed must not touch SQL");
            Assertions.assertTrue(cache2.getAllKeys.isEmpty(), "rejected seed must not consult the cache");
        });
    }

    @Test
    public void seedDeclinesForMismatchedConnection() {
        RecordingCache cache = new RecordingCache();
        JSqlClient client = filteredClient(cache);
        Map<Object, ImmutableType> group = group(Constants.learningGraphQLId1);
        jdbc(con -> {
            // Seed bound to no connection; the executing bridge owns the real connection.
            ObjectCacheQuerySeed seed = new ObjectCacheQuerySeed(
                    (JSqlClientImplementor) client,
                    null,
                    INACTIVE_MANAGER,
                    ImmutableType.get(Book.class),
                    ImmutableType.get(Book.class),
                    group
            );
            clearExecutions();
            Map<UUID, Book> result = ((EntitiesImpl) client.getEntities().forConnection(con))
                    .<UUID, Book>findMapByIdsForQuery(
                            ImmutableType.get(Book.class),
                            null,
                            ImmutableType.get(Book.class),
                            Collections.singletonList(Constants.learningGraphQLId1),
                            group,
                            seed
                    );
            Assertions.assertTrue(result.isEmpty(), "a seed bound to another connection must decline");
            Assertions.assertTrue(getExecutions().isEmpty(), "rejected seed must not touch SQL");
            Assertions.assertTrue(cache.getAllKeys.isEmpty(), "rejected seed must not consult the cache");
        });
    }

    @Test
    public void seedDeclinesForWrongRequestedOrCacheOwnerType() {
        RecordingCache cache = new RecordingCache();
        JSqlClient client = filteredClient(cache);
        Map<Object, ImmutableType> group = group(Constants.learningGraphQLId1);
        jdbc(con -> {
            EntitiesImpl entities = (EntitiesImpl) client.getEntities().forConnection(con);
            ObjectCacheQuerySeed seed = seed(client, con, group);
            clearExecutions();
            Map<UUID, Book> wrongRequested = entities.<UUID, Book>findMapByIdsForQuery(
                    ImmutableType.get(Author.class),
                    null,
                    ImmutableType.get(Book.class),
                    Collections.singletonList(Constants.learningGraphQLId1),
                    group,
                    seed
            );
            Assertions.assertTrue(wrongRequested.isEmpty(), "requested-type mismatch must decline");
            Map<UUID, Book> wrongOwner = entities.<UUID, Book>findMapByIdsForQuery(
                    ImmutableType.get(Book.class),
                    null,
                    ImmutableType.get(BookStore.class),
                    Collections.singletonList(Constants.learningGraphQLId1),
                    group,
                    seed
            );
            Assertions.assertTrue(wrongOwner.isEmpty(), "cache-owner mismatch must decline");
            Assertions.assertTrue(getExecutions().isEmpty(), "rejected seeds must not touch SQL");
            Assertions.assertTrue(cache.getAllKeys.isEmpty(), "rejected seeds must not consult the cache");
        });
    }

    @Test
    public void seedDeclinesForWidenedOrChangedGroup() {
        RecordingCache cache = new RecordingCache();
        JSqlClient client = filteredClient(cache);
        Map<Object, ImmutableType> group = group(Constants.learningGraphQLId1);
        Map<Object, ImmutableType> widened = group(Constants.learningGraphQLId1);
        widened.put(Constants.learningGraphQLId2, ImmutableType.get(Book.class));
        Map<Object, ImmutableType> changedType = group(Constants.learningGraphQLId1);
        changedType.put(Constants.learningGraphQLId1, ImmutableType.get(Author.class));
        jdbc(con -> {
            EntitiesImpl entities = (EntitiesImpl) client.getEntities().forConnection(con);
            ObjectCacheQuerySeed seed = seed(client, con, group);
            clearExecutions();
            Assertions.assertTrue(
                    entities.<UUID, Book>findMapByIdsForQuery(
                            ImmutableType.get(Book.class),
                            null,
                            ImmutableType.get(Book.class),
                            Collections.singletonList(Constants.learningGraphQLId1),
                            widened,
                            seed
                    ).isEmpty(),
                    "a widened group must decline"
            );
            Assertions.assertTrue(
                    entities.<UUID, Book>findMapByIdsForQuery(
                            ImmutableType.get(Book.class),
                            null,
                            ImmutableType.get(Book.class),
                            Collections.singletonList(Constants.learningGraphQLId1),
                            changedType,
                            seed
                    ).isEmpty(),
                    "a changed concrete type must decline"
            );
            Assertions.assertTrue(getExecutions().isEmpty(), "rejected seeds must not touch SQL");
            Assertions.assertTrue(cache.getAllKeys.isEmpty(), "rejected seeds must not consult the cache");
        });
    }

    @Test
    public void seedDeclinesForUnknownOrActiveManager() {
        RecordingCache cache = new RecordingCache();
        JSqlClient client = filteredClient(cache);
        Map<Object, ImmutableType> group = group(Constants.learningGraphQLId1);
        jdbc(con -> {
            EntitiesImpl entities = (EntitiesImpl) client.getEntities().forConnection(con);
            ObjectCacheQuerySeed seed = new ObjectCacheQuerySeed(
                    (JSqlClientImplementor) client,
                    con,
                    ACTIVE_MANAGER,
                    ImmutableType.get(Book.class),
                    ImmutableType.get(Book.class),
                    group
            );
            clearExecutions();
            Assertions.assertTrue(
                    entities.<UUID, Book>findMapByIdsForQuery(
                            ImmutableType.get(Book.class),
                            null,
                            ImmutableType.get(Book.class),
                            Collections.singletonList(Constants.learningGraphQLId1),
                            group,
                            seed
                    ).isEmpty(),
                    "a manager that cannot prove inactivity must decline"
            );
            Assertions.assertTrue(getExecutions().isEmpty(), "rejected seed must not touch SQL");
            Assertions.assertTrue(cache.getAllKeys.isEmpty(), "rejected seed must not consult the cache");
        });
    }

    @Test
    public void seedDeclinesForCommandPurposeAndLockingReads() {
        RecordingCache cache = new RecordingCache();
        JSqlClient client = filteredClient(cache);
        Map<Object, ImmutableType> group = group(Constants.learningGraphQLId1);
        jdbc(con -> {
            ObjectCacheQuerySeed seed = seed(client, con, group);
            EntitiesImpl bound = (EntitiesImpl) client.getEntities().forConnection(con);
            EntitiesImpl command = (EntitiesImpl) bound.forSaveCommandFetch(QueryReason.NONE);
            EntitiesImpl locking = (EntitiesImpl) bound.forUpdate();
            clearExecutions();
            Assertions.assertTrue(
                    command.<UUID, Book>findMapByIdsForQuery(
                            ImmutableType.get(Book.class),
                            null,
                            ImmutableType.get(Book.class),
                            Collections.singletonList(Constants.learningGraphQLId1),
                            group,
                            seed
                    ).isEmpty(),
                    "a command-purpose read must decline"
            );
            Assertions.assertTrue(
                    locking.<UUID, Book>findMapByIdsForQuery(
                            ImmutableType.get(Book.class),
                            null,
                            ImmutableType.get(Book.class),
                            Collections.singletonList(Constants.learningGraphQLId1),
                            group,
                            seed
                    ).isEmpty(),
                    "a locking read must decline"
            );
            Assertions.assertTrue(getExecutions().isEmpty(), "rejected seeds must not touch SQL");
            Assertions.assertTrue(cache.getAllKeys.isEmpty(), "rejected seeds must not consult the cache");
        });
    }

    private JSqlClient filteredClient(Cache<?, ?> bookCache) {
        return getSqlClient(builder -> {
            builder.setConnectionManager(INACTIVE_MANAGER);
            builder.setEntityManager(new EntityManager(Book.class, BookStore.class, Author.class, Country.class));
            builder.addFilters(new Filter<BookTable>() {
                @Override
                public void filter(FilterArgs<BookTable> args) {
                    args.where(args.getTable().id().eq(Constants.learningGraphQLId1));
                }
            });
            builder.setCaches(cfg -> cfg.setCacheFactory(new CacheFactory() {
                @Override
                public Cache<?, ?> createObjectCache(ImmutableType type) {
                    return type.getJavaClass() == Book.class ? bookCache : null;
                }
            }));
        });
    }

    private Map<UUID, Book> loadBooks() {
        JSqlClient plain = getSqlClient();
        Map<UUID, Book> bodies = new LinkedHashMap<>();
        jdbc(con -> {
            for (Book book : plain.getEntities()
                    .forConnection(con)
                    .findByIds(
                            Book.class,
                            Arrays.asList(Constants.learningGraphQLId1, Constants.learningGraphQLId2)
                    )) {
                bodies.put(book.id(), book);
            }
        });
        return bodies;
    }

    private ObjectCacheQuerySeed seed(
            JSqlClient client,
            Connection con,
            Map<Object, ImmutableType> group
    ) {
        return new ObjectCacheQuerySeed(
                (JSqlClientImplementor) client,
                con,
                INACTIVE_MANAGER,
                ImmutableType.get(Book.class),
                ImmutableType.get(Book.class),
                group
        );
    }

    private static Map<Object, ImmutableType> group(UUID id) {
        Map<Object, ImmutableType> group = new LinkedHashMap<>();
        group.put(id, ImmutableType.get(Book.class));
        return group;
    }

    private static class RecordingCache implements Cache<Object, Book> {

        private final ImmutableType type = ImmutableType.get(Book.class);

        private final Map<Object, Book> map = new LinkedHashMap<>();

        private final List<Collection<Object>> getAllKeys = new ArrayList<>();

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
        public Map<Object, Book> getAll(
                @NotNull Collection<Object> keys,
                @NotNull CacheEnvironment<Object, Book> env
        ) {
            getAllKeys.add(new ArrayList<>(keys));
            Map<Object, Book> result = new LinkedHashMap<>();
            Set<Object> missed = new LinkedHashSet<>();
            for (Object key : keys) {
                if (map.containsKey(key)) {
                    result.put(key, map.get(key));
                } else {
                    result.put(key, null);
                    missed.add(key);
                }
            }
            if (!missed.isEmpty()) {
                Map<Object, Book> loaded = env.getLoader().loadAll(missed);
                for (Object key : missed) {
                    Book value = loaded.get(key);
                    map.put(key, value);
                    result.put(key, value);
                }
            }
            return result;
        }

        void put(Object key, Book value) {
            map.put(key, value);
        }

        @Override
        public void deleteAll(@NotNull Collection<Object> keys, @Nullable Object reason) {
            map.keySet().removeAll(keys);
        }
    }
}
