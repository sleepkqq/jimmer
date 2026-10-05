package io.quarkiverse.jimmer.it.kt

import io.quarkiverse.jimmer.it.kt.entity.KtPost
import io.quarkiverse.jimmer.it.kt.entity.`by`
import io.quarkiverse.jimmer.it.kt.entity.id
import io.quarkiverse.jimmer.it.kt.entity.title
import io.quarkiverse.jimmer.runtime.Jimmer
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import javax.sql.DataSource
import org.babyfish.jimmer.meta.ImmutableType
import org.babyfish.jimmer.sql.cache.Cache
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.kt.toKSqlClient
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@QuarkusTest
class KtSmokeTestCase {

	private val sql = Jimmer.getDefaultJSqlClient().toKSqlClient()

	@Inject
	lateinit var dataSource: DataSource

	@Test
	fun kotlinDslReachesKtPost() {
		val titles = sql.createQuery(KtPost::class) {
			orderBy(table.id.asc())
			select(table.title)
		}.execute()
		Assertions.assertEquals(
			listOf("post-1", "post-2", "post-3", "post-4", "post-5"),
			titles
		)
	}

	@Test
	fun delegatedWarmMixedTupleIsServedFromTheDeclarativeObjectCache() {
		// The Quarkus default client is a JSqlClient delegate wrapped by a KSqlClient, so
		// the object-cache seed identity only matches when the hydration uses the same
		// delegate. The application CacheConfig wires a real object cache for the injected
		// client; a raw JDBC update cannot run the TRANSACTION_ONLY invalidations, so the
		// warm body must still come from the cache.
		val javaClient = Jimmer.getDefaultJSqlClient()
		val objectCache = javaClient.caches.getObjectCache<Long, KtPost>(ImmutableType.get(KtPost::class.java))
		Assertions.assertNotNull(
			objectCache,
			"the declarative application CacheConfig must wire an object cache for the injected client"
		)
		val id = sql.createQuery(KtPost::class) {
			orderBy(table.id.asc())
			select(table.id)
		}.limit(1).execute().first()
		val original = sql.entities.findById(KtPost::class, id)!!.title
		Assertions.assertEquals("post-1", original)
		val stale = "stale-sentinel"
		try {
			dataSource.connection.use { con ->
				con.autoCommit = true
				con.prepareStatement("update kt_post set title = ? where id = ?").use { ps ->
					ps.setString(1, stale)
					ps.setObject(2, id)
					ps.executeUpdate()
				}
			}
			val rows = sql.createQuery(KtPost::class) {
				where(table.id eq id)
				select(
					table.fetch(newFetcher(KtPost::class).by { title() }),
					table.title
				)
			}.useObjectCache().execute()
			Assertions.assertEquals(1, rows.size)
			Assertions.assertEquals(id, rows[0]._1.id)
			// A delegated client whose hint silently fell back would read the sentinel
			// from the database instead of the warm cached body.
			Assertions.assertEquals(original, rows[0]._1.title)
			Assertions.assertEquals(stale, rows[0]._2)
		} finally {
			dataSource.connection.use { con ->
				con.autoCommit = true
				con.prepareStatement("update kt_post set title = ? where id = ?").use { ps ->
					ps.setString(1, original)
					ps.setObject(2, id)
					ps.executeUpdate()
				}
			}
			// A cache miss during the asserted query can store the sentinel body; evict the
			// exact id from both native chain tiers so later tests never observe the stale row.
			// Runs even when the query assertion above failed.
			@Suppress("UNCHECKED_CAST")
			(objectCache as Cache<Any?, Any?>).delete(id, "kt-smoke-cleanup")
		}
	}
}
