package org.babyfish.jimmer.sql.ast.impl.query;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.meta.InheritanceInfo;
import org.babyfish.jimmer.meta.PropId;
import org.babyfish.jimmer.runtime.ImmutableSpi;
import org.babyfish.jimmer.sql.Entities;
import org.babyfish.jimmer.sql.InheritanceType;
import org.babyfish.jimmer.sql.ast.Selection;
import org.babyfish.jimmer.sql.ast.impl.AstContext;
import org.babyfish.jimmer.sql.ast.impl.EntitiesImpl;
import org.babyfish.jimmer.sql.ast.impl.table.FetcherSelectionImpl;
import org.babyfish.jimmer.sql.ast.impl.table.TableImplementor;
import org.babyfish.jimmer.sql.ast.impl.table.TableProxies;
import org.babyfish.jimmer.sql.ast.table.BaseTable;
import org.babyfish.jimmer.sql.ast.table.Table;
import org.babyfish.jimmer.sql.ast.table.spi.KTable;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.ast.tuple.Tuple3;
import org.babyfish.jimmer.sql.ast.tuple.Tuple4;
import org.babyfish.jimmer.sql.ast.tuple.Tuple5;
import org.babyfish.jimmer.sql.ast.tuple.Tuple6;
import org.babyfish.jimmer.sql.ast.tuple.Tuple7;
import org.babyfish.jimmer.sql.ast.tuple.Tuple8;
import org.babyfish.jimmer.sql.ast.tuple.Tuple9;
import org.babyfish.jimmer.sql.cache.CacheTypeMismatchException;
import org.babyfish.jimmer.sql.cache.CacheDisableConfig;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.fetcher.Field;
import org.babyfish.jimmer.sql.fetcher.impl.FetchPath;
import org.babyfish.jimmer.sql.fetcher.impl.FetcherImpl;
import org.babyfish.jimmer.sql.fetcher.impl.FetcherSelection;
import org.babyfish.jimmer.sql.fetcher.impl.FetcherUtil;
import org.babyfish.jimmer.sql.fetcher.impl.JoinFetchFieldVisitor;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.babyfish.jimmer.sql.runtime.Selectors;
import org.babyfish.jimmer.sql.runtime.TupleCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * <p>Optional object-cache execution for {@link ConfigurableRootQueryImpl}: the query
 * still executes SQL for membership, ordering, pagination and any fresh (uncacheable)
 * slot, but each cacheable entity slot is seeded with an id-only skeleton selection and
 * then read from the configured object cache instead of re-reading entity columns.</p>
 *
 * <p>This is deliberately conservative. The skeleton always re-queries SQL, so it is
 * <b>not</b> a membership/query cache and gives no same-statement snapshot guarantee
 * beyond the object cache's own eventual consistency. A slot is cached only when its
 * table is the root table or an ordinary non-polymorphic to-one join whose default
 * id-only machinery preserves existence/nullability/multiplicity; every other entity
 * slot stays a fresh full selection. Any missing cache entry, unknown transaction
 * state or unproven shape falls back to the ordinary SQL execution exactly once.</p>
 */
