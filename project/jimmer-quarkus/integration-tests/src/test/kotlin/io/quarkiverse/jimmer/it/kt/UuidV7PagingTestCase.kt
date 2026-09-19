package io.quarkiverse.jimmer.it.kt

import io.quarkiverse.jimmer.it.kt.entity.KtPost
import io.quarkiverse.jimmer.it.kt.entity.author
import io.quarkiverse.jimmer.it.kt.entity.`author?`
import io.quarkiverse.jimmer.it.kt.entity.authorId
import io.quarkiverse.jimmer.it.kt.entity.id
import io.quarkiverse.jimmer.it.kt.entity.name
import io.quarkiverse.jimmer.it.kt.entity.title
import io.quarkiverse.jimmer.runtime.Jimmer
import io.quarkiverse.jimmer.runtime.repo.fetchUuidV7Page
import io.quarkiverse.jimmer.runtime.repo.fetchUuidV7Slice
import io.quarkus.test.junit.QuarkusTest
import org.babyfish.jimmer.sql.kt.ast.expression.asNonNull
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.`gt?`
import org.babyfish.jimmer.sql.kt.ast.expression.isNotNull
import org.babyfish.jimmer.sql.kt.ast.expression.`like?`
import org.babyfish.jimmer.sql.kt.ast.expression.`lt?`
import org.babyfish.jimmer.sql.kt.ast.query.cteBaseTableSymbol
import org.babyfish.jimmer.sql.kt.toKSqlClient
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class UuidV7PagingTestCase {

	private val sql = Jimmer.getDefaultJSqlClient().toKSqlClient()

	private fun titlesAfter(cursor: UUID?, limit: Int) =
		sql.createQuery(KtPost::class) {
			where(table.id `gt?` cursor)
			orderBy(table.id.asc())
			select(table.id, table.title)
		}.fetchUuidV7Slice(limit)

	@Test
	fun ascPagingWalksAllRows() {
		val first = titlesAfter(null, 2)
		Assertions.assertEquals(listOf("post-1", "post-2"), first.rows)
		Assertions.assertEquals(
			UUID.fromString("0191c400-0001-7000-8000-000000000002"),
			first.nextCursor
		)
		Assertions.assertTrue(first.hasNext)

		val second = titlesAfter(first.nextCursor, 2)
		Assertions.assertEquals(listOf("post-3", "post-4"), second.rows)
		Assertions.assertTrue(second.hasNext)

		val last = titlesAfter(second.nextCursor, 2)
		Assertions.assertEquals(listOf("post-5"), last.rows)
		Assertions.assertNull(last.nextCursor)
		Assertions.assertFalse(last.hasNext)
	}

	@Test
	fun descPagingWalksBackwards() {
		fun page(cursor: UUID?) =
			sql.createQuery(KtPost::class) {
				where(table.id `lt?` cursor)
				orderBy(table.id.desc())
				select(table.id, table.title)
			}.fetchUuidV7Slice(2)

		val first = page(null)
		Assertions.assertEquals(listOf("post-5", "post-4"), first.rows)
		Assertions.assertEquals(
			UUID.fromString("0191c400-0003-7000-8000-000000000004"),
			first.nextCursor
		)

		val second = page(first.nextCursor)
		Assertions.assertEquals(listOf("post-3", "post-2"), second.rows)

		val last = page(second.nextCursor)
		Assertions.assertEquals(listOf("post-1"), last.rows)
		Assertions.assertNull(last.nextCursor)
	}

	@Test
	fun rowCursorExtractorPagesEntities() {
		val page = sql.createQuery(KtPost::class) {
			orderBy(table.id.asc())
			select(table)
		}.fetchUuidV7Slice(2) { it.id }

		Assertions.assertEquals(listOf("post-1", "post-2"), page.rows.map { it.title })
		Assertions.assertEquals(UUID.fromString("0191c400-0001-7000-8000-000000000002"), page.nextCursor)
		Assertions.assertFalse(page.isTail)
	}

	@Test
	fun keysetOverJoinFiltersAndPages() {
		fun page(cursor: UUID?) =
			sql.createQuery(KtPost::class) {
				where(table.author.name `like?` "author-")
				where(table.id `gt?` cursor)
				orderBy(table.id.asc())
				select(table.id, table.title)
			}.fetchUuidV7Slice(3)

		val first = page(null)
		Assertions.assertEquals(listOf("post-1", "post-2", "post-3"), first.rows)
		Assertions.assertTrue(first.hasNext)

		val second = page(first.nextCursor)
		Assertions.assertEquals(listOf("post-4"), second.rows)
		Assertions.assertNull(second.nextCursor)
	}

	@Test
	fun exactLimitFinalPageHasNoNext() {
		val page = titlesAfter(null, 5)
		Assertions.assertEquals(
			listOf("post-1", "post-2", "post-3", "post-4", "post-5"),
			page.rows
		)
		Assertions.assertNull(page.nextCursor)
		Assertions.assertFalse(page.hasNext)
	}

	@Test
	fun cursorPastLastRowYieldsEmptyPage() {
		val page = titlesAfter(
			UUID.fromString("0191c400-0004-7000-8000-000000000005"),
			5
		)
		Assertions.assertEquals(emptyList<String>(), page.rows)
		Assertions.assertNull(page.nextCursor)
		Assertions.assertFalse(page.hasNext)
	}

	@Test
	fun nonPositiveLimitIsRejected() {
		Assertions.assertThrows(IllegalArgumentException::class.java) {
			titlesAfter(null, 0)
		}
		Assertions.assertThrows(IllegalArgumentException::class.java) {
			titlesAfter(null, -1)
		}
		Assertions.assertThrows(IllegalArgumentException::class.java) {
			titlesAfter(null, Int.MAX_VALUE)
		}
	}

	@Test
	fun pageCountsAllRowsOnFirstPage() {
		val page = sql.createQuery(KtPost::class) {
			orderBy(table.id.asc())
			select(table.id, table.title)
		}.fetchUuidV7Page(2)

		Assertions.assertEquals(listOf("post-1", "post-2"), page.rows)
		Assertions.assertEquals(5L, page.totalRowCount)
		Assertions.assertTrue(page.hasNext)
	}

	@Test
	fun pageWithExplicitTotalPassesItThrough() {
		val page = sql.createQuery(KtPost::class) {
			where(table.id `gt?` UUID.fromString("0191c400-0001-7000-8000-000000000002"))
			orderBy(table.id.asc())
			select(table.id, table.title)
		}.fetchUuidV7Page(2, 42L)

		Assertions.assertEquals(listOf("post-3", "post-4"), page.rows)
		Assertions.assertEquals(42L, page.totalRowCount)
	}

	private fun postCte() =
		cteBaseTableSymbol {
			sql.createBaseQuery(KtPost::class) {
				where(table.authorId.isNotNull())
				selections.add(table.id).add(table.title)
			}
		}

	@Test
	fun keysetOverNullableExpressionNeedsAsNonNull() {
		fun page(cursor: UUID?) =
			sql.createQuery(KtPost::class) {
				where(table.`author?`.id.isNotNull())
				where(table.`author?`.id `gt?` cursor)
				orderBy(table.`author?`.id.asc())
				select(table.`author?`.id.asNonNull(), table.title)
			}.fetchUuidV7Slice(2)

		val first = page(null)
		Assertions.assertEquals(listOf("post-1", "post-2"), first.rows)
		Assertions.assertEquals(
			UUID.fromString("0191c300-0001-7000-8000-000000000002"),
			first.nextCursor
		)
		Assertions.assertTrue(first.hasNext)

		val last = page(first.nextCursor)
		Assertions.assertEquals(listOf("post-3", "post-4"), last.rows)
		Assertions.assertNull(last.nextCursor)
	}

	@Test
	fun keysetOverCteBaseTable() {
		// Each root query binds its own base-table symbol, so a fresh postCte()
		// is built per page() call rather than being reused across two queries.
		fun page(cursor: UUID?) =
			sql.createQuery(postCte()) {
				where(table._1 `gt?` cursor)
				orderBy(table._1.asc())
				select(table._1, table._2)
			}.fetchUuidV7Slice(3)

		val first = page(null)
		Assertions.assertEquals(listOf("post-1", "post-2", "post-3"), first.rows)
		Assertions.assertTrue(first.hasNext)

		val last = page(first.nextCursor)
		Assertions.assertEquals(listOf("post-4"), last.rows)
		Assertions.assertNull(last.nextCursor)
	}

	@Test
	fun pageOverCteBaseTableCounts() {
		val postCte = postCte()

		val page = sql.createQuery(postCte) {
			orderBy(table._1.asc())
			select(table._1, table._2)
		}.fetchUuidV7Page(2)

		Assertions.assertEquals(listOf("post-1", "post-2"), page.rows)
		Assertions.assertEquals(4L, page.totalRowCount)
		Assertions.assertTrue(page.hasNext)
	}

	@Test
	fun pageWithCursorCountsOnlyRemainingRows() {
		val page = sql.createQuery(KtPost::class) {
			where(table.id `gt?` UUID.fromString("0191c400-0001-7000-8000-000000000002"))
			orderBy(table.id.asc())
			select(table.id, table.title)
		}.fetchUuidV7Page(2)

		Assertions.assertEquals(listOf("post-3", "post-4"), page.rows)
		Assertions.assertEquals(3L, page.totalRowCount)
		Assertions.assertTrue(page.hasNext)
	}
}
