package io.quarkiverse.jimmer.runtime.repo

import org.babyfish.jimmer.sql.ast.tuple.Tuple2
import org.babyfish.jimmer.sql.kt.ast.query.KConfigurableRootQuery
import java.sql.Connection
import java.util.UUID

/**
 * Fetches one keyset page from a query whose selection is `(cursor, row)`.
 *
 * The cursor predicate and the `order by` clause belong **inside** the query — only the caller
 * knows which expression the ordering is built on:
 *
 * ```kotlin
 * sql.createQuery(KtPost::class) {
 *     where(table.id `gt?` cursor)
 *     orderBy(table.id.asc())
 *     select(table.id, table.title)
 * }.fetchUuidV7Slice(20)
 * ```
 *
 * Contract, none of which this function can verify:
 * - `_1` of the tuple **must** be the very expression used in `orderBy`. Otherwise the cursor
 *   drifts apart from the ordering and paging starts skipping or repeating rows.
 * - Direction is the caller's: `` `gt?` `` with `asc()`, or `` `lt?` `` with `desc()`.
 *   This function always takes the cursor from the last returned row, so both work.
 * - Nullability is a property of the **expression**, not of the construct it came from.
 *   Nullable cursor expressions arise from outer joins (`` table.`assoc?` ``), raw foreign-key
 *   accessors (`table.assocId`), and base-table columns whose source expression was itself
 *   nullable. A plain association accessor (`table.assoc`) is an *inner* join and stays
 *   non-null even when the association is declared nullable. When the expression is nullable,
 *   `select` produces `Tuple2<UUID?, R>` and this function rejects the query at compile time —
 *   strip it with `select(expr.asNonNull(), ...)`.
 * - The ordering/cursor expression **must be unique** across the result rows. With `` `gt?` ``
 *   a duplicated boundary value silently drops the rest of the tie group, and ordering by a
 *   non-unique expression is not even stable between two statements — rows can both repeat and
 *   vanish. Because the cursor is a single `UUID`, this API cannot express a composite
 *   `(expression, id)` keyset; if the ordering expression is not unique, this closer is the
 *   wrong tool. A primary key or any other unique v7 id is the intended case.
 * - `limit` is applied as `limit(limit + 1, 0)`, so any previously configured `limit` or
 *   `offset` is overwritten. Keyset paging does not need an offset.
 * - The cursor is not validated to be a version 7 UUID. Validate it where it enters the
 *   application — at the REST boundary — not here.
 *
 * @param limit page size, must be in `1..Int.MAX_VALUE - 1`
 * @param con explicit JDBC connection, `null` means the default one
 * @return rows of the page and the cursor to pass into the next call, `null` if this is the last page
 */
fun <R> KConfigurableRootQuery<*, Tuple2<UUID, R>>.fetchUuidV7Slice(
	limit: Int,
	con: Connection? = null
): UuidV7Slice<R> = fetchUuidV7Slice(limit, con, { it._2 }, { it._1 })

/**
 * Fetches one keyset page when the query row already contains its cursor.
 * The caller must apply the matching cursor predicate and ordering inside the query.
 */
fun <R> KConfigurableRootQuery<*, R>.fetchUuidV7Slice(
	limit: Int,
	con: Connection? = null,
	cursorOf: (R) -> UUID,
): UuidV7Slice<R> = fetchUuidV7Slice(limit, con, { it }, cursorOf)

private fun <T, R> KConfigurableRootQuery<*, T>.fetchUuidV7Slice(
	limit: Int,
	con: Connection?,
	rowOf: (T) -> R,
	cursorOf: (T) -> UUID,
): UuidV7Slice<R> {
	require(limit > 0) { "limit must be greater than 0" }
	require(limit < Int.MAX_VALUE) { "limit must be less than Int.MAX_VALUE" }
	val values = limit(limit + 1, 0).execute(con)
	val hasNext = values.size > limit
	val rows = if (hasNext) values.subList(0, limit) else values
	return UuidV7Slice(
		rows.map(rowOf),
		if (hasNext) cursorOf(rows.last()) else null,
	)
}

/**
 * Same as [fetchUuidV7Slice], plus a total row count obtained from this very query via
 * `fetchUnlimitedCount()`.
 *
 * **The total is not always what you expect.** `fetchUnlimitedCount` drops sorting and paging
 * but keeps the whole `where` clause — including the cursor predicate. So:
 * - with no cursor the total is the full row count matching the business filters;
 * - with a cursor it is the number of rows **left from that cursor**, current page included.
 *
 * Pass the total explicitly via the other overload when the honest number matters on page 2+.
 * Note also that this overload issues one extra SQL statement per call — the count and the
 * page are two separate statements, so outside a transaction they can disagree under
 * concurrent writes. Wrap the call in a transaction if `totalRowCount` must be consistent
 * with `rows`.
 */
fun <R> KConfigurableRootQuery<*, Tuple2<UUID, R>>.fetchUuidV7Page(
	limit: Int,
	con: Connection? = null
): UuidV7Page<R> {
	val slice = fetchUuidV7Slice(limit, con)
	val totalRowCount = fetchUnlimitedCount(con)
	return UuidV7Page(slice.rows, slice.nextCursor, totalRowCount)
}

/**
 * Same as [fetchUuidV7Slice], with a caller-supplied total row count.
 *
 * Use this when the total comes from somewhere cheaper or more correct than a second query —
 * a cached counter, a separate cursor-free count, a precomputed aggregate.
 *
 * @param totalRowCount passed into the result verbatim, never validated against the rows
 */
fun <R> KConfigurableRootQuery<*, Tuple2<UUID, R>>.fetchUuidV7Page(
	limit: Int,
	totalRowCount: Long,
	con: Connection? = null
): UuidV7Page<R> =
	fetchUuidV7Slice(limit, con).let {
		UuidV7Page(it.rows, it.nextCursor, totalRowCount)
	}
