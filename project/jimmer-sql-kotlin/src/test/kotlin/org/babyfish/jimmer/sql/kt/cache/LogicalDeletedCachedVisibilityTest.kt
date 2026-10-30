package org.babyfish.jimmer.sql.kt.cache

import org.babyfish.jimmer.meta.ImmutableType
import org.babyfish.jimmer.sql.cache.Cache
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.common.AbstractQueryTest
import org.babyfish.jimmer.sql.kt.common.AbstractTest
import org.babyfish.jimmer.sql.kt.common.createCache
import org.babyfish.jimmer.sql.kt.model.inheritance.Role
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import java.sql.Connection
import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The built-in logical-delete filter is judged on the cached row, so a warm Kotlin
 * entities read of a logically deleted type issues no SQL. The Kotlin client delegates
 * to the same Java `EntitiesImpl` branch. Fixture: `ROLE` holds one live row and one
 * logically deleted row.
 */
class LogicalDeletedCachedVisibilityTest : AbstractQueryTest() {

    private val client: KSqlClient = sqlClient {
        setConnectionManager(AutoCommitConnectionManager())
        setCacheFactory(
            object : KCacheFactory {
                override fun createObjectCache(type: ImmutableType): Cache<*, *>? =
                    createCache<Any, Any>(type)
            }
        )
    }

    @Test
    fun testWarmLiveRowUsesCacheOnly() {
        nontransactional { con -> client.entities.forConnection(con).findById(Role::class, LIVE_ROLE_ID) }
        clearExecutions()
        nontransactional { con ->
            val role = client.entities.forConnection(con).findById(Role::class, LIVE_ROLE_ID)
            assertEquals(LIVE_ROLE_ID, role?.id)
        }
        assertSqlCount(0)
    }

    @Test
    fun testWarmDeletedRowIsInvisibleWithoutSql() {
        nontransactional { con ->
            client.entities.forConnection(con).findByIds(Role::class, listOf(LIVE_ROLE_ID, DELETED_ROLE_ID))
        }
        clearExecutions()
        nontransactional { con ->
            assertNull(client.entities.forConnection(con).findById(Role::class, DELETED_ROLE_ID))
        }
        assertSqlCount(0)
        clearExecutions()
        nontransactional { con ->
            val roles = client.entities
                .forConnection(con)
                .findByIds(Role::class, listOf(LIVE_ROLE_ID, DELETED_ROLE_ID))
            assertEquals(listOf(LIVE_ROLE_ID), roles.map { it.id })
        }
        assertSqlCount(0)
    }

    private fun nontransactional(block: (Connection) -> Unit) {
        AbstractTest.jdbc(rollback = false) { con ->
            con.autoCommit = true
            block(con)
        }
    }

    private fun assertSqlCount(expected: Int) {
        assertEquals(expected, executions.size, executions.joinToString("\n") { it.sql })
    }

    /**
     * Positively reports the auto-commit connection as non-transactional, mirroring the
     * Java parity test; the shared `TestConnectionManager` is final and cannot be extended.
     */
    private class AutoCommitConnectionManager : ConnectionManager {

        @Suppress("UNCHECKED_CAST")
        override fun <R> execute(con: Connection?, block: Function<Connection, R>): R {
            var result: R? = null
            if (con == null) {
                AbstractTest.jdbc { result = block.apply(it) }
            } else {
                result = block.apply(con)
            }
            return result as R
        }

        override fun isTransactionKnownInactive(con: Connection): Boolean = con.autoCommit
    }

    private companion object {

        private const val LIVE_ROLE_ID = 100L

        private const val DELETED_ROLE_ID = 200L
    }
}
