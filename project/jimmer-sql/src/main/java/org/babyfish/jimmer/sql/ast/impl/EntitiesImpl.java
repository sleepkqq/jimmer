package org.babyfish.jimmer.sql.ast.impl;

import org.babyfish.jimmer.Input;
import org.babyfish.jimmer.View;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.meta.PropId;
import org.babyfish.jimmer.meta.TypedProp;
import org.babyfish.jimmer.runtime.ImmutableSpi;
import org.babyfish.jimmer.sql.Entities;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.impl.mutation.BatchEntitySaveCommandImpl;
import org.babyfish.jimmer.sql.ast.impl.mutation.DeleteCommandImpl;
import org.babyfish.jimmer.sql.ast.impl.mutation.SimpleEntitySaveCommandImpl;
import org.babyfish.jimmer.sql.ast.impl.query.FilterLevel;
import org.babyfish.jimmer.sql.ast.impl.query.MutableRootQueryImpl;
import org.babyfish.jimmer.sql.ast.impl.query.ObjectCacheQuerySeed;
import org.babyfish.jimmer.sql.ast.impl.query.Queries;
import org.babyfish.jimmer.sql.ast.impl.table.FetcherSelectionImpl;
import org.babyfish.jimmer.sql.ast.mutation.BatchEntitySaveCommand;
import org.babyfish.jimmer.sql.ast.mutation.DeleteCommand;
import org.babyfish.jimmer.sql.ast.mutation.QueryReason;
import org.babyfish.jimmer.sql.ast.mutation.SimpleEntitySaveCommand;
import org.babyfish.jimmer.sql.ast.query.ConfigurableRootQuery;
import org.babyfish.jimmer.sql.ast.query.Example;
import org.babyfish.jimmer.sql.ast.query.Order;
import org.babyfish.jimmer.sql.ast.table.Table;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.CacheEnvironment;
import org.babyfish.jimmer.sql.cache.CacheLoader;
import org.babyfish.jimmer.sql.cache.CacheTypeMismatchException;
import org.babyfish.jimmer.sql.exception.EmptyResultException;
import org.babyfish.jimmer.sql.fetcher.DtoMetadata;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.fetcher.impl.Shapes;
import org.babyfish.jimmer.sql.runtime.Converters;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.util.*;
import java.util.function.Function;

public class EntitiesImpl implements Entities {

    private final JSqlClientImplementor sqlClient;

    private final boolean forUpdate;

    private final Connection con;

    private final ExecutionPurpose purpose;

    private final boolean rootUserFiltersIgnored;

    public EntitiesImpl(JSqlClientImplementor sqlClient) {
        this(sqlClient, false, null, ExecutionPurpose.QUERY, false);
    }

    public EntitiesImpl(JSqlClientImplementor sqlClient, boolean forUpdate, Connection con, ExecutionPurpose purpose) {
        this(sqlClient, forUpdate, con, purpose, false);
    }

