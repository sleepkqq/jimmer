package org.babyfish.jimmer.sql.ast.impl.query;

import org.babyfish.jimmer.Slice;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.Selection;
import org.babyfish.jimmer.sql.ast.impl.Ast;
import org.babyfish.jimmer.sql.ast.impl.AstContext;
import org.babyfish.jimmer.sql.ast.impl.AstVisitor;
import org.babyfish.jimmer.sql.ast.impl.table.TableImplementor;
import org.babyfish.jimmer.sql.ast.query.*;
import org.babyfish.jimmer.sql.ast.table.BaseTable;
import org.babyfish.jimmer.sql.ast.table.spi.TableLike;
import org.babyfish.jimmer.sql.ast.tuple.Tuple3;
import org.babyfish.jimmer.sql.cache.CacheDisableConfig;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.fetcher.impl.FetcherUtil;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.Selectors;
import org.babyfish.jimmer.sql.runtime.SqlBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class ConfigurableRootQueryImpl<T extends TableLike<?>, R>
        extends AbstractConfigurableTypedQueryImpl
        implements ConfigurableRootQuery<T, R>, TypedRootQueryImplementor<R> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurableRootQueryImpl.class);

    ConfigurableRootQueryImpl(
            TypedQueryData data,
            MutableRootQueryImpl<T> baseQuery
    ) {
        super(data, baseQuery);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutableRootQueryImpl<T> getMutableQuery() {
        return (MutableRootQueryImpl<T>) super.getMutableQuery();
    }

    @Override
    public long fetchUnlimitedCount(final Connection con) {
        if (getMutableQuery().getTable() instanceof BaseTable || getMutableQuery().isGroupByClauseUsed()) {
            return simpleCount(con);
        }
        return ConfigurableRootQuery.super.fetchUnlimitedCount(con);
    }

    @Override
    public boolean exists(final Connection con) {
        if (getMutableQuery().getTable() instanceof BaseTable) {
            return ((ConfigurableRootQueryImpl<?, ?>)limit(1)).simpleExists(con);
        }
        return ConfigurableRootQuery.super.exists(con);
    }

    @Override
    public <P> @NotNull P fetchPage(int pageIndex, int pageSize, Connection con, PageFactory<R, P> pageFactory) {
        if (pageSize == 0 || pageSize == -1 || pageSize == Integer.MAX_VALUE) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "For meaningless pageSize {}, avoid pagination and fetch all rows directly",
                        pageSize
                );
            }
            List<R> rows = execute(con);
            return pageFactory.create(
                    rows,
                    rows.size(),
                    PageSource.of(0, Integer.MAX_VALUE, getMutableQuery())
            );
        }
        if (pageIndex < 0) {
            LOGGER.info("pageIndex is negative, returns empty list directly");
            return pageFactory.create(
                    Collections.emptyList(),
                    0,
                    PageSource.of(0, pageSize, getMutableQuery())
            );
        }

        long offset = (long) pageIndex * pageSize;
        if (offset > Long.MAX_VALUE - pageSize) {
            throw new IllegalArgumentException("offset is too big");
        }
        long total = fetchUnlimitedCount(con);
        if (offset >= total) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "pageIndex(starts from 0) is {} but the total page count is {}, " +
                                "returns empty list directly",
                        pageIndex,
                        (total + pageSize - 1) / pageSize
                );
            }
            return pageFactory.create(
                    Collections.emptyList(),
                    total,
                    PageSource.of(pageIndex, pageSize, getMutableQuery())
            );
        }

        ConfigurableRootQuery<?, R> reversedQuery = null;
        boolean reverseSortOptimization;
        if (getData().reverseSortOptimizationEnabled != null) {
            reverseSortOptimization = getData().reverseSortOptimizationEnabled;
        } else {
            reverseSortOptimization = getSqlClient().isReverseSortOptimizationEnabled();
        }
        if (reverseSortOptimization && offset + pageSize / 2 > total / 2) {
            LOGGER.info("Enable reverse sorting optimization, all sorting behaviors will be reversed");
            reversedQuery = reverseSorting();
        }

        List<R> rows;
        if (reversedQuery != null) {
            int limit;
            long reversedOffset = (int) (total - offset - pageSize);
            if (reversedOffset < 0) {
                limit = pageSize + (int) reversedOffset;
                reversedOffset = 0;
            } else {
                limit = pageSize;
            }
            rows = reversedQuery
                    .limit(limit, reversedOffset)
                    .execute(con);
            Collections.reverse(rows);
        } else {
            rows = limit(pageSize, offset).execute(con);
        }
        return pageFactory.create(
                rows,
                total,
                PageSource.of(pageIndex, pageSize, getMutableQuery())
        );
    }

    @Override
    public Slice<R> fetchSlice(int limit, int offset, @Nullable Connection con) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit cannot be less than 1");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be less than 0");
        }
        List<R> rows = limit(limit + 1).offset(offset).execute(con);
        if (rows.size() <= limit) {
            return new Slice<>(rows, offset == 0, true);
        }
        return new Slice<>(rows.subList(0, rows.size() - 1), offset == 0, false);
    }

    @Override
    public <X> ConfigurableRootQuery<T, X> reselect(
            BiFunction<MutableRootQuery<T>, T, ConfigurableRootQuery<T, X>> block
    ) {
        if (getData().oldSelections != null) {
            throw new IllegalStateException("The current query has been reselected, it cannot be reselect again");
        }
        MutableRootQueryImpl<T> baseQuery = getMutableQuery();
        if (baseQuery.isGroupByClauseUsed()) {
            throw new IllegalStateException("The current query uses group by clause, it cannot be reselected");
        }

        AstContext astContext = new AstContext(baseQuery.getSqlClient());
        AstVisitor visitor = new ReselectValidator(astContext);
        astContext.pushStatement(baseQuery);
        try {
            for (Selection<?> selection : getData().selections) {
                Ast.from(selection, visitor.getAstContext()).accept(visitor);
            }
        } finally {
            astContext.popStatement();
        }
        ConfigurableRootQuery<T, X> reselected = block.apply(
                baseQuery,
                baseQuery.getTable()
        );
        List<Selection<?>> selections = ((ConfigurableRootQueryImpl<T, X>) reselected).getData().selections;
        return new ConfigurableRootQueryImpl<>(
                getData().reselect(selections, null),
                baseQuery
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> distinct() {
        TypedQueryData data = getData();
        if (data.distinct) {
            return this;
        }
        return new ConfigurableRootQueryImpl<>(
                data.distinct(),
                getMutableQuery()
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> limit(int limit) {
        return limitImpl(limit, null);
    }

    @Override
    public ConfigurableRootQuery<T, R> offset(long offset) {
        return limitImpl(null, offset);
    }

    @Override
    public ConfigurableRootQuery<T, R> limit(int limit, long offset) {
        return limitImpl(limit, offset);
    }

    private ConfigurableRootQuery<T, R> limitImpl(@Nullable Integer limit, @Nullable Long offset) {
        TypedQueryData data = getData();
        if (limit == null) {
            limit = data.limit;
        }
        if (offset == null) {
            offset = data.offset;
        }
        if (data.limit == limit && data.offset == offset) {
            return this;
        }
        if (limit < 0) {
            throw new IllegalArgumentException("'limit' can not be less than 0");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("'offsetValue' can not be less than 0");
        }
        return new ConfigurableRootQueryImpl<>(
                data.limit(limit, offset),
                getMutableQuery()
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> withoutSortingAndPaging() {
        TypedQueryData data = getData();
        if (data.withoutSortingAndPaging) {
            return this;
        }
        return new ConfigurableRootQueryImpl<>(
                data.withoutSortingAndPaging(),
                getMutableQuery()
        );
    }

    @Override
    @Nullable
    public ConfigurableRootQuery<T, R> reverseSorting() {
        TypedQueryData data = getData();
        if (data.reverseSorting) {
            return this;
        }
        boolean reversereverseSortOptimizationEnabled =
                data.reverseSortOptimizationEnabled != null ?
                        data.reverseSortOptimizationEnabled :
                        getSqlClient().isReverseSortOptimizationEnabled();
        if (!reversereverseSortOptimizationEnabled) {
            return this;
        }
        if (getMutableQuery().getOrders().isEmpty()) {
            return null;
        }
        return new ConfigurableRootQueryImpl<>(
                data.reverseSorting(),
                getMutableQuery()
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> setReverseSortOptimizationEnabled(boolean enabled) {
        TypedQueryData data = this.getData();
        if (Objects.equals(data.reverseSortOptimizationEnabled, enabled)) {
            return this;
        }
        return new ConfigurableRootQueryImpl<>(
                data.reverseSortOptimizationEnabled(enabled),
                getMutableQuery()
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> forUpdate(boolean forUpdate) {
        if (forUpdate) {
            return forUpdate(LockMode.UPDATE, LockWait.DEFAULT);
        }
        TypedQueryData data = getData();
        if (data.forUpdate == null) {
            return this;
        }
        return new ConfigurableRootQueryImpl<>(
                data.forUpdate(null),
                getMutableQuery()
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> forUpdate(LockMode lockMode, LockWait lockWait) {
        TypedQueryData data = getData();
        ForUpdate forUpdate = new ForUpdate(lockMode, lockWait);
        if (Objects.equals(data.forUpdate, forUpdate)) {
            return this;
        }
        return new ConfigurableRootQueryImpl<>(
                data.forUpdate(forUpdate),
                getMutableQuery()
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> hint(String hint) {
        TypedQueryData data = getData();
        return new ConfigurableRootQueryImpl<>(
                data.hint(hint),
                getMutableQuery()
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> useObjectCache(boolean enabled) {
        TypedQueryData data = getData();
        if (data.useObjectCache == enabled && data.cachedContent == null) {
            return this;
        }
        return new ConfigurableRootQueryImpl<>(
                data.useObjectCache(enabled),
                getMutableQuery()
        );
    }

    @Override
    public ConfigurableRootQuery<T, R> useObjectCache(Fetcher<?> cachedContent) {
        return new ConfigurableRootQueryImpl<>(
                getData().useObjectCache(cachedContent),
                getMutableQuery()
        );
    }

    Fetcher<?> getCachedContent() {
        TypedQueryData data = getData();
        if (!data.useObjectCache) {
            return null;
        }
        if (data.cachedContent != null) {
            return data.cachedContent;
        }
        Object table = getMutableQuery().getTableLikeImplementor();
        return table instanceof TableImplementor<?> ?
                getMutableQuery().getSqlClient().getCaches().getObjectCacheContentFetcher(
                        ((TableImplementor<?>) table).getImmutableType()
                ) : null;
    }

    @Override
    public List<R> execute(Connection con) {
        return getMutableQuery()
                .getSqlClient()
                .getSlaveConnectionManager(getData().forUpdate != null)
                .execute(con, this::executeImpl);
    }

    private long simpleCount(Connection con) {
        return getMutableQuery()
                .getSqlClient()
                .getSlaveConnectionManager(getData().forUpdate != null)
                .execute(con, this::simpleCountImpl);
    }

    private long simpleCountImpl(Connection con) {
        TypedQueryData data = getData();
        JSqlClientImplementor sqlClient = getMutableQuery().getSqlClient();
        Tuple3<String, List<Object>, List<Integer>> sqlResult = preExecute(
                sqlClient,
                QueryRenderMode.WITHOUT_SORTING_AND_PAGING
        );
        List<Object> rows = Selectors.select(
                sqlClient,
                con,
                "select count(1) from (" + sqlResult.get_1() + ") tb_simple_count__",
                sqlResult.get_2(),
                sqlResult.get_3(),
                Collections.singletonList(Expression.rowCount()),
                null,
                getMutableQuery().getPurpose(),
                data.jdbcOptions,
                data.forUpdate != null
        );
        return (Long)rows.get(0);
    }

    private boolean simpleExists(Connection con) {
        return getMutableQuery()
                .getSqlClient()
                .getSlaveConnectionManager(getData().forUpdate != null)
                .execute(con, this::simpleExistsImpl);
    }

    private boolean simpleExistsImpl(Connection con) {
        TypedQueryData data = getData();
        JSqlClientImplementor sqlClient = getMutableQuery().getSqlClient();
        Tuple3<String, List<Object>, List<Integer>> sqlResult = preExecute(
                sqlClient,
                QueryRenderMode.WITHOUT_NESTED_BASE_TABLE_SORTING_AND_PAGING
        );
        List<Object> rows = Selectors.select(
                sqlClient,
                con,
                "select 1 from (" + sqlResult.get_1() + ") tb_simple_exists__",
                sqlResult.get_2(),
                sqlResult.get_3(),
                Collections.singletonList(Expression.rowCount()),
                null,
                getMutableQuery().getPurpose(),
                data.jdbcOptions,
                getForUpdate() != null
        );
        return !rows.isEmpty();
    }

    private List<R> executeImpl(Connection con) {
        TypedQueryData data = getData();
        if (data.limit == 0) {
            return Collections.emptyList();
        }
        JSqlClientImplementor sqlClient = getMutableQuery().getSqlClient();
        boolean queryPurpose =
                getMutableQuery().getPurpose().getType() == ExecutionPurpose.Type.QUERY;
        if (getCachedContent() != null) {
            // An explicit or configured content mask is a cache-content hint, not an
            // authorization cache. Whenever it cannot be honored - a non-QUERY purpose,
            // a locking read, an unproven transaction state or any decline - the complete
            // original projection runs on a cache-disabled derived client, so no
            // SELECT-loaded association can serve stale or uncommitted content and the
            // render/read/loader stay consistent.
            if (data.forUpdate == null && queryPurpose) {
                ConnectionManager connectionManager = sqlClient.getSlaveConnectionManager(false);
                if (connectionManager.isTransactionKnownInactive(con)) {
                    List<R> rows =
                            ObjectCacheQueryExecution.tryExecute(this, con, sqlClient, connectionManager);
                    if (rows != null) {
                        return rows;
                    }
                }
            }
            return executeBypassed(con, data, sqlClient);
        }
        if (data.useObjectCache
                && data.forUpdate == null
                && queryPurpose) {
            ConnectionManager connectionManager = sqlClient.getSlaveConnectionManager(false);
            if (connectionManager.isTransactionKnownInactive(con)) {
                List<R> rows = ObjectCacheQueryExecution.tryExecute(this, con, sqlClient, connectionManager);
                if (rows != null) {
                    return rows;
                }
            }
        }
        Tuple3<String, List<Object>, List<Integer>> sqlResult = preExecute(sqlClient);
        return Selectors.select(
                sqlClient,
                con,
                sqlResult.get_1(),
                sqlResult.get_2(),
                sqlResult.get_3(),
                data.selections,
                data.tupleCreator,
                getMutableQuery().getPurpose(),
                data.jdbcOptions,
                data.forUpdate != null
        );
    }

    /**
     * Runs the complete original projection with all object caches disabled, used as the
     * content-mask hint's fallback. Rendering, reading and every loader use the same
     * derived client so the query's join decisions match the reader that consumes them.
     */
    private List<R> executeBypassed(Connection con, TypedQueryData data, JSqlClientImplementor sqlClient) {
        JSqlClientImplementor readClient = sqlClient.caches(CacheDisableConfig::disableAll);
        Tuple3<String, List<Object>, List<Integer>> sqlResult = preExecute(readClient, QueryRenderMode.NORMAL);
        // A nested field filter or converter may already have an ambient fetcher
        // context whose client is cache-enabled; the fallback must load every
        // association on this cache-disabled client to stay fresh and consistent.
        return FetcherUtil.withoutFetcherContext(() -> Selectors.select(
                readClient,
                con,
                sqlResult.get_1(),
                sqlResult.get_2(),
                sqlResult.get_3(),
                data.selections,
                data.tupleCreator,
                getMutableQuery().getPurpose(),
                data.jdbcOptions,
                data.forUpdate != null
        ));
    }

    /**
     * Package-private hook for {@link ObjectCacheQueryExecution}: resolves this
     * query's virtual predicates (updating {@link #getData()}) without freezing or
     * rendering the shared mutable query, so the caller can inspect the updated
     * projection for aggregation/slot analysis.
     */
    void resolveVirtualPredicatesForObjectCache() {
        MutableRootQueryImpl<T> mutableQuery = getMutableQuery();
        if (!mutableQuery.isFrozen()
                && (mutableQuery.hasVirtualPredicate() || getData().hasVirtualPredicate())) {
            applyVirtualPredicates(new AstContext(mutableQuery.getSqlClient()));
        }
    }

    /**
     * Package-private hook for {@link ObjectCacheQueryExecution}: applies global filters
     * for the union of this query's original/retained selections and the id-only skeleton
     * selections before the shared mutable query is frozen, so the frozen join analysis
     * stays correct for the original projection while only the skeleton is rendered.
     * Retained {@link TypedQueryData#oldSelections} (count/reselect) are preserved.
     */
    void prepareGlobalFiltersForObjectCache(List<Selection<?>> skeletonSelections) {
        MutableRootQueryImpl<T> mutableQuery = getMutableQuery();
        if (mutableQuery.isFrozen()) {
            return;
        }
        AstContext astContext = new AstContext(mutableQuery.getSqlClient());
        List<Selection<?>> filterSelections = new ArrayList<>(getData().selections);
        if (getData().oldSelections != null) {
            // Count/reselect shares the mutable query with the original projection.
            filterSelections.addAll(getData().oldSelections);
        }
        filterSelections.addAll(skeletonSelections);
        QueryAnalyzer analyzer = new QueryAnalyzer(astContext, this);
        mutableQuery.applyGlobalFilters(
                astContext,
                mutableQuery.getContext().getFilterLevel(),
                filterSelections,
                analyzer.analyzeJoinRequirements()
        );
    }

    /**
     * Package-private hook for {@link ObjectCacheQueryExecution}: renders the current
     * (skeleton) projection assuming {@link #resolveVirtualPredicatesForObjectCache()}
     * and {@link #prepareGlobalFiltersForObjectCache(List)} have already run. It neither
     * re-prepares predicates/filters nor renders the discarded original projection.
     */
    Tuple3<String, List<Object>, List<Integer>> renderForObjectCache() {
        return renderForObjectCache(getMutableQuery().getSqlClient());
    }

    /**
     * Package-private hook for {@link ObjectCacheQueryExecution}: renders the current
     * (skeleton) projection with an explicit client. The recursive content-mask path
     * renders on its locally derived, cache-disabled client so the join decisions of
     * the rendered SQL match the reader and loaders that consume it.
     */
    Tuple3<String, List<Object>, List<Integer>> renderForObjectCache(JSqlClientImplementor renderClient) {
        AstContext astContext = new AstContext(renderClient, QueryRenderMode.NORMAL);
        SqlBuilder builder = new SqlBuilder(astContext);
        QueryAnalyzer analyzer = new QueryAnalyzer(astContext, this);
        builder.setQueryAnalysis(analyzer.analyze());
        renderTo(builder);
        return builder.build();
    }

    /**
     * Package-private hook for {@link ObjectCacheQueryExecution}: whether any
     * selected expression is an aggregation, which makes an id-only entity
     * skeleton unsafe.
     */
    boolean hasAggregationSelection() {
        final boolean[] found = {false};
        AstContext astContext = new AstContext(getMutableQuery().getSqlClient());
        AstVisitor visitor = new AstVisitor(astContext) {
            @Override
            public boolean visitSubQuery(TypedSubQuery<?> subQuery) {
                return false;
            }

            @Override
            public void visitAggregation(String functionName, Expression<?> expression, String prefix) {
                found[0] = true;
            }
        };
        astContext.pushStatement(getMutableQuery());
        try {
            for (Selection<?> selection : getData().selections) {
                Ast.from(selection, astContext).accept(visitor);
            }
        } finally {
            astContext.popStatement();
        }
        return found[0];
    }

    @Override
    public Stream<R> stream(Connection con) {
        TypedQueryData data = getData();
        if (data.limit == 0) {
            return Stream.empty();
        }
        // A content-mask query never streams the cached hint; it stays a fresh,
        // whole-graph read on a cache-disabled derived client.
        JSqlClientImplementor sqlClient = getCachedContent() != null ?
                getMutableQuery().getSqlClient().caches(CacheDisableConfig::disableAll) :
                getMutableQuery().getSqlClient();
        Tuple3<String, List<Object>, List<Integer>> sqlResult = preExecute(sqlClient);
        return Selectors.stream(
                sqlClient,
                con,
                sqlResult.get_1(),
                sqlResult.get_2(),
                sqlResult.get_3(),
                data.selections,
                data.tupleCreator,
                getMutableQuery().getPurpose(),
                data.jdbcOptions,
                data.forUpdate != null
        );
    }

    @Override
    public <X> List<X> map(Connection con, Function<R, X> mapper) {
        List<R> rows = execute(con);
        List<X> mapped = new ArrayList<>(rows.size());
        for (R row : rows) {
            mapped.add(mapper.apply(row));
        }
        return mapped;
    }

    @Override
    public void forEach(Connection con, int batchSize, Consumer<R> consumer) {
        TypedQueryData data = getData();
        if (data.limit == 0) {
            return;
        }
        JSqlClientImplementor sqlClient = getMutableQuery().getSqlClient();
        int finalBatchSize = nonNull(
                sqlClient.getDialect().getForEachBatchSize(),
                batchSize > 0 ? batchSize : sqlClient.getDefaultBatchSize()
        );
        sqlClient.getSlaveConnectionManager(getData().forUpdate != null).execute(con, newConn -> {
            forEachImpl(newConn, finalBatchSize, consumer);
            return (Void) null;
        });
    }

    private static <T> T nonNull(T a, T b) {
        return a != null ? a : b;
    }

    private void forEachImpl(Connection con, int batchSize, Consumer<R> consumer) {
        // A content-mask query never uses the cached hint for forEach; it stays a
        // fresh, whole-graph read on a cache-disabled derived client. The legacy
        // hint without a configured policy keeps its ordinary client and batching.
        boolean masked = getCachedContent() != null;
        JSqlClientImplementor sqlClient = masked ?
                getMutableQuery().getSqlClient().caches(CacheDisableConfig::disableAll) :
                getMutableQuery().getSqlClient();
        Tuple3<String, List<Object>, List<Integer>> sqlResult = preExecute(sqlClient);
        Runnable read = () -> Selectors.forEach(
                sqlClient,
                con,
                sqlResult.get_1(),
                sqlResult.get_2(),
                sqlResult.get_3(),
                getData().selections,
                getData().tupleCreator,
                getMutableQuery().getPurpose(),
                batchSize,
                consumer,
                getForUpdate() != null
        );
        if (masked) {
            // A nested field filter or converter may already have an ambient fetcher
            // context whose client is cache-enabled; run the read in a fresh context so
            // every post-fetch loader uses this cache-disabled client.
            FetcherUtil.withoutFetcherContext(() -> {
                read.run();
                return null;
            });
        } else {
            read.run();
        }
    }

    private Tuple3<String, List<Object>, List<Integer>> preExecute(JSqlClientImplementor sqlClient) {
        return preExecute(sqlClient, QueryRenderMode.NORMAL);
    }

    private Tuple3<String, List<Object>, List<Integer>> preExecute(
            JSqlClientImplementor sqlClient,
            QueryRenderMode mode
    ) {
        AstContext astContext = new AstContext(sqlClient, mode);
        SqlBuilder builder = new SqlBuilder(astContext);
        QueryAnalyzer analyzer = new QueryAnalyzer(astContext, this);
        if (!getMutableQuery().isFrozen()) {
            applyVirtualPredicates(astContext);
            List<Selection<?>> filterSelections = getData().selections;
            if (getData().oldSelections != null) {
                // Count/reselect shares the mutable query with the original projection.
                // Prepare its subqueries before rendering freezes that shared query.
                filterSelections = new ArrayList<>(filterSelections);
                filterSelections.addAll(getData().oldSelections);
            }
            getMutableQuery().applyGlobalFilters(
                    astContext,
                    getMutableQuery().getContext().getFilterLevel(),
                    filterSelections,
                    analyzer.analyzeJoinRequirements()
            );
        }
        builder.setQueryAnalysis(analyzer.analyze());
        renderTo(builder);
        return builder.build();
    }

    @Override
    public ForUpdate getForUpdate() {
        return getData().forUpdate;
    }

    @Override
    public TypedRootQuery<R> withLimit(int limit) {
        if (getData().limit == Integer.MAX_VALUE) {
            return limit(limit);
        }
        return this;
    }

    private static class ReselectValidator extends AstVisitor {

        ReselectValidator(AstContext astContext) {
            super(astContext);
        }

        @Override
        public boolean visitSubQuery(TypedSubQuery<?> subQuery) {
            return false;
        }

        @Override
        public void visitAggregation(String functionName, Expression<?> expression, String prefix) {
            throw new IllegalStateException(
                    "The current query uses aggregation function in select clause, it cannot be reselected"
            );
        }
    }
}
