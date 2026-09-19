package io.quarkiverse.jimmer.it.kt

import io.quarkiverse.jimmer.it.kt.entity.KtPost
import io.quarkiverse.jimmer.it.kt.entity.id
import io.quarkiverse.jimmer.it.kt.entity.title
import io.quarkiverse.jimmer.runtime.Jimmer
import io.quarkus.test.junit.QuarkusTest
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.toKSqlClient
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@QuarkusTest
class KtSmokeTestCase {

	private val sql = Jimmer.getDefaultJSqlClient().toKSqlClient()

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
}
