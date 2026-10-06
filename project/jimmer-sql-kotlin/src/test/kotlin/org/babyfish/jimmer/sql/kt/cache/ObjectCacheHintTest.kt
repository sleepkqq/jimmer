package org.babyfish.jimmer.sql.kt.cache

import org.babyfish.jimmer.meta.ImmutableType
import org.babyfish.jimmer.sql.cache.Cache
import org.babyfish.jimmer.sql.di.AbstractJSqlClientDelegate
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.value
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.babyfish.jimmer.sql.kt.common.AbstractQueryTest
import org.babyfish.jimmer.sql.kt.common.AbstractTest
import org.babyfish.jimmer.sql.kt.common.createCache
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.kt.filter.KFilter
import org.babyfish.jimmer.sql.kt.filter.KFilterArgs
import org.babyfish.jimmer.sql.kt.model.classic.book.Book
import org.babyfish.jimmer.sql.kt.model.classic.book.by
import org.babyfish.jimmer.sql.kt.model.classic.book.dto.BookView
import org.babyfish.jimmer.sql.kt.model.classic.book.id
import org.babyfish.jimmer.sql.kt.model.filter.File
import org.babyfish.jimmer.sql.kt.model.filter.id
import org.babyfish.jimmer.sql.kt.model.inheritance.single.employee.Employee
import org.babyfish.jimmer.sql.kt.model.inheritance.single.employee.`by`
import org.babyfish.jimmer.sql.kt.model.inheritance.single.employee.id
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.KClient
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.KOrganization
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.KPerson
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.`by`
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.firstName
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.id
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.lastName
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.name
import org.babyfish.jimmer.sql.kt.model.inheritance.singletable.taxCode
import org.babyfish.jimmer.sql.kt.toKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor
import java.sql.Connection
import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The object-cache query hint is a best-effort optimization: the SQL for membership,
 * ordering, pagination, count and existence is always executed, and the hint
 * transparently falls back to ordinary SQL. These tests exercise the Kotlin API
 * forwarding and the observable hint behavior with a warm object cache.
 *
 * The optimization only runs when the connection manager can positively prove that
 * the supplied connection is not inside a transaction. For a plain JDBC H2
 * connection that proof is exactly `Connection.autoCommit == true`; this standalone
 * fixture has no ambient framework transaction. The positive-path tests therefore
 * use a non-transactional read-only connection, while the decline test uses the
 * default rollback fixture whose connection really is a local JDBC transaction.
 * No filter, readiness check or repository is faked.
 */
class ObjectCacheHintTest : AbstractQueryTest() {

    private val _sqlClient: KSqlClient =
        sqlClient {
            setConnectionManager(AutoCommitConnectionManager())
            setCacheFactory(
                object : KCacheFactory {
                    override fun createObjectCache(type: ImmutableType): Cache<*, *> =
                        createCache<Any, Any>(type)
                }
            )
        }

    @Test
    fun testDefaultQueryReadsWideColumns() {
        jdbc { con ->
            val rows = _sqlClient.createQuery(Book::class) {
                where(table.id eq 3L)
                select(table)
            }.execute(con)
            assertEquals(1, rows.size)
            assertEquals("Learning GraphQL", rows[0].name)
        }
        assertTrue(
            executions.single().sql.contains("PRICE"),
            executions.single().sql
        )
    }

    @Test
    fun testWarmObjectCacheQueryProjectsIdOnly() {
        nontransactional { con ->
            _sqlClient.entities.forConnection(con).findById(Book::class, 3L)
        }
        clearExecutions()
        nontransactional { con ->
            val rows = _sqlClient.createQuery(Book::class) {
                where(table.id eq 3L)
                select(table)
            }.useObjectCache().execute(con)
            assertEquals(1, rows.size)
            assertEquals(3L, rows[0].id)
            assertEquals("Learning GraphQL", rows[0].name)
        }
        // A warm cache must short-circuit the entity columns. If the hint silently
        // fell back to ordinary SQL the wide PRICE column would reappear, so this
        // assertion fails on a fallback instead of passing trivially.
        assertTrue(executions.isNotEmpty())
        executions.forEach { assertFalse(it.sql.contains("PRICE"), it.sql) }
    }

