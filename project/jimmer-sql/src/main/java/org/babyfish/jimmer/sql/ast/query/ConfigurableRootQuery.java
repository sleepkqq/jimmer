package org.babyfish.jimmer.sql.ast.query;

import org.babyfish.jimmer.Page;
import org.babyfish.jimmer.Slice;
import org.babyfish.jimmer.lang.NewChain;
import org.babyfish.jimmer.sql.ast.Expression;
import org.babyfish.jimmer.sql.ast.impl.query.ConfigurableRootQueryImpl;
import org.babyfish.jimmer.sql.ast.table.spi.TableLike;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.function.BiFunction;

public interface ConfigurableRootQuery<T extends TableLike<?>, R> extends TypedRootQuery<R> {

    /**
     * Ignore the sorting and pagination settings of the current query, 
     * query the total number of data before pagination
     *
     * <p>
     *     In general, users do not need to directly use this method, 
     *      but call the {@link #fetchPage(int, int)} method instead
     * </p>
     * 
     * @return Total row count before pagination
     */
    default long fetchUnlimitedCount() {
        return fetchUnlimitedCount(null);
    }

    /**
     * Ignore the sorting and pagination settings of the current query, 
     * query the total number of data before pagination
     *
     * <p>
     *     In general, users do not need to directly use this method, 
     *      but call the {@link #fetchPage(int, int, Connection)} method instead
     * </p>
     *
     * @return Total row count before pagination
     */
    default long fetchUnlimitedCount(Connection con) {
        return reselect((q, t) -> q.select(Expression.rowCount()))
            .withoutSortingAndPaging()
            .execute(con)
            .get(0);
    }

    default boolean exists() {
        return exists(null);
    }

    default boolean exists(Connection con) {
        return !limit(1, 0L)
                .reselect((q, t) -> q.select(Expression.constant(1)))
                .execute(con)
                .isEmpty();
    }

    @NotNull
    default <P> P fetchPage(int pageIndex, int pageSize, PageFactory<R, P> pageFactory) {
        return fetchPage(pageIndex, pageSize, null, pageFactory);
    }

    @NotNull
    <P> P fetchPage(int pageIndex, int pageSize, Connection con, PageFactory<R, P> pageFactory);

    @NotNull
    default Page<R> fetchPage(int pageIndex, int pageSize) {
        return fetchPage(pageIndex, pageSize, null, PageFactory.standard());
    }

    @NotNull
    default Page<R> fetchPage(int pageIndex, int pageSize, Connection con) {
        return fetchPage(pageIndex, pageSize, con, PageFactory.standard());
    }

    Slice<R> fetchSlice(int limit, int offset, @Nullable Connection con);

    default Slice<R> fetchSlice(int limit, int offset) {
        return fetchSlice(limit, offset, null);
    }

    @NewChain
    <X> ConfigurableRootQuery<T, X> reselect(
            BiFunction<MutableRootQuery<T>, T, ConfigurableRootQuery<T, X>> block
    );

    @NewChain
    ConfigurableRootQuery<T, R> distinct();

    @NewChain
    ConfigurableRootQuery<T, R> limit(int limit);

    @NewChain
    ConfigurableRootQuery<T, R> offset(long offset);

    @NewChain
    ConfigurableRootQuery<T, R> limit(int limit, long offset);

    @NewChain
    ConfigurableRootQuery<T, R> withoutSortingAndPaging();

    /**
     * @return If the original query does not have `order by` clause, returns null
     */
    @NewChain
    @Nullable
    ConfigurableRootQuery<T, R> reverseSorting();

    @NewChain
    ConfigurableRootQuery<T, R> setReverseSortOptimizationEnabled(boolean enabled);

    /**
     * <p>Optional hint: try to serve the entity part of this query from the configured
     * object cache instead of re-reading entity columns from the database.</p>
     *
     * <p><b>The entity content is eventual, not a same-statement snapshot.</b> The query
     * always executes SQL for membership, ordering and pagination, and any fresh scalar
     * slots are read from that SQL; the entity slots are then hydrated from the object
     * cache. A concurrent writer can therefore make the fresh SQL predicates/scalars
     * disagree with the possibly-stale cached entity content of a returned row.</p>
     *
     * <p>This is only a hint. If the query shape is not supported (for example an
     * aggregation, distinct, effective join-fetch, unsupported table shape, or a command
     * /load purpose), no object cache is configured, the transaction state cannot be
     * positively proven inactive, or the cached content does not cover the selected
     * rows, the query transparently falls back to the ordinary SQL execution.
     * {@link #forUpdate()} always disables this hint.</p>
     *
     * <p>The default execution path, {@link #stream(Connection)}, {@link #forEach}, count
     * and existence are unaffected and keep their ordinary semantics. Because a cold or
     * mixed cache may load missing entries, the hint can add SQL round-trips.</p>
     *
     * @return A new query object with the hint enabled
     */
    @NewChain
    default ConfigurableRootQuery<T, R> useObjectCache() {
        return useObjectCache(true);
    }

    /**
     * <p>Enable or disable the optional object-cache hint. The default is disabled,
     * so ordinary queries are unchanged.</p>
     *
     * @param enabled Whether the hint is enabled
     * @return A new query object
     */
    @NewChain
    default ConfigurableRootQuery<T, R> useObjectCache(boolean enabled) {
        return this;
    }

    @NewChain
    default ConfigurableRootQuery<T, R> forUpdate() {
        return forUpdate(true);
    }

    @NewChain
    ConfigurableRootQuery<T, R> forUpdate(boolean forUpdate);

    @NewChain
    ConfigurableRootQuery<T, R> forUpdate(LockMode lockMode, LockWait lockWait);

    /**
     * Set the hint
     * @param hint Optional hint, both <b>/&#42;+ sth &#42;/</b> and <b>sth</b> are OK.
     * @return A new query object
     */
    @NewChain
    ConfigurableRootQuery<T, R> hint(@Nullable String hint);
}
