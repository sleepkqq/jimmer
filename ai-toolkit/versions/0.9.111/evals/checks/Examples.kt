package example

import java.math.BigDecimal
import java.sql.DriverManager
import org.babyfish.jimmer.sql.dialect.H2Dialect
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager

fun main() {
    DriverManager.getConnection("jdbc:h2:mem:generated-kotlin").use { connection ->
        connection.createStatement().use { statement ->
            val schema = checkNotNull(Book::class.java.getResourceAsStream("/schema.sql"))
                .bufferedReader().use { it.readText() }
            schema.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
            statement.execute("insert into book(id, name, price, version, tenant_id) values (1, 'Canary Guide', 25, 4, 'test')")
        }
        connection.autoCommit = false
        val sql = newKSqlClient {
            setDialect(H2Dialect())
            setConnectionManager(ConnectionManager.singleConnectionManager(connection))
        }
        check(CanaryKotlin.increasePrice(sql, 1L, 4, BigDecimal("5.00"))) { "matching update failed" }
        check(!CanaryKotlin.increasePrice(sql, 1L, 4, BigDecimal("5.00"))) { "stale version changed row" }
        check(!CanaryKotlin.increasePrice(sql, 999L, 4, BigDecimal("5.00"))) { "missing row accepted" }
        val stored = sql.findOneById(newFetcher(Book::class).by { price(); version() }, 1L)
        check(stored.price.compareTo(BigDecimal("30.00")) == 0 && stored.version == 5) { "non-atomic value/version update" }
        val page = CanaryKotlin.search(sql, null, 0, 10)
        check(page.totalRowCount == 1L && page.rows.size == 1) { "null predicate or missing-row insert" }
        check(CanaryKotlin.search(sql, "Guide", 0, 10).rows.size == 1) { "name filter" }
        check(CanaryKotlin.search(sql, "missing", 0, 10).rows.isEmpty()) { "missing filter" }
        connection.rollback()
    }
    println("MODEL-GENERATED KOTLIN: conditional atomic update, stale/missing no-op and filtered pagination passed")
}