    @Test
    fun testExplicitEnableOverloadIsAccepted() {
        nontransactional { con ->
            val rows = _sqlClient.createQuery(Book::class) {
                where(table.id eq 3L)
                select(table)
            }.useObjectCache(true).execute(con)
            assertEquals(1, rows.size)
            assertEquals(3L, rows[0].id)
        }
    }

    @Test
    fun testContentFetcherServesOnlyApprovedFieldFromCache() {
        nontransactional { con ->
            _sqlClient.entities.forConnection(con).findById(Book::class, 3L)
        }
        clearExecutions()
        nontransactional { con ->
            val rows = _sqlClient.createQuery(Book::class) {
                where(table.id eq 3L)
                select(table)
            }.useObjectCache(newFetcher(Book::class).by { name() }).execute(con)
            assertEquals(1, rows.size)
            assertEquals(3L, rows[0].id)
            assertEquals("Learning GraphQL", rows[0].name)
        }
        val sql = executions.single().sql
        assertFalse(sql.contains("NAME"), sql)
        assertTrue(sql.contains("PRICE"), sql)
    }

    @Test
    fun testHintCanBeDisabled() {
        jdbc { con ->
            _sqlClient.createQuery(Book::class) {
                where(table.id eq 3L)
                select(table)
            }.useObjectCache().useObjectCache(false).execute(con)
        }
        assertTrue(executions.single().sql.contains("PRICE"), executions.single().sql)
    }

    @Test
    fun testLimitForwardingOnHintedQuery() {
        nontransactional { con ->
            val rows = _sqlClient.createQuery(Book::class) {
                orderBy(table.id.asc())
                select(table)
            }.useObjectCache().limit(2).execute(con)
            assertEquals(listOf(1L, 2L), rows.map { it.id })
        }
    }

    @Test
    fun testDtoResultsUnaffectedByHint() {
        val defaultRows = mutableListOf<BookView>()
        jdbc { con ->
            defaultRows += _sqlClient.createQuery(Book::class) {
                where(table.id eq 12L)
                select(table.fetch(BookView::class))
            }.execute(con)
        }
        nontransactional { con ->
            _sqlClient.entities.forConnection(con).findById(Book::class, 12L)
        }
        clearExecutions()
        val hintedRows = mutableListOf<BookView>()
        nontransactional { con ->
            hintedRows += _sqlClient.createQuery(Book::class) {
                where(table.id eq 12L)
                select(table.fetch(BookView::class))
            }.useObjectCache().execute(con)
        }
        assertEquals(defaultRows.toString(), hintedRows.toString())
        // BookView selects the root scalars NAME and EDITION. PRICE is absent from
        // BookView entirely, so asserting its absence would prove nothing; EDITION
        // is the real discriminator: it exists only on the BOOK table and is not
        // referenced by any related BookStore/Author load. With a warm entity cache
        // the hinted DTO query must be served from the id-only skeleton (root ID
        // only), so a fallback to ordinary DTO SQL would emit EDITION and fail here.
        assertTrue(executions.isNotEmpty())
        executions.forEach { assertFalse(it.sql.contains("EDITION"), it.sql) }
    }

    @Test
    fun testHintDeclinedInsideLocalJdbcTransaction() {
        // Warm the object cache through a proven non-transactional connection.
        nontransactional { con ->
            _sqlClient.entities.forConnection(con).findById(Book::class, 3L)
        }
        clearExecutions()
        // The default rollback fixture runs with autoCommit=false, a real local
        // JDBC transaction, so the manager cannot prove it is inactive.
        jdbc { con ->
            val rows = _sqlClient.createQuery(Book::class) {
                where(table.id eq 3L)
                select(table)
            }.useObjectCache().execute(con)
            assertEquals(1, rows.size)
            assertEquals("Learning GraphQL", rows[0].name)
        }
        // The decline is observable: ordinary SQL re-reads the entity columns even
        // though the object cache is warm.
        assertTrue(executions.single().sql.contains("PRICE"), executions.single().sql)
    }

