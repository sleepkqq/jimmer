package org.babyfish.jimmer.sql.kt.ast.query

import org.babyfish.jimmer.Page
import org.babyfish.jimmer.Slice
import org.babyfish.jimmer.lang.NewChain
import org.babyfish.jimmer.sql.ast.query.LockMode
import org.babyfish.jimmer.sql.ast.query.LockWait
import org.babyfish.jimmer.sql.ast.query.PageFactory
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.kt.ast.expression.constant
import org.babyfish.jimmer.sql.kt.ast.expression.rowCount
import org.babyfish.jimmer.sql.kt.ast.table.KPropsLike
import java.sql.Connection

interface KConfigurableRootQuery<P : KPropsLike, R> : KTypedRootQuery<R> {

    /**
     * Ignore the sorting and pagination settings of the current query,
     * query the total number of data before pagination
     *
     * <p>
     *     In general, users do not need to directly use this method,
     *      but call the `fetchPage` method instead
     * </p>
     *
     * @param con The explicit jdbc connection, null means using default connection
     * @return Total row count before pagination
     */
    fun fetchUnlimitedCount(con: Connection? = null): Long =
        reselect { select(rowCount()) }
            .withoutSortingAndPaging()
            .execute(con)[0]

    fun exists(con: Connection? = null): Boolean =
        limit(1)
            .reselect { select(constant(1)) }
            .execute(con).isNotEmpty()

    fun <P : Any> fetchPage(
        pageIndex: Int,
        pageSize: Int,
        con: Connection? = null,
        pageFactory: PageFactory<R, P>
    ): P

    fun fetchPage(
        pageIndex: Int,
        pageSize: Int,
        con: Connection? = null
    ): Page<R> = fetchPage(pageIndex, pageSize, con, PageFactory.standard())

    fun fetchSlice(
        limit: Int,
        offset: Int,
        con: Connection? = null
    ): Slice<R>

    @NewChain
    fun <X> reselect(
        block: KMutableRootQuery<P>.() -> KConfigurableRootQuery<P, X>
    ): KConfigurableRootQuery<P, X>

    @NewChain
    fun distinct(): KConfigurableRootQuery<P, R>

    @NewChain
    fun limit(limit: Int): KConfigurableRootQuery<P, R>

    @NewChain
    fun offset(offset: Long): KConfigurableRootQuery<P, R>

    @NewChain
    fun limit(limit: Int, offset: Long): KConfigurableRootQuery<P, R>

    @NewChain
    fun withoutSortingAndPaging(): KConfigurableRootQuery<P, R>

    /**
     * @return If the original query does not have `order by` clause, returns null
     */
    @NewChain
    fun reverseSorting(): KConfigurableRootQuery<P, R>?

    @NewChain
    fun setReverseSortOptimizationEnabled(enabled: Boolean): KConfigurableRootQuery<P, R>

    /**
     * Optional hint: try to serve the entity part of this query from the configured
     * object cache instead of re-reading entity columns from the database. The query
     * always executes SQL for membership, ordering, pagination, count and existence,
     * and transparently falls back to ordinary SQL when the hint cannot be applied.
     * Cached entity content is eventual, not a statement snapshot: concurrent writes
     * can make it disagree with fresh SQL predicates or scalar slots. The hint requires
     * a positively proven inactive transaction and is disabled for locking reads.
     *
     * @return A new query object with the hint enabled
     */
    @NewChain
    fun useObjectCache(): KConfigurableRootQuery<P, R> =
        useObjectCache(true)

    /**
     * Enable or disable the optional object-cache hint. The default is disabled,
     * so ordinary queries are unchanged.
     *
     * @param enabled Whether the hint is enabled
     * @return A new query object
     */
    @NewChain
    fun useObjectCache(enabled: Boolean): KConfigurableRootQuery<P, R> =
        this

    /**
     * Optional hint with an explicit cached-content fetcher: only the recursive
     * fetcher's stored display leaves come from the object cache, while association
     * edges and every other selected fact stay fresh SQL. The hint still requires a
     * positively proven inactive transaction and otherwise executes the complete
     * graph from fresh SQL.
     *
     * @param cachedContent Recursive fetcher defining the cacheable display whitelist
     * @return A new query object
     */
    @NewChain
    fun useObjectCache(cachedContent: Fetcher<*>): KConfigurableRootQuery<P, R> =
        throw UnsupportedOperationException("Cached-content fetchers are not supported by this query")

    @NewChain
    fun forUpdate(forUpdate: Boolean = true): KConfigurableRootQuery<P, R>

    @NewChain
    fun forUpdate(lockMode: LockMode, lockWait: LockWait): KConfigurableRootQuery<P, R>

    /**
     * Set the hint
     * @param hint Optional hint, both /&#42;+ sth &#42;/ and **sth** are OK.
     * @return A new query object
     */
    @NewChain
    fun hint(hint: String?): KConfigurableRootQuery<P, R>
}