final class ObjectCacheQueryExecution {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectCacheQueryExecution.class);

    private static final TupleCreator<Object[]> IDENTITY_TUPLE_CREATOR = args -> args;

    private ObjectCacheQueryExecution() {
    }

    /**
     * @return the materialized rows, or {@code null} to fall back to ordinary SQL
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T extends org.babyfish.jimmer.sql.ast.table.spi.TableLike<?>, R> List<R> tryExecute(
            ConfigurableRootQueryImpl<T, R> query,
            Connection con,
            JSqlClientImplementor sqlClient,
            ConnectionManager connectionManager
    ) {
        // Resolve virtual predicates first so the updated projection is what the
        // aggregation/slot analysis below inspects.
        query.resolveVirtualPredicatesForObjectCache();
        TypedQueryData data = query.getData();
        MutableRootQueryImpl<T> mutableQuery = query.getMutableQuery();

        if (data.cachedContent != null) {
            return tryExecuteWithContent(query, con, sqlClient, connectionManager);
        }

        // ---- Strict eligibility -------------------------------------------------
        if (data.oldSelections != null || data.distinct || mutableQuery.isGroupByClauseUsed()) {
            return null;
        }
        if (mutableQuery.getTable() instanceof BaseTable) {
            return null;
        }
        if (query.hasAggregationSelection()) {
            return null;
        }
        List<Selection<?>> selections = data.selections;
        int size = selections.size();
        if (size == 0) {
            return null;
        }

        Slot[] slots = new Slot[size];
        List<Selection<?>> skeletonSelections = new ArrayList<>(size);
        int cacheableCount = 0;

        // Resolve the selected tables through the same AST resolver the renderer
        // uses, so a proxy and its implementor compare as the same logical table.
        AstContext astContext = new AstContext(sqlClient);
        astContext.pushStatement(mutableQuery);
        try {
            Table<?> rootTable = (Table<?>) mutableQuery.getTable();
            TableImplementor<?> rootImplementor = TableProxies.resolve(rootTable, astContext);

            for (int i = 0; i < size; i++) {
                Selection<?> selection = selections.get(i);

                // --- Fetcher selection: an entity or DTO slot ------------------
                if (selection instanceof FetcherSelection<?>) {
                    FetcherSelection<?> fetcherSelection = (FetcherSelection<?>) selection;
                    if (fetcherSelection.getEmbeddedPropExpression() != null) {
                        // Embedded projections are not part of the entity slot model.
                        return null;
                    }
                    Table<?> selectedTable = ((FetcherSelectionImpl<?>) selection).getTable();
                    TableImplementor<?> tableImplementor = TableProxies.resolve(selectedTable, astContext);
                    boolean root = tableImplementor == rootImplementor;
                    Fetcher<?> fetcher = fetcherSelection.getFetcher();
                    // The declared (cache owner) type comes from the fetcher; the
                    // id-only seed keeps the selected table's own type, so a
                    // polymorphic root still renders its discriminator.
                    ImmutableType entityType = fetcher != null ?
                            fetcher.getImmutableType() :
                            tableImplementor.getImmutableType();
                    ImmutableType seedType = tableImplementor.getImmutableType();
                    Function<?, ?> converter = fetcherSelection.getConverter();
                    if (isCacheableSlot(root, tableImplementor, entityType, fetcher, sqlClient)) {
                        slots[i] = Slot.cached(
                                entityType,
                                entityType.getIdProp().getId(),
                                fetcher,
                                converter
                        );
                        skeletonSelections.add(
                                ((Table) selectedTable).fetch(idOnlyFetcher(seedType))
                        );
                        cacheableCount++;
                    } else {
                        // Uncacheable ordinary slot: keep the fresh full selection
                        // (with its own joins) but defer its DTO conversion until
                        // every cacheable slot of the page has been validated.
                        slots[i] = Slot.fresh(converter);
                        skeletonSelections.add(stripConverter(fetcherSelection));
                    }
                    continue;
                }

                // --- Bare table selection: a fetched entity without a fetcher --
                Table<?> selectedTable = null;
                if (selection instanceof Table<?>) {
                    selectedTable = (Table<?>) selection;
                } else if (selection instanceof KTable<?>) {
                    selectedTable = ((KTable<?>) selection).getImplementor();
                }
                if (selectedTable == null) {
                    // Fresh scalar / expression slot, retained verbatim.
                    slots[i] = Slot.scalar();
                    skeletonSelections.add(selection);
                    continue;
                }
                TableImplementor<?> tableImplementor = TableProxies.resolve(selectedTable, astContext);
                boolean root = tableImplementor == rootImplementor;
                ImmutableType entityType = tableImplementor.getImmutableType();
                if (isCacheableSlot(root, tableImplementor, entityType, null, sqlClient)) {
                    slots[i] = Slot.cached(
                            entityType,
                            entityType.getIdProp().getId(),
                            null,
                            null
                    );
                    skeletonSelections.add(
                            ((Table) selectedTable).fetch(idOnlyFetcher(entityType))
                    );
                    cacheableCount++;
                } else {
                    slots[i] = Slot.fresh(null);
                    skeletonSelections.add(selection);
                }
            }

            if (cacheableCount == 0) {
                // At least one actually cacheable slot is required, otherwise the
                // ordinary projection is cheaper and safer.
                return null;
            }
        } finally {
            astContext.popStatement();
        }

        // ---- Skeleton: same predicates/order/paging, id-only cacheable slots --
        TypedQueryData skeletonData = data.skeleton(skeletonSelections, IDENTITY_TUPLE_CREATOR);
        ConfigurableRootQueryImpl<T, Object[]> skeleton =
                new ConfigurableRootQueryImpl<>(skeletonData, mutableQuery);

        // Prepare the global filters for the union of the original/retained and the
        // skeleton selections before the shared mutable query is frozen, then render
        // ONLY the skeleton: the original projection SQL is never rendered/discarded.
        query.prepareGlobalFiltersForObjectCache(skeletonSelections);
        Tuple3<String, List<Object>, List<Integer>> sqlResult = skeleton.renderForObjectCache();

        List<Object[]> seeds = Selectors.select(
                sqlClient,
                con,
                sqlResult.get_1(),
                sqlResult.get_2(),
                sqlResult.get_3(),
                skeletonData.selections,
                skeletonData.tupleCreator,
                mutableQuery.getPurpose(),
                data.jdbcOptions,
                false
        );
        if (seeds.isEmpty()) {
            return new ArrayList<>();
        }

        // ---- Hydrate every cacheable slot through the filtered object cache ----
        Entities entities = sqlClient.getEntities().forConnection(con);
        if (!(entities instanceof EntitiesImpl)) {
            // A custom Entities implementation cannot thread the expected concrete
            // type per id; decline rather than guess.
            return null;
        }
        EntitiesImpl entitiesImpl = ((EntitiesImpl) entities).forSqlClient(sqlClient);
        Map<Integer, Map<Object, Object>> hydratedBySlot = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            Slot slot = slots[i];
            if (!slot.cached) {
                continue;
            }
            // The fresh skeleton carries the actual concrete type per id. Prefer a
            // cache configured on the declared slot type; otherwise use the
            // (possibly subtype-only) cache of each concrete type. Every required
            // group must have a cache before any row is returned or converted.
            boolean declaredCache = sqlClient.getCaches().getObjectCache(slot.entityType) != null;
            Map<ImmutableType, Map<Object, ImmutableType>> groups = new LinkedHashMap<>();
            for (Object[] seed : seeds) {
                Object seedEntity = seed[i];
                if (seedEntity == null) {
                    continue;
                }
                Object id = ((ImmutableSpi) seedEntity).__get(slot.idPropId);
                if (id == null) {
                    continue;
                }
                ImmutableType expectedType = ((ImmutableSpi) seedEntity).__type();
                ImmutableType owner = declaredCache ? slot.entityType : expectedType;
                if (!declaredCache && sqlClient.getCaches().getObjectCache(owner) == null) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Object-cache hint declined: no object cache for the concrete type {} of slot {}",
                                owner,
                                slot.entityType
                        );
                    }
                    return null;
                }
                groups.computeIfAbsent(owner, it -> new LinkedHashMap<>()).put(id, expectedType);
            }
            Map<Object, Object> hydrated = new LinkedHashMap<>();
            try {
                for (Map.Entry<ImmutableType, Map<Object, ImmutableType>> group : groups.entrySet()) {
                    // The seed proves this exact id/type group was admitted by the
                    // filtered skeleton on this exact owned, inactive connection, so
                    // the bridge can skip the redundant per-id visibility query.
                    ObjectCacheQuerySeed admission = new ObjectCacheQuerySeed(
                            sqlClient,
                            con,
                            connectionManager,
                            slot.entityType,
                            group.getKey(),
                            group.getValue()
                    );
                    Map<Object, Object> groupHydrated = (Map<Object, Object>) entitiesImpl.findMapByIdsForQuery(
                            slot.entityType,
                            (Fetcher) slot.fetcher,
                            group.getKey(),
                            group.getValue().keySet(),
                            group.getValue(),
                            admission
                    );
                    hydrated.putAll(groupHydrated);
                }
            } catch (CacheTypeMismatchException ex) {
                // The cache returned a payload whose concrete type is incompatible
                // with the freshly loaded row. This is a misbehaving/shared cache,
                // not a database or resolver failure, so decline and let the
                // ordinary SQL run exactly once.
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Object-cache hint declined: {}", ex.getMessage());
                }
                return null;
            }
            // A missing/negative entry invalidates the whole skeleton: re-run the
            // original SQL once instead of shortening the page.
            for (Map<Object, ImmutableType> group : groups.values()) {
                for (Object id : group.keySet()) {
                    if (hydrated.get(id) == null) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "Object-cache hint declined: no cached value for {} id {}",
                                    slot.entityType,
                                    id
                            );
                        }
                        return null;
                    }
                }
            }
            hydratedBySlot.put(i, hydrated);
        }

        // ---- Rebuild the original projection using the existing tuple mechanism --
        // All DTO converters run here, exactly once per output row, only after every
        // cacheable slot of the page has been validated. A failed cache above
        // returned before this point, so no converter has run yet.
        List<R> results = new ArrayList<>(seeds.size());
        for (Object[] seed : seeds) {
            Object[] args = seed;
            for (int i = 0; i < size; i++) {
                Slot slot = slots[i];
                if (slot.scalar) {
                    continue;
                }
                Object value = args[i];
                if (slot.cached) {
                    if (value == null) {
                        continue;
                    }
                    Object id = ((ImmutableSpi) value).__get(slot.idPropId);
                    Object hydrated = id != null ? hydratedBySlot.get(i).get(id) : null;
                    if (hydrated != null && slot.converter != null) {
                        hydrated = ((Function<Object, Object>) slot.converter).apply(hydrated);
                    }
                    args[i] = hydrated;
                } else if (value != null && slot.converter != null) {
                    args[i] = ((Function<Object, Object>) slot.converter).apply(value);
                }
            }
            results.add((R) rebuild(data.tupleCreator, args));
        }
        return results;
    }

    /**
     * <p>Recursive content-mask execution: SQL reads the caller's projection with
     * only the whitelisted stored leaves removed, so membership, ordering, pagination,
     * current FK edges, concrete types and every non-whitelisted scalar stay fresh;
     * the whitelisted leaves are then filled from the configured reference object
     * caches. Association edges are never served from cache, and the cached payload is
     * never merged as a whole.</p>
     *
     * <p>The retained read runs on a locally derived, cache-disabled client so no
     * association loader can accidentally serve a cached whole target or property
     * edge; that client is used consistently for rendering, reading and every
     * loader-created target query. The original client is used only for the explicit
     * cache content loads, which reuse the authenticated seed bridge. Any missing,
     * negative or incompatible cached value falls back to the whole original query,
     * which keeps running on the original client.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends org.babyfish.jimmer.sql.ast.table.spi.TableLike<?>, R> List<R> tryExecuteWithContent(
            ConfigurableRootQueryImpl<T, R> query,
            Connection con,
            JSqlClientImplementor sqlClient,
            ConnectionManager connectionManager
    ) {
        TypedQueryData data = query.getData();
        MutableRootQueryImpl<T> mutableQuery = query.getMutableQuery();

        if (data.oldSelections != null || data.distinct || mutableQuery.isGroupByClauseUsed()) {
            return null;
        }
        if (mutableQuery.getTable() instanceof BaseTable) {
            return null;
        }
        if (query.hasAggregationSelection()) {
            return null;
        }
        List<Selection<?>> selections = data.selections;
        int size = selections.size();
        if (size == 0) {
            return null;
        }

        CacheContentMask rootMask = CacheContentMask.of(data.cachedContent);

        // The retained graph must not read any object cache: a locally derived,
        // cache-disabled client keeps every loader query fresh and consistent with the
        // rendered SQL. It is also the client whose join decisions the join-preservation
        // check must consult.
        JSqlClientImplementor readClient = sqlClient.caches(CacheDisableConfig::disableAll);

        AstContext astContext = new AstContext(sqlClient);
        astContext.pushStatement(mutableQuery);
        TableImplementor<?> rootImplementor;
        try {
            rootImplementor = TableProxies.resolve((Table<?>) mutableQuery.getTable(), astContext);
        } finally {
            astContext.popStatement();
        }

        MaskSlot[] slots = new MaskSlot[size];
        List<Selection<?>> retainedSelections = new ArrayList<>(size);
        int maskedCount = 0;
        boolean maskMatched = false;
        astContext = new AstContext(sqlClient);
        astContext.pushStatement(mutableQuery);
        try {
            for (int i = 0; i < size; i++) {
                Selection<?> selection = selections.get(i);
                if (selection instanceof FetcherSelection<?>) {
                    FetcherSelection<?> fetcherSelection = (FetcherSelection<?>) selection;
                    if (fetcherSelection.getEmbeddedPropExpression() != null) {
                        return null;
                    }
                    Table<?> selectedTable = ((FetcherSelectionImpl<?>) selection).getTable();
                    TableImplementor<?> tableImplementor =
                            TableProxies.resolve(selectedTable, astContext);
                    Fetcher<?> fetcher = fetcherSelection.getFetcher();
                    CacheContentMask node = maskNodeFor(rootMask, tableImplementor, rootImplementor);
                    if (node == null) {
                        slots[i] = MaskSlot.fresh(fetcherSelection.getConverter());
                        retainedSelections.add(stripConverter(fetcherSelection));
                        continue;
                    }
                    maskMatched = true;
                    Fetcher<?> retained = fetcher != null ?
                            CacheContentMask.retainedFetcher(fetcher, node, sqlClient) : null;
                    if (retained != null &&
                            node.hasCacheableLeaves(sqlClient) &&
                            !node.hasJoinedInheritanceCacheable(sqlClient)) {
                        slots[i] = MaskSlot.masked(node, fetcher, fetcherSelection.getConverter());
                        retainedSelections.add(retainedSelection(fetcherSelection, retained));
                        maskedCount++;
                    } else {
                        slots[i] = MaskSlot.fresh(fetcherSelection.getConverter());
                        retainedSelections.add(stripConverter(fetcherSelection));
                    }
                    continue;
                }
                Table<?> selectedTable = null;
                if (selection instanceof Table<?>) {
                    selectedTable = (Table<?>) selection;
                } else if (selection instanceof KTable<?>) {
                    selectedTable = ((KTable<?>) selection).getImplementor();
                }
                if (selectedTable == null) {
                    slots[i] = MaskSlot.scalar();
                    retainedSelections.add(selection);
                    continue;
                }
                TableImplementor<?> tableImplementor =
                        TableProxies.resolve(selectedTable, astContext);
                CacheContentMask node = maskNodeFor(rootMask, tableImplementor, rootImplementor);
                if (node == null) {
                    slots[i] = MaskSlot.fresh(null);
                    retainedSelections.add(selection);
                    continue;
                }
                maskMatched = true;
                // A bare entity-table selection fetches all table properties. Its
                // partial form cannot express association content selected by other
                // tuple slots, so only this node's own leaves apply.
                CacheContentMask bareMask = node.withoutChildren();
                Fetcher<?> all = allTableFieldsFetcher(tableImplementor.getImmutableType());
                Fetcher<?> retained = all != null ?
                        CacheContentMask.retainedFetcher(all, bareMask, sqlClient) : null;
                if (retained != null &&
                        bareMask.hasCacheableLeaves(sqlClient) &&
                        !bareMask.hasJoinedInheritanceCacheable(sqlClient)) {
                    slots[i] = MaskSlot.masked(bareMask, all, null);
                    retainedSelections.add(((Table) selectedTable).fetch(retained));
                    maskedCount++;
                } else {
                    slots[i] = MaskSlot.fresh(null);
                    retainedSelections.add(selection);
                }
            }
        } finally {
            astContext.popStatement();
        }
        if (!maskMatched) {
            throw new IllegalArgumentException(
                    "The object-cache content mask does not match any selected table"
            );
        }
        if (maskedCount == 0) {
            return null;
        }

        TypedQueryData retainedData = data.skeleton(retainedSelections, IDENTITY_TUPLE_CREATOR);
        ConfigurableRootQueryImpl<T, Object[]> retainedQuery =
                new ConfigurableRootQueryImpl<>(retainedData, mutableQuery);
        query.prepareGlobalFiltersForObjectCache(retainedSelections);

        // The retained read renders, reads and loads on the same cache-disabled derived
        // client, so no loader query can serve a cached whole target or property edge.
        Tuple3<String, List<Object>, List<Integer>> sqlResult =
                retainedQuery.renderForObjectCache(readClient);
        // A nested field filter or converter may already have an ambient fetcher
        // context whose client is the cache-enabled caller; run the retained read in a
        // fresh context so every post-fetch loader uses this cache-disabled client.
        List<Object[]> seeds = FetcherUtil.withoutFetcherContext(() -> Selectors.select(
                readClient,
                con,
                sqlResult.get_1(),
                sqlResult.get_2(),
                sqlResult.get_3(),
                retainedData.selections,
                retainedData.tupleCreator,
                mutableQuery.getPurpose(),
                data.jdbcOptions,
                false
        ));
        if (seeds.isEmpty()) {
            return new ArrayList<>();
        }

        Entities entities = sqlClient.getEntities().forConnection(con);
        if (!(entities instanceof EntitiesImpl)) {
            return null;
        }
        EntitiesImpl entitiesImpl = ((EntitiesImpl) entities).forSqlClient(sqlClient);

        // Collect the id/concrete-type seeds for every masked slot node (and its
        // navigated children) from the fresh retained graph, then load each node's
        // cache content once through the authenticated seed bridge.
        Map<CacheContentMask, Map<Object, ImmutableType>> nodeSeeds = new LinkedHashMap<>();
        // Proof is tracked per exact id and concrete type, never per node: a joined slot
        // and a navigated FAKE-FK reference can reach the same node with different ids, so
        // a node-level proof would leak the slot's admission onto the navigated id and
        // resurrect a warm but deleted target.
        Map<CacheContentMask, Map<Object, ImmutableType>> provenSeeds = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            MaskSlot slot = slots[i];
            if (slot == null || !slot.masked) {
                continue;
            }
            boolean ownLeaves = slot.node.hasCacheableOwnLeaves(sqlClient);
            PropId idPropId = slot.node.getType().getIdProp().getId();
            for (Object[] seed : seeds) {
                Object seedValue = seed[i];
                if (seedValue == null) {
                    continue;
                }
                ImmutableSpi fresh = (ImmutableSpi) seedValue;
                if (ownLeaves) {
                    Object id = fresh.__get(idPropId);
                    if (id != null) {
                        // The slot's own SQL selection (the guarded outer WHERE for a
                        // root table, the explicit join for a child table) admitted this
                        // exact row, so its id and concrete type are proven.
                        ImmutableType concreteType = fresh.__type();
                        nodeSeeds.computeIfAbsent(slot.node, it -> new LinkedHashMap<>())
                                .put(id, concreteType);
                        provenSeeds.computeIfAbsent(slot.node, it -> new LinkedHashMap<>())
                                .put(id, concreteType);
                    }
                }
                if (!CacheContentMask.collectSeeds(fresh, slot.node, nodeSeeds, provenSeeds, sqlClient)) {
                    // A cacheable navigated target's concrete type could not be
                    // established from the fresh read; fall back whole.
                    return null;
                }
            }
        }

        Map<CacheContentMask, Map<Object, ImmutableSpi>> cachedByNode = new LinkedHashMap<>();
        try {
            for (Map.Entry<CacheContentMask, Map<Object, ImmutableType>> e : nodeSeeds.entrySet()) {
                Map<Object, ImmutableSpi> cached = loadCacheContent(
                        sqlClient,
                        con,
                        connectionManager,
                        e.getKey().getType(),
                        e.getValue(),
                        provenSeeds.get(e.getKey()),
                        entitiesImpl
                );
                if (cached == null) {
                    return null;
                }
                cachedByNode.put(e.getKey(), cached);
            }
        } catch (CacheTypeMismatchException ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Object-cache content hint declined: {}", ex.getMessage());
            }
            return null;
        }

        // Validate the complete page before any converter runs, so a later incomplete
        // row/slot cannot leave an earlier converter already applied on the full fallback.
        for (Object[] seed : seeds) {
            for (int i = 0; i < size; i++) {
                MaskSlot slot = slots[i];
                if (slot == null || slot.scalar || !slot.masked) {
                    continue;
                }
                Object value = seed[i];
                if (value == null) {
                    continue;
                }
                if (!CacheContentMask.validate((ImmutableSpi) value, slot.node, cachedByNode, sqlClient)) {
                    return null;
                }
            }
        }

        // Rebuild the original projection: every converter runs here, exactly once per
        // output row, only after every masked slot of the page has been validated.
        List<R> results = new ArrayList<>(seeds.size());
        for (Object[] seed : seeds) {
            Object[] args = seed.clone();
            for (int i = 0; i < size; i++) {
                MaskSlot slot = slots[i];
                if (slot == null || slot.scalar) {
                    continue;
                }
                Object value = args[i];
                if (!slot.masked) {
                    if (value != null && slot.converter != null) {
                        args[i] = ((Function<Object, Object>) slot.converter).apply(value);
                    }
                    continue;
                }
                if (value == null) {
                    continue;
                }
                Object overlaid = CacheContentMask.overlay(
                        (ImmutableSpi) value, slot.projection, slot.node, cachedByNode, sqlClient
                );
                if (slot.converter != null) {
                    overlaid = ((Function<Object, Object>) slot.converter).apply(overlaid);
                }
                args[i] = overlaid;
            }
            results.add((R) rebuild(data.tupleCreator, args));
        }
        return results;
    }

    /**
     * Maps a selected table to the mask node reached by its join path from the root
     * table, so a whitelist entry for an association applies to whichever selected
     * slot targets that association. Returns {@code null} for a table that is not a
     * plain to-one join descent of the root or whose path is not whitelisted.
     */
    private static CacheContentMask maskNodeFor(
            CacheContentMask rootMask,
            TableImplementor<?> tableImplementor,
            TableImplementor<?> rootImplementor
    ) {
        List<ImmutableProp> path = new ArrayList<>();
        TableImplementor<?> table = tableImplementor;
        while (table != null && table != rootImplementor) {
            ImmutableProp joinProp = table.getJoinProp();
            if (joinProp == null || table.isTreated() || table.isRemote() || table.isInverse()) {
                return null;
            }
            path.add(0, joinProp);
            table = table.getParent();
        }
        if (table != rootImplementor) {
            return null;
        }
        CacheContentMask node = rootMask;
        for (ImmutableProp prop : path) {
            node = node.getChildren().get(prop);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    private static final class MaskSlot {

        final boolean scalar;

        final boolean masked;

        final CacheContentMask node;

        /** The original projection fetcher, source of truth for restored visibility. */
        final Fetcher<?> projection;

        final Function<?, ?> converter;

        private MaskSlot(
                boolean scalar,
                boolean masked,
                CacheContentMask node,
                Fetcher<?> projection,
                Function<?, ?> converter
        ) {
            this.scalar = scalar;
            this.masked = masked;
            this.node = node;
            this.projection = projection;
            this.converter = converter;
        }

        static MaskSlot scalar() {
            return new MaskSlot(true, false, null, null, null);
        }

        static MaskSlot fresh(Function<?, ?> converter) {
            return new MaskSlot(false, false, null, null, converter);
        }

        static MaskSlot masked(CacheContentMask node, Fetcher<?> projection, Function<?, ?> converter) {
            return new MaskSlot(false, true, node, projection, converter);
        }
    }

    /**
     * Loads one node's cached entities through the authenticated seed bridge. Proof is
     * per exact id: an id recorded in {@code provenIds} (with the same concrete type)
     * was admitted by the executing SQL, so the visibility-skipping seed is minted for
     * it. Every other id keeps its fresh per-id visibility read (the actual target read
     * boundary), so a deleted or filtered-out target is never resurrected from a warm
     * payload - including when a sibling joined slot proved a different id of the same
     * node. The cache owner is the declared type when it has a cache, otherwise each
     * concrete type's own cache; a vanished cache or any missing entry returns
     * {@code null} so the caller declines the whole optimization instead of shortening
     * the page.
     */
    private static Map<Object, ImmutableSpi> loadCacheContent(
            JSqlClientImplementor sqlClient,
            Connection con,
            ConnectionManager connectionManager,
            ImmutableType requestedType,
            Map<Object, ImmutableType> ids,
            Map<Object, ImmutableType> provenIds,
            EntitiesImpl entitiesImpl
    ) {
        if (ids.isEmpty()) {
            return new LinkedHashMap<>();
        }
        boolean declaredCache = sqlClient.getCaches().getObjectCache(requestedType) != null;
        Map<ImmutableType, Map<Object, ImmutableType>> groups = new LinkedHashMap<>();
        for (Map.Entry<Object, ImmutableType> e : ids.entrySet()) {
            ImmutableType owner = declaredCache ? requestedType : e.getValue();
            if (!declaredCache && sqlClient.getCaches().getObjectCache(owner) == null) {
                return null;
            }
            groups.computeIfAbsent(owner, it -> new LinkedHashMap<>()).put(e.getKey(), e.getValue());
        }
        Map<Object, ImmutableSpi> result = new LinkedHashMap<>();
        for (Map.Entry<ImmutableType, Map<Object, ImmutableType>> group : groups.entrySet()) {
            Map<Object, ImmutableType> proven = new LinkedHashMap<>();
            Map<Object, ImmutableType> fresh = new LinkedHashMap<>();
            for (Map.Entry<Object, ImmutableType> e : group.getValue().entrySet()) {
                ImmutableType provenType = provenIds != null ? provenIds.get(e.getKey()) : null;
                // Only an exact id/concrete-type match counts as proof; a mismatched or
                // duplicate id is conservatively fresh-checked.
                if (provenType == e.getValue()) {
                    proven.put(e.getKey(), provenType);
                } else {
                    fresh.put(e.getKey(), e.getValue());
                }
            }
            loadCacheGroup(sqlClient, con, connectionManager, requestedType, group.getKey(), proven, true, entitiesImpl, result);
            loadCacheGroup(sqlClient, con, connectionManager, requestedType, group.getKey(), fresh, false, entitiesImpl, result);
        }
        for (Object id : ids.keySet()) {
            if (!result.containsKey(id)) {
                return null;
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void loadCacheGroup(
            JSqlClientImplementor sqlClient,
            Connection con,
            ConnectionManager connectionManager,
            ImmutableType requestedType,
            ImmutableType owner,
            Map<Object, ImmutableType> ids,
            boolean proven,
            EntitiesImpl entitiesImpl,
            Map<Object, ImmutableSpi> result
    ) {
        if (ids.isEmpty()) {
            return;
        }
        ObjectCacheQuerySeed admission = proven ?
                new ObjectCacheQuerySeed(sqlClient, con, connectionManager, requestedType, owner, ids) :
                null;
        Map<Object, Object> loaded = (Map<Object, Object>) entitiesImpl.findMapByIdsForQuery(
                requestedType,
                (Fetcher) null,
                owner,
                ids.keySet(),
                ids,
                admission
        );
        for (Map.Entry<Object, Object> e : loaded.entrySet()) {
            if (e.getValue() != null) {
                result.put(e.getKey(), (ImmutableSpi) e.getValue());
            }
        }
    }

    /**
     * Copies a {@link FetcherSelection} with a different (retained) fetcher and its
     * converter removed, keeping the table and path intact.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Selection<?> retainedSelection(FetcherSelection<?> selection, Fetcher<?> retained) {
        FetcherSelectionImpl<?> impl = (FetcherSelectionImpl<?>) selection;
        Table<?> table = impl.getTable();
        FetchPath path = selection.getPath();
        if (path != null) {
            return new FetcherSelectionImpl(table, path, retained);
        }
        return new FetcherSelectionImpl(table, retained, (Function) null);
    }

    /**
     * Whether the entity slot can be served from the object cache with an id-only
     * skeleton seed. The root table keeps the existing behaviour (single-table
     * polymorphism and subtype-only caches included) except for a root
     * joined-inheritance projection, which is declined until the existing reader is
     * proven for the id-only seed. A non-root slot is admitted only as an ordinary
     * non-polymorphic to-one join without a target filter; the entity projection
     * retains that join so existence/nullability/multiplicity are unchanged.
     */
    private static boolean isCacheableSlot(
            boolean root,
            TableImplementor<?> tableImplementor,
            ImmutableType entityType,
            Fetcher<?> fetcher,
            JSqlClientImplementor sqlClient
    ) {
        if (!hasObjectCacheInHierarchy(sqlClient, entityType)) {
            return false;
        }
        if (fetcher != null && hasEffectiveJoinFetch(fetcher, sqlClient)) {
            return false;
        }
        if (root) {
            InheritanceInfo inheritanceInfo = entityType.getInheritanceInfo();
            return inheritanceInfo == null ||
                    inheritanceInfo.getStrategy() != InheritanceType.JOINED;
        }
        if (tableImplementor == null) {
            return false;
        }
        ImmutableProp joinProp = tableImplementor.getJoinProp();
        if (joinProp == null ||
                tableImplementor.isTreated() ||
                tableImplementor.isRemote() ||
                tableImplementor.isInverse()) {
            return false;
        }
        if (entityType.getInheritanceInfo() != null) {
            // Joined polymorphic/treated bodies are never partially projected.
            return false;
        }
        // A target filter turns the to-one join into a membership filter the
        // id-only seed cannot preserve.
        return sqlClient.getFilters().getTargetFilter(joinProp) == null;
    }

    private static boolean hasObjectCacheInHierarchy(JSqlClientImplementor sqlClient, ImmutableType type) {
        if (sqlClient.getCaches().getObjectCache(type) != null) {
            return true;
        }
        for (ImmutableType derived : type.getAllDerivedTypes()) {
            if (sqlClient.getCaches().getObjectCache(derived) != null) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Fetcher<?> idOnlyFetcher(ImmutableType entityType) {
        return new FetcherImpl<>(entityType.getJavaClass());
    }

    /**
     * The all-table-property projection a bare entity-table selection fetches. Reusing
     * the existing fetcher machinery keeps its partial retained form identical to an
     * explicit {@code fetch(allTableFields())}, so the mask can remove approved leaves
     * while membership and the remaining columns stay SQL.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Fetcher<?> allTableFieldsFetcher(ImmutableType entityType) {
        try {
            return new FetcherImpl<>(entityType.getJavaClass()).allTableFields();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Copies a fresh {@link FetcherSelection} with its DTO converter removed while
     * keeping the table, path, fetcher and joins intact, so the skeleton returns the
     * raw entity and the converter can run after cache validation.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Selection<?> stripConverter(FetcherSelection<?> selection) {
        if (selection.getConverter() == null) {
            return selection;
        }
        FetcherSelectionImpl<?> impl = (FetcherSelectionImpl<?>) selection;
        Table<?> table = impl.getTable();
        Fetcher<?> fetcher = selection.getFetcher();
        FetchPath path = selection.getPath();
        if (path != null) {
            return new FetcherSelectionImpl(table, path, fetcher);
        }
        return new FetcherSelectionImpl(table, fetcher, (Function) null);
    }

    /**
     * Detects an <em>effective</em> join-fetch in the projection by reusing the
     * same visitor the renderer uses: whenever the renderer would actually join
     * (respecting the configured max join-fetch depth and the type-branch
     * predicate), the projection is declined. Those joins shape membership and
     * row multiplicity, so the id-only skeleton cannot retain them.
     *
     * <p>SELECT-loaded associations are deliberately not declined: they are
     * hydrated after the skeleton query. The old shallow "any target filter"
     * check is intentionally not used.</p>
     */
    private static boolean hasEffectiveJoinFetch(Fetcher<?> fetcher, JSqlClientImplementor sqlClient) {
        final boolean[] found = {false};
        new JoinFetchFieldVisitor(sqlClient) {
            @Override
            protected Object enter(Field field) {
                found[0] = true;
                return null;
            }

            @Override
            protected void leave(Field field, Object enterValue) {
            }

            @Override
            protected boolean shouldVisitTypeBranch(ImmutableType branchType, Fetcher<?> branchFetcher) {
                // Same condition the renderer uses to decide whether a branch
                // contributes table fields (and therefore joins).
                return JoinFetchFieldVisitor.hasTableFields(branchFetcher, sqlClient, true);
            }
        }.visit(fetcher);
        return found[0];
    }

    private static Object rebuild(TupleCreator<?> tupleCreator, Object[] args) {
        if (tupleCreator != null) {
            return tupleCreator.createTuple(args);
        }
        if (args.length == 1) {
            return args[0];
        }
        switch (args.length) {
            case 2:
                return new Tuple2<>(args[0], args[1]);
            case 3:
                return new Tuple3<>(args[0], args[1], args[2]);
            case 4:
                return new Tuple4<>(args[0], args[1], args[2], args[3]);
            case 5:
                return new Tuple5<>(args[0], args[1], args[2], args[3], args[4]);
            case 6:
                return new Tuple6<>(args[0], args[1], args[2], args[3], args[4], args[5]);
            case 7:
                return new Tuple7<>(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 8:
                return new Tuple8<>(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            case 9:
                return new Tuple9<>(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
            default:
                throw new IllegalArgumentException(
                        "Object-cache hint supports between 1 and 9 selections, but there are " + args.length
                );
        }
    }

    private static final class Slot {

        final boolean scalar;

        final boolean cached;

        final ImmutableType entityType;

        final PropId idPropId;

        final Fetcher<?> fetcher;

        final Function<?, ?> converter;

        private Slot(
                boolean scalar,
                boolean cached,
                ImmutableType entityType,
                PropId idPropId,
                Fetcher<?> fetcher,
                Function<?, ?> converter
        ) {
            this.scalar = scalar;
            this.cached = cached;
            this.entityType = entityType;
            this.idPropId = idPropId;
            this.fetcher = fetcher;
            this.converter = converter;
        }

        static Slot scalar() {
            return new Slot(true, false, null, null, null, null);
        }

        static Slot fresh(Function<?, ?> converter) {
            return new Slot(false, false, null, null, null, converter);
        }

        static Slot cached(
                ImmutableType entityType,
                PropId idPropId,
                Fetcher<?> fetcher,
                Function<?, ?> converter
        ) {
            return new Slot(false, true, entityType, idPropId, fetcher, converter);
        }
    }
}