    @Test
    fun testContentFetcherHintDeclinedInsideLocalJdbcTransaction() {
        nontransactional { con ->
            _sqlClient.entities.forConnection(con).findById(Book::class, 3L)
        }
        clearExecutions()
        jdbc { con ->
            val rows = _sqlClient.createQuery(Book::class) {
                where(table.id eq 3L)
                select(table)
            }.useObjectCache(newFetcher(Book::class).by { name() }).execute(con)
            assertEquals(1, rows.size)
            assertEquals("Learning GraphQL", rows[0].name)
        }
        assertTrue(executions.single().sql.contains("PRICE"), executions.single().sql)
    }

    @Test
    fun testFilteredWarmQueryStatementParity() {
        // A real root filter must not be applied a second time by the per-id
        // visibility check of the object-cache read. The filtered skeleton already
        // proves membership, so the hinted statement count must equal the unhinted
        // count instead of adding a redundant visibility query.
        val visibleId = longArrayOf(1L)
        val client = sqlClient {
            setConnectionManager(AutoCommitConnectionManager())
            addFilters(
                object : KFilter<File> {
                    override fun filter(args: KFilterArgs<File>) {
                        args.where(args.table.id eq visibleId[0])
                    }
                }
            )
            setCacheFactory(
                object : KCacheFactory {
                    override fun createObjectCache(type: ImmutableType): Cache<*, *> =
                        createCache<Any, Any>(type)
                }
            )
        }
        // Warm the shared cache through the ordinary entities path while visible.
        nontransactional { con ->
            client.entities.forConnection(con).findById(File::class, 1L)
        }
        // Unhinted baseline for the same filtered query.
        clearExecutions()
        nontransactional { con ->
            val rows = client.createQuery(File::class) {
                where(table.id eq 1L)
                select(table)
            }.execute(con)
            assertEquals(1, rows.size)
        }
        val ordinaryCount = executions.size
        clearExecutions()
        nontransactional { con ->
            val rows = client.createQuery(File::class) {
                where(table.id eq 1L)
                select(table)
            }.useObjectCache().execute(con)
            assertEquals(1, rows.size)
            assertEquals(1L, rows[0].id)
        }
        assertEquals(ordinaryCount, executions.size)
        // A fallback to ordinary SQL would re-read the NAME column.
        executions.forEach { assertFalse(it.sql.contains("NAME"), it.sql) }
    }

    @Test
    fun testDelegatedClientWarmMixedTupleUsesSkeleton() {
        // Real Quarkus/Kotlin shape: a KSqlClient retains a JSqlClient delegate whose
        // getEntities() returns the underlying EntitiesImpl. The seed is minted for the
        // delegate, so the hydration must rebind those entities to the same delegate, or
        // every warm mixed-tuple query silently declines back to the full projection.
        val visibleId = longArrayOf(6000L)
        val underlying = sqlClient {
            setConnectionManager(AutoCommitConnectionManager())
            addFilters(
                object : KFilter<Employee> {
                    override fun filter(args: KFilterArgs<Employee>) {
                        args.where(args.table.id eq visibleId[0])
                    }
                }
            )
            setCacheFactory(
                object : KCacheFactory {
                    override fun createObjectCache(type: ImmutableType): Cache<*, *> =
                        createCache<Any, Any>(type)
                }
            )
        }
        val delegated: KSqlClient = object : AbstractJSqlClientDelegate() {
            override fun sqlClient(): JSqlClientImplementor = underlying.javaClient
        }.toKSqlClient()
        // Warm the shared cache through the delegated client while the row is visible.
        nontransactional { con ->
            delegated.entities.forConnection(con).findById(Employee::class, 6000L)
        }
        clearExecutions()
        nontransactional { con ->
            val rows = delegated.createQuery(Employee::class) {
                where(table.id eq 6000L)
                select(
                    table.fetch(newFetcher(Employee::class).by { fullName() }),
                    value(1L)
                )
            }.useObjectCache().execute(con)
            assertEquals(1, rows.size)
            assertEquals(6000L, rows[0]._1.id)
        }
        // One id-only skeleton, and the cached entity body (FULL_NAME) is not re-read.
        // A decline fallback would emit FULL_NAME and run a second statement.
        assertEquals(1, executions.size, executions.joinToString("\n") { it.sql })
        assertFalse(executions[0].sql.contains("FULL_NAME"), executions[0].sql)
    }