    public EntitiesImpl(
            JSqlClientImplementor sqlClient,
            boolean forUpdate,
            Connection con,
            ExecutionPurpose purpose,
            boolean rootUserFiltersIgnored
    ) {
        this.sqlClient = sqlClient;
        this.forUpdate = forUpdate;
        this.con = con;
        this.purpose = purpose;
        this.rootUserFiltersIgnored = rootUserFiltersIgnored;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ImmutableType immutableTypeOf(Class<?> type) {
        if (View.class.isAssignableFrom(type)) {
            return DtoMetadata.of((Class<? extends View>) type).getFetcher().getImmutableType();
        }
        return ImmutableType.get(type);
    }

    public JSqlClientImplementor getSqlClient() {
        return sqlClient;
    }

    public Connection getCon() {
        return con;
    }

    public EntitiesImpl forSqlClient(JSqlClientImplementor sqlClient) {
        if (this.sqlClient == sqlClient) {
            return this;
        }
        return new EntitiesImpl(sqlClient, forUpdate, con, purpose, rootUserFiltersIgnored);
    }

    @Override
    public Entities forUpdate() {
        if (forUpdate) {
            return this;
        }
        return new EntitiesImpl(sqlClient, true, con, purpose, rootUserFiltersIgnored);
    }

    @Override
    public Entities forConnection(Connection con) {
        if (this.con == con) {
            return this;
        }
        return new EntitiesImpl(sqlClient, forUpdate, con, purpose, rootUserFiltersIgnored);
    }

    public Entities forLoader() {
        if (purpose == ExecutionPurpose.LOAD) {
            return this;
        }
        return new EntitiesImpl(sqlClient, forUpdate, con, ExecutionPurpose.LOAD, rootUserFiltersIgnored);
    }

    public Entities forExporter() {
        if (purpose == ExecutionPurpose.EXPORT) {
            return this;
        }
        return new EntitiesImpl(sqlClient, forUpdate, con, ExecutionPurpose.EXPORT, rootUserFiltersIgnored);
    }

    public Entities forSaveCommandFetch(QueryReason reason) {
        ExecutionPurpose newPurpose = ExecutionPurpose.command(reason);
        if (purpose instanceof ExecutionPurpose.Command &&
                ((ExecutionPurpose.Command)purpose).getQueryReason() == reason &&
                rootUserFiltersIgnored) {
            return this;
        }
        return new EntitiesImpl(sqlClient, forUpdate, con, newPurpose, true);
    }

    @Override
    public <E> E findById(Class<E> type, Object id) {
        return sqlClient.getConnectionManager().execute(con, con -> findById(type, id, con));
    }

    @NotNull
    @Override
    public <T> T findOneById(Class<T> type, Object id) {
        T result = findById(type, id);
        if (result == null) {
            throw new EmptyResultException();
        }
        return result;
    }

    @Override
    public <T> List<T> findByIds(Class<T> type, Iterable<?> ids) {
        return sqlClient.getConnectionManager().execute(con, con -> findByIds(type, ids, con));
    }

    @Override
    public <ID, T> Map<ID, T> findMapByIds(Class<T> type, Iterable<ID> ids) {
        return sqlClient.getConnectionManager().execute(con, con -> findMapByIds(type, ids, con));
    }

    @Override
    public <E> E findById(Fetcher<E> fetcher, Object id) {
        return sqlClient.getConnectionManager().execute(con, con -> findById(fetcher, id, con));
    }

    @NotNull
    @Override
    public <E> E findOneById(Fetcher<E> fetcher, Object id) {
        E result = findById(fetcher, id);
        if (result == null) {
            throw new EmptyResultException();
        }
        return result;
    }

    @Override
    public <E> @NotNull List<E> findByIds(Fetcher<E> fetcher, Iterable<?> ids) {
        return sqlClient.getConnectionManager().execute(con, con -> findByIds(fetcher, ids, con));
    }

    @Override
    public <ID, T> @NotNull Map<ID, T> findMapByIds(Fetcher<T> fetcher, Iterable<ID> ids) {
        return sqlClient.getConnectionManager().execute(con, con -> findMapByIds(fetcher, ids, con));
    }

    private <T> T findById(Class<T> type, Object id, Connection con) {
        if (id instanceof Iterable<?>) {
            throw new IllegalArgumentException(
                    "id cannot be collection, do you want to call 'findByIds'?"
            );
        }
        List<T> rows = findByIds(type, null, Collections.singleton(id), con);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <T> List<T> findByIds(Class<T> type, Iterable<?> ids, Connection con) {
        return findByIds(type, null, ids, con);
    }

    @SuppressWarnings("unchecked")
    private <ID, T> Map<ID, T> findMapByIds(Class<T> type, Iterable<ID> ids, Connection con) {
        PropId idPropId = immutableTypeOf(type).getIdProp().getId();
        List<T> entities = findByIds(type, null, ids, con);
        Map<ID, T> map = new LinkedHashMap<>((entities.size() * 4 + 2) / 3);
        for (T entity : entities) {
            if (View.class.isAssignableFrom(type)) {
                map.put((ID) ((ImmutableSpi) (((View<?>) entity).toEntity())).__get(idPropId), entity);
            } else {
                map.put((ID) ((ImmutableSpi) entity).__get(idPropId), entity);
            }
        }
        return map;
    }

    private <E> E findById(Fetcher<E> fetcher, Object id, Connection con) {
        if (id instanceof Iterable<?>) {
            throw new IllegalArgumentException(
                    "id cannot be collection, do you want to call 'findByIds'?"
            );
        }
        List<E> rows = findByIds(fetcher.getJavaClass(), fetcher, Collections.singleton(id), con);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <E> List<E> findByIds(Fetcher<E> fetcher, Iterable<?> ids, Connection con) {
        return findByIds(fetcher.getJavaClass(), fetcher, ids, con);
    }

    @SuppressWarnings("unchecked")
    private <ID, E> Map<ID, E> findMapByIds(Fetcher<E> fetcher, Iterable<ID> ids, Connection con) {
        ImmutableType type = fetcher.getImmutableType();
        PropId idPropId = type.getIdProp().getId();
        List<E> entities = findByIds((Class<E>) type.getJavaClass(), fetcher, ids, con);
        Map<ID, E> map = new LinkedHashMap<>((entities.size() * 4 + 2) / 3);
        for (E entity : entities) {
            map.put((ID) ((ImmutableSpi) entity).__get(idPropId), entity);
        }
        return map;
    }

    /**
     * Internal (implementation, not public API) overload used by the optional
     * object-cache query execution. It is the ordinary filtered object-cache read
     * with two explicit pieces of information obtained from a fresh SQL skeleton:
     * the concrete cache owner to read from and the expected concrete immutable
     * type of each id.
     *
     * <p>Ordinary callers keep using {@link #findMapByIds(Class, Iterable)} /
     * {@link #findMapByIds(Fetcher, Iterable)} and never reach this method. A
     * non-null {@code expectedTypes} value that disagrees with the cached
     * payload's concrete type is reported as {@link CacheTypeMismatchException},
     * so the caller can fall back to ordinary SQL instead of reshaping a stale
     * payload. A missing cache returns an empty map, which makes the caller
     * decline the whole optimization.</p>
     */
    @SuppressWarnings("unchecked")
    public <ID, E> Map<ID, E> findMapByIdsForQuery(
            ImmutableType requestedType,
            Fetcher<E> requestedFetcher,
            ImmutableType cacheOwnerType,
            Iterable<ID> ids,
            Map<Object, ImmutableType> expectedTypes
    ) {
        return sqlClient
                .getConnectionManager()
                .execute(
                        con,
                        c -> findMapByIdsForQuery(
                                requestedType,
                                requestedFetcher,
                                cacheOwnerType,
                                ids,
                                expectedTypes,
                                null,
                                c
                        )
                );
    }

    /**
     * Authenticated overload used only by the optional object-cache query execution
     * after it has executed the filtered id-only skeleton. The {@link ObjectCacheQuerySeed}
     * proves that exact ids/types were already admitted by that skeleton on this exact
     * owned, inactive connection, so the per-id visibility query is skipped. The seed
     * is unforgeable (package-private mint), and {@link ObjectCacheQuerySeed#admits}
     * re-validates every binding here before any cache access; a rejected seed returns
     * an empty map, which makes the caller fall back to the whole original query.
     */
    @SuppressWarnings("unchecked")
    public <ID, E> Map<ID, E> findMapByIdsForQuery(
            ImmutableType requestedType,
            Fetcher<E> requestedFetcher,
            ImmutableType cacheOwnerType,
            Iterable<ID> ids,
            Map<Object, ImmutableType> expectedTypes,
            ObjectCacheQuerySeed seed
    ) {
        return sqlClient
                .getConnectionManager()
                .execute(
                        con,
                        c -> findMapByIdsForQuery(
                                requestedType,
                                requestedFetcher,
                                cacheOwnerType,
                                ids,
                                expectedTypes,
                                seed,
                                c
                        )
                );
    }

    @SuppressWarnings("unchecked")
    private <ID, E> Map<ID, E> findMapByIdsForQuery(
            ImmutableType requestedType,
            Fetcher<E> requestedFetcher,
            ImmutableType cacheOwnerType,
            Iterable<ID> ids,
            Map<Object, ImmutableType> expectedTypes,
            ObjectCacheQuerySeed seed,
            Connection con
    ) {
        // Snapshot the caller's group BEFORE authentication and use only this local
        // copy afterwards: `ids` is an arbitrary iterable whose iteration may mutate
        // the caller's map, so authenticating and then reading the caller's map (or
        // its keySet) could admit ids the filtered skeleton never established.
        Map<Object, ImmutableType> admittedTypes;
        if (seed == null) {
            admittedTypes = expectedTypes;
        } else {
            if (expectedTypes == null) {
                return Collections.emptyMap();
            }
            admittedTypes = Collections.unmodifiableMap(new LinkedHashMap<>(expectedTypes));
            if (!ObjectCacheQuerySeed.admits(
                    seed,
                    sqlClient,
                    con,
                    purpose,
                    forUpdate,
                    requestedType,
                    cacheOwnerType,
                    admittedTypes
            )) {
                // A forged, reused, mismatched or no-longer-inactive token must decline
                // before any cache access. A null seed is the ordinary raw bridge and
                // keeps its visibility check inside findByIds.
                return Collections.emptyMap();
            }
        }
        // Reuse the flat filtered read: the requested (declared) type keeps the
        // visibility check and the final shape, while the concrete cache owner and
        // the fresh per-id concrete type are threaded through the internal
        // overload. A missing cache or a filtered-out id comes back empty, which
        // makes the caller decline the whole optimization.
        List<E> entities = findByIds(
                (Class<E>) requestedType.getJavaClass(),
                requestedFetcher,
                cacheOwnerType,
                admittedTypes,
                seed,
                ids,
                con
        );
        // A cache payload that does not even carry its own id cannot be admitted; decline
        // the whole optimization instead of letting the map construction throw while
        // reading it. Genuine cache or database exceptions still propagate.
        PropId queryIdPropId = requestedType.getIdProp().getId();
        Map<ID, E> queryMap = new LinkedHashMap<>((entities.size() * 4 + 2) / 3);
        for (E entity : entities) {
            ImmutableSpi spi = (ImmutableSpi) entity;
            if (!spi.__isLoaded(queryIdPropId)) {
                return Collections.emptyMap();
            }
            queryMap.put((ID) spi.__get(queryIdPropId), entity);
        }
        return queryMap;
    }

    @SuppressWarnings("unchecked")
    private <E> List<E> findByIds(
            Class<E> type,
            Fetcher<E> fetcher,
            Iterable<?> ids,
            Connection con
    ) {
        return findByIds(type, fetcher, null, null, null, ids, con);
    }

    /**
     * Internal overload of {@link #findByIds(Class, Fetcher, Iterable, Connection)}.
     * Ordinary callers pass {@code null} for the two optional arguments and keep
     * the plain "object cache, otherwise SQL" behavior.
     *
     * <p>The object-cache query helper additionally passes the concrete
     * {@code cacheOwner} to read from and the fresh per-id concrete
     * {@code expectedTypes} obtained from the skeleton query. When
     * {@code expectedTypes} is non-null the method is in internal mode: it never
     * starts the ordinary SQL fallback, so a missing cache or a filtered-out id
     * returns empty and the caller declines the whole optimization. A cached
     * payload whose concrete type disagrees with the fresh row is reported as
     * {@link CacheTypeMismatchException} before any reshaping.</p>
     *
     * <p>When {@code seed} is non-null (already authenticated by the caller) the
     * filtered skeleton has positively established visibility for exactly these ids
     * on this owned, inactive connection, so the redundant per-id visibility query is
     * skipped and the admitted ids are used directly.</p>
     */
    @SuppressWarnings("unchecked")
    private <E> List<E> findByIds(
            Class<E> type,
            Fetcher<E> fetcher,
            ImmutableType cacheOwner,
            Map<Object, ImmutableType> expectedTypes,
            ObjectCacheQuerySeed seed,
            Iterable<?> ids,
            Connection con
    ) {
        Set<Object> distinctIds = distinctIds(ids);
        if (distinctIds.isEmpty()) {
            return Collections.emptyList();
        }

        if (View.class.isAssignableFrom(type)) {
            return findByIds(DtoMetadata.of((Class<? extends View<Object>>) type), ids, con);
        }

        ImmutableType immutableType = ImmutableType.get(type);
        Class<?> idClass = immutableType.getIdProp().getElementClass();
        for (Object id : distinctIds) {
            if (Converters.tryConvert(id, idClass) == null) {
                throw new IllegalArgumentException(
                        "The type of \"" +
                                immutableType.getIdProp() +
                                "\" must be \"" +
                                idClass.getName() +
                                "\", but the actual type is \"" +
                                id.getClass().getName() +
                                "\""
                );
            }
        }
        boolean internal = expectedTypes != null;
        ImmutableType owner = cacheOwner != null ? cacheOwner : immutableType;
        Cache<Object, E> cache = forUpdate ? null : sqlClient.getCaches().getObjectCache(owner);
        if (cache != null) {
            // A seed proves the filtered skeleton already admitted these exact ids on this
            // connection. An internal caller without a seed (a navigated FAKE-FK/filtered
            // child) has no such proof, so force the fresh per-id SQL visibility read that
            // also establishes target existence instead of trusting the ids as visible.
            Collection<Object> visibleIds = seed != null ?
                    expectedTypes.keySet() :
                    visibleCachedIds(immutableType, distinctIds, con, internal);
            if (visibleIds.isEmpty()) {
                return Collections.emptyList();
            }
            Map<Object, E> cachedMap = cache.getAll(
                    visibleIds,
                    new CacheEnvironment<>(
                            sqlClient,
                            con,
                            CacheLoader.objectLoader(
                                    sqlClient,
                                    con,
                                    (Class<E>) owner.getJavaClass()
                            ),
                            true
                    )
            );
            // Enforce the fresh concrete type per id before any shape/DTO
            // conversion, so a stale polymorphic payload is declined rather
            // than reshaped. Also require the payload to carry its own id equal
            // to the requested key: otherwise two admitted same-type rows whose
            // payloads were swapped would be silently re-keyed by payload id and
            // the association between requested id and content would be lost.
            if (internal) {
                for (Map.Entry<Object, E> e : cachedMap.entrySet()) {
                    ImmutableType expectedType = expectedTypes.get(e.getKey());
                    E entity = e.getValue();
                    if (entity != null && expectedType != null) {
                        ImmutableSpi spi = (ImmutableSpi) entity;
                        ImmutableType actualType = spi.__type();
                        if (actualType != expectedType) {
                            throw new CacheTypeMismatchException(
                                    "Object cache for \"" +
                                            owner +
                                            "\" returned id \"" +
                                            e.getKey() +
                                            "\" as an object of type \"" +
                                            actualType +
                                            "\", but the freshly loaded row is \"" +
                                            expectedType +
                                            "\""
                            );
                        }
                        if (!spi.__isLoaded(immutableType.getIdProp().getId()) ||
                                !Objects.equals(e.getKey(), spi.__get(immutableType.getIdProp().getId()))) {
                            // A malformed or mismatched payload cannot be admitted;
                            // decline the whole optimization so the original query runs.
                            return Collections.emptyList();
                        }
                    }
                }
            }
            List<E> entities = new ArrayList<>(cachedMap.size());
            for (E entity : cachedMap.values()) {
                if (entity != null) {
                    entities.add(entity);
                }
            }
            Shapes.reshape(sqlClient, con, entities, immutableType, fetcher, null);
            return entities;
        }
        if (internal) {
            // The internal query helper only routes a group here when its cache
            // exists; a vanished cache declines instead of starting a second loader.
            return Collections.emptyList();
        }
        ConfigurableRootQuery<?, E> query = Queries.createQuery(
                sqlClient,
                immutableType,
                purpose,
                FilterLevel.DEFAULT,
                rootUserFiltersIgnored,
                (q, table) -> {
                    Expression<Object> idProp = table.get(immutableType.getIdProp().getName());
                    if (distinctIds.size() == 1) {
                        q.where(idProp.eq(distinctIds.iterator().next()));
                    } else {
                        q.where(idProp.in(distinctIds));
                    }
                    return q.select(((Table<E>) table).fetch(fetcher));
                }
        );
        if (forUpdate) {
            query = query.forUpdate(true);
        }
        return query.execute(con);
    }

    @SuppressWarnings("unchecked")
    private <E> List<E> findByIds(
            DtoMetadata<?, ?> metadata,
            Iterable<?> ids,
            Connection con
    ) {
        Set<Object> distinctIds = distinctIds(ids);
        if (distinctIds.isEmpty()) {
            return Collections.emptyList();
        }

        Fetcher<?> fetcher = metadata.getFetcher();
        Function<?, E> converter = (Function<?, E>) metadata.getConverter();
        ImmutableType immutableType = metadata.getFetcher().getImmutableType();
        Class<?> idClass = immutableType.getIdProp().getElementClass();
        for (Object id : distinctIds) {
            if (Converters.tryConvert(id, idClass) == null) {
                throw new IllegalArgumentException(
                        "The type of \"" +
                        immutableType.getIdProp() +
                        "\" must be \"" +
                        idClass.getName() +
                        "\""
                );
            }
        }
        Cache<Object, E> cache = forUpdate ? null : sqlClient.getCaches().getObjectCache(immutableType);
        if (cache != null) {
            Collection<Object> visibleIds = visibleCachedIds(immutableType, distinctIds, con);
            if (visibleIds.isEmpty()) {
                return Collections.emptyList();
            }
            Collection<E> cachedEntities = cache.getAll(
                    visibleIds,
                    new CacheEnvironment<>(
                            sqlClient,
                            con,
                            CacheLoader.objectLoader(
                                    sqlClient,
                                    con,
                                    (Class<E>) immutableType.getJavaClass()
                            ),
                            true
                    )
            ).values();
            List<E> entities = new ArrayList<>(cachedEntities.size());
            for (E entity : cachedEntities) {
                if (entity != null) {
                    entities.add(entity);
                }
            }
            Shapes.reshape(sqlClient, con, entities, immutableType, fetcher, converter);
            return entities;
        }
        ConfigurableRootQuery<?, E> query = Queries.<E>createQuery(
                sqlClient,
                immutableType,
                purpose,
                FilterLevel.DEFAULT,
                rootUserFiltersIgnored,
                (q, table) -> {
                    Expression<Object> idProp = table.get(immutableType.getIdProp().getName());
                    if (distinctIds.size() == 1) {
                        q.where(idProp.eq(distinctIds.iterator().next()));
                    } else {
                        q.where(idProp.in(distinctIds));
                    }
                    return q.select(
                            new FetcherSelectionImpl<E>(
                                    table,
                                    (DtoMetadata<?, E>) metadata
                            )
                    );
                }
        );
        if (forUpdate) {
            query = query.forUpdate(true);
        }
        return query.execute(con);
    }

    private Collection<Object> visibleCachedIds(ImmutableType type, Set<Object> ids, Connection con) {
        return visibleCachedIds(type, ids, con, false);
    }

    private Collection<Object> visibleCachedIds(ImmutableType type, Set<Object> ids, Connection con, boolean force) {
        if (!force && (
                rootUserFiltersIgnored ||
                        purpose == ExecutionPurpose.LOAD ||
                        sqlClient.getFilters().getFilter(type) == null
        )) {
            return ids;
        }
        // Object caches are single-view. Access to a root ID must be checked in the
        // current filter context, including on a warm hit or a previously denied ID.
        return Queries.createQuery(
                sqlClient,
                type,
                purpose,
                FilterLevel.DEFAULT,
                (q, table) -> {
                    Expression<Object> id = table.get(type.getIdProp().getName());
                    q.where(ids.size() == 1 ? id.eq(ids.iterator().next()) : id.in(ids));
                    return q.select(id);
                }
        ).execute(con);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> List<T> findAll(Class<T> type) {
        if (View.class.isAssignableFrom(type)) {
            return find(DtoMetadata.of((Class<View<Object>>) type), null);
        }
        return find(ImmutableType.get(type), null, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> List<T> findAll(Class<T> type, TypedProp.Scalar<?, ?>... sortedProps) {
        if (View.class.isAssignableFrom(type)) {
            DtoMetadata<?, ?> metadata = DtoMetadata.of((Class<View<Object>>) type);
            return find(metadata, null, sortedProps);
        }
        return find(ImmutableType.get(type), null, null, sortedProps);
    }

    @Override
    public <E> List<E> findAll(Fetcher<E> fetcher, TypedProp.Scalar<?, ?>... sortedProps) {
        return find(fetcher.getImmutableType(), fetcher, null, sortedProps);
    }

    @Override
    public <E> List<E> findByExample(Example<E> example, TypedProp.Scalar<?, ?>... sortedProps) {
        ExampleImpl<E> exampleImpl = (ExampleImpl<E>) example;
        return find(exampleImpl.type(), null, exampleImpl, sortedProps);
    }

    @Override
    public <E> List<E> findByExample(Example<E> example, Fetcher<E> fetcher, TypedProp.Scalar<?, ?>... sortedProps) {
        ExampleImpl<E> exampleImpl = (ExampleImpl<E>) example;
        return find(exampleImpl.type(), fetcher, exampleImpl, sortedProps);
    }

    @Override
    public <E, V extends View<E>> List<V> findExample(Class<V> viewType, Example<E> example, TypedProp.Scalar<?, ?>... sortedProps) {
        return find(DtoMetadata.of(viewType), (ExampleImpl<E>) example, sortedProps);
    }

    private <E> List<E> find(
            ImmutableType type,
            Fetcher<E> fetcher,
            ExampleImpl<E> example,
            TypedProp.Scalar<?, ?>... sortedProps
    ) {
        if (fetcher != null && fetcher.getImmutableType() != type) {
            throw new IllegalArgumentException(
                    "The type of fetcher is \"" +
                    fetcher.getImmutableType() +
                    "\", it does not match the query root type \"" +
                    type +
                    "\""
            );
        }
        if (example != null && example.type() != type) {
            throw new IllegalArgumentException(
                    "The type of example is \"" +
                    example.type() +
                    "\", it does not match the query root type \"" +
                    type +
                    "\""
            );
        }
        MutableRootQueryImpl<Table<E>> query =
                new MutableRootQueryImpl<>(sqlClient, type, ExecutionPurpose.QUERY, FilterLevel.DEFAULT);
        Table<E> table = query.getTable();
        if (example != null) {
            example.applyTo(query);
        }
        for (TypedProp.Scalar<?, ?> sortedProp : sortedProps) {
            if (!sortedProp.unwrap().getDeclaringType().isAssignableFrom(type)) {
                throw new IllegalArgumentException(
                        "The sorted field \"" +
                        sortedProp +
                        "\" does not belong to the type \"" +
                        type +
                        "\" or its super types"
                );
            }
            Expression<?> expr = table.get(sortedProp.unwrap().getName());
            Order astOrder;
            if (sortedProp.isDesc()) {
                astOrder = expr.desc();
            } else {
                astOrder = expr.asc();
            }
            if (sortedProp.isNullsFirst()) {
                astOrder = astOrder.nullsFirst();
            }
            if (sortedProp.isNullsLast()) {
                astOrder = astOrder.nullsLast();
            }
            query.orderBy(astOrder);
        }
        return query.select(
                fetcher != null ? table.fetch(fetcher) : table
        ).execute(con);
    }

    @SuppressWarnings("unchecked")
    private <V> List<V> find(
            DtoMetadata<?, ?> metadata,
            ExampleImpl<?> example,
            TypedProp.Scalar<?, ?>... sortedProps
    ) {
        Fetcher<?> fetcher = metadata.getFetcher();
        ImmutableType type = fetcher.getImmutableType();
        MutableRootQueryImpl<Table<?>> query =
                new MutableRootQueryImpl<>(sqlClient, type, ExecutionPurpose.QUERY, FilterLevel.DEFAULT);
        if (example != null) {
            example.applyTo(query);
        }
        Table<?> table = query.getTable();
        for (TypedProp.Scalar<?, ?> sortedProp : sortedProps) {
            if (!sortedProp.unwrap().getDeclaringType().isAssignableFrom(type)) {
                throw new IllegalArgumentException(
                        "The sorted field \"" +
                        sortedProp +
                        "\" does not belong to the type \"" +
                        type +
                        "\" or its super types"
                );
            }
            Expression<?> expr = table.get(sortedProp.unwrap().getName());
            Order astOrder;
            if (sortedProp.isDesc()) {
                astOrder = expr.desc();
            } else {
                astOrder = expr.asc();
            }
            if (sortedProp.isNullsFirst()) {
                astOrder = astOrder.nullsFirst();
            }
            if (sortedProp.isNullsLast()) {
                astOrder = astOrder.nullsLast();
            }
            query.orderBy(astOrder);
        }
        return query.<V>select(
                new FetcherSelectionImpl<V>(
                        table,
                        (DtoMetadata<?, V>) metadata
                )
        ).execute(con);
    }

    @Override
    public <E> SimpleEntitySaveCommand<E> saveCommand(E entity) {
        if (entity instanceof Iterable<?>) {
            throw new IllegalArgumentException("entity cannot be collection, do you want to call `saveEntities/saveEntitiesCommand`?");
        }
        if (entity instanceof Input<?>) {
            throw new IllegalArgumentException(
                    "entity cannot be input, " +
                    "please call another overloaded function whose parameter is input"
            );
        }
        return new SimpleEntitySaveCommandImpl<>(sqlClient, con, entity);
    }

    @Override
    public <E> BatchEntitySaveCommand<E> saveEntitiesCommand(Iterable<E> entities) {
        for (E e : entities) {
            if (e instanceof Input<?>) {
                throw new IllegalArgumentException(
                        "the collection cannot contains input, " +
                        "please call another overloaded function `saveInputsCommand`"
                );
            }
        }
        return new BatchEntitySaveCommandImpl<>(sqlClient, con, entities);
    }

    @Override
    public DeleteCommand deleteCommand(
            Class<?> type,
            Object id
    ) {
        if (id instanceof Iterable<?>) {
            throw new IllegalArgumentException("`id` cannot be iterable, do you want to call `deleteAll/deleteAllCommand`?");
        }
        if ((id instanceof ImmutableSpi && ((ImmutableSpi) id).__type().isEntity()) || id instanceof Input<?>) {
            throw new IllegalArgumentException("`id` must be simple type");
        }
        return deleteAllCommand(type, Collections.singleton(id));
    }

    @Override
    public DeleteCommand deleteAllCommand(
            Class<?> type,
            Iterable<?> ids
    ) {
        for (Object id : ids) {
            if ((id instanceof ImmutableSpi && ((ImmutableSpi) id).__type().isEntity()) || id instanceof Input<?>) {
                throw new IllegalArgumentException("All the elements of `ids` must be simple type");
            }
        }
        ImmutableType immutableType = immutableTypeOf(type);
        return new DeleteCommandImpl(sqlClient, con, immutableType, ids);
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> distinctIds(Iterable<?> values) {
        if (values == null) {
            return Collections.emptySet();
        }
        if (values instanceof Set<?> && !((Set<?>) values).contains(null)) {
            return (Set<Object>) values;
        }
        Set<Object> set;
        if (values instanceof Collection<?>) {
            Collection<?> c = (Collection<?>)values;
            if (c.isEmpty()) {
                return Collections.emptySet();
            }
            if (c instanceof Set<?> && !c.contains(null)) {
                return (Set<Object>)c;
            }
            set = new LinkedHashSet<>((c.size() * 4 + 2) / 3);
        } else {
            set = new LinkedHashSet<>();
        }
        for (Object value : values) {
            if (value != null) {
                set.add(value);
            }
        }
        return set;
    }
}