    @Test
    fun testBranchContentFetcherServesApprovedSubtypeLeafFromCache() {
        val visibleIds = listOf(100L, 101L)
        val underlying = sqlClient {
            setConnectionManager(AutoCommitConnectionManager())
            setCacheFactory(
                object : KCacheFactory {
                    override fun createObjectCache(type: ImmutableType): Cache<*, *> =
                        createCache<Any, Any>(type)
                }
            )
        }
        val delegated: KSqlClient = object : AbstractJSqlClientDelegate() {
            override fun sqlClient(): JSqlClientImplementor = underlying.javaClient
        }.toKSqlClient()
        nontransactional { con ->
            delegated.entities.forConnection(con).findById(KClient::class, 100L)
            delegated.entities.forConnection(con).findById(KClient::class, 101L)
        }
        try {
            rawUpdate(
                "update CLIENT set NAME = ?, TAX_CODE = ? where ID = ?",
                "STALE-100", "STALE-TAX-100", 100L
            )
            rawUpdate(
                "update CLIENT set NAME = ?, FIRST_NAME = ?, LAST_NAME = ? where ID = ?",
                "STALE-101", "STALE-FIRST", "STALE-LAST", 101L
            )
            clearExecutions()
            var rows: List<KClient> = emptyList()
            nontransactional { con ->
                rows = delegated.createQuery(KClient::class) {
                    where(table.id valueIn visibleIds)
                    orderBy(table.id)
                    select(
                        table.fetchBy {
                            name()
                            forType(KOrganization::class) { taxCode() }
                            forType(KPerson::class) { firstName(); lastName() }
                        }
                    )
                }.useObjectCache(
                    newFetcher(KClient::class).by {
                        name()
                        forType(KOrganization::class) { taxCode() }
                    }
                ).execute(con)
            }
            assertEquals(2, rows.size)
            val organization = assertIs<KOrganization>(rows[0])
            assertEquals("Acme", organization.name)
            assertEquals("ACME-001", organization.taxCode)
            val person = assertIs<KPerson>(rows[1])
            assertEquals("Bob", person.name)
            assertEquals("STALE-FIRST", person.firstName)
            assertEquals("STALE-LAST", person.lastName)
        } finally {
            rawUpdate(
                "update CLIENT set NAME = 'Acme', TAX_CODE = 'ACME-001', FIRST_NAME = null, LAST_NAME = null where ID = ?",
                100L
            )
            rawUpdate(
                "update CLIENT set NAME = 'Bob', FIRST_NAME = 'Bob', LAST_NAME = 'Brown' where ID = ?",
                101L
            )
        }
    }

    /**
     * Runs the block on a fresh, unmodified test connection with auto-commit enabled.
     * These bodies only read, so nothing is committed; the connection is closed by
     * the fixture immediately afterwards.
     */
    private fun nontransactional(block: (Connection) -> Unit) {
        AbstractTest.jdbc(rollback = false) { con ->
            con.autoCommit = true
            block(con)
        }
    }

    /** Commits a raw JDBC mutation the object cache does not observe. */
    private fun rawUpdate(sql: String, vararg args: Any) {
        AbstractTest.jdbc(rollback = false) { con ->
            con.prepareStatement(sql).use { st ->
                for (i in args.indices) {
                    st.setObject(i + 1, args[i])
                }
                st.executeUpdate()
            }
        }
    }

    /**
     * Positively proves a non-transactional connection from the actual JDBC state
     * only. This standalone H2 fixture has no ambient framework transaction, so
     * `autoCommit == true` is a genuine proof, and `autoCommit == false` (the
     * rollback fixture) is correctly declined instead of being assumed safe.
     */
    private class AutoCommitConnectionManager : ConnectionManager {

        @Suppress("UNCHECKED_CAST")
        override fun <R> execute(con: Connection?, block: Function<Connection, R>): R {
            val ref = arrayOfNulls<Any>(1) as Array<R>
            if (con == null) {
                AbstractTest.jdbc { ref[0] = block.apply(it) }
            } else {
                ref[0] = block.apply(con)
            }
            return ref[0]
        }

        override fun isTransactionKnownInactive(con: Connection): Boolean = con.autoCommit
    }
}
