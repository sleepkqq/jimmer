package example

import com.fasterxml.jackson.databind.ObjectMapper
import example.dto.BookPatch
import example.dto.BookSpec
import example.dto.BookView
import example.dto.StorePatch
import java.math.BigDecimal
import java.sql.DriverManager
import org.babyfish.jimmer.Page
import org.babyfish.jimmer.jackson.ImmutableModule
import org.babyfish.jimmer.kt.isLoaded
import org.babyfish.jimmer.kt.new
import org.babyfish.jimmer.sql.ast.mutation.AssociatedSaveMode
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.dialect.H2Dialect
import org.babyfish.jimmer.sql.exception.SaveException
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.query.baseTableSymbol
import org.babyfish.jimmer.sql.kt.ast.table.*
import org.babyfish.jimmer.sql.kt.fetcher.newFetcher
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager

fun search(sqlClient: KSqlClient, name: String?, page: Int, size: Int): Page<BookView> =
    sqlClient.createQuery(Book::class) {
        where(table.name `like?` name)
        orderBy(table.name.asc(), table.id.asc())
        select(table.fetch(BookView::class))
    }.fetchPage(page, size)

fun increasePrice(sqlClient: KSqlClient, id: Long, expectedVersion: Int, delta: BigDecimal): Int =
    sqlClient.createUpdate(Book::class) {
        set(table.price, table.price + delta)
        set(table.version, table.version + 1)
        where(table.id eq id, table.version eq expectedVersion)
    }.execute()

fun main() {
    val mapper = ObjectMapper().registerModule(ImmutableModule())
    DriverManager.getConnection("jdbc:h2:mem:kotlin").use { connection ->
        connection.createStatement().use { statement ->
            val schema = checkNotNull(Book::class.java.getResourceAsStream("/schema.sql"))
                .bufferedReader().use { it.readText() }
            schema.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
        }
        connection.autoCommit = false
        val manager = ConnectionManager.singleConnectionManager(connection)
        val sql = newKSqlClient {
            setDialect(H2Dialect())
            setConnectionManager(manager)
        }
        val book = Book {
            name = "Jimmer Guide"
            price = BigDecimal("29.90")
            tenantId = "a"
            store = BookStore { id = 10L }
        }
        val created = sql.saveCommand(book) {
            setMode(SaveMode.INSERT_ONLY)
        }.execute(BookView::class).modifiedView
        val id = created.id
        check(created.store?.name == "Library") { "save View association" }
        check(search(sql, "Guide", 0, 10).rows.size == 1) { "View page" }
        check(search(sql, null, 0, 10).totalRowCount == 1L) { "optional predicate" }

        val fetcher = newFetcher(Book::class).by {
            name()
            price()
            store { name() }
            displayName()
        }
        val fetched = sql.findByIds(fetcher, listOf(id)).single()
        check(fetched.displayName == "Book: Jimmer Guide") { "formula" }
        check(!isLoaded(fetched, Book::tenantId)) { "explicit shape" }

        val omitted = mapper.readValue("""{"id":$id}""", BookPatch::class.java)
        check(!isLoaded(omitted.toEntity(), Book::store)) { "omitted store" }
        sql.save(omitted) { setMode(SaveMode.UPDATE_ONLY) }
        val storeIdFetcher = newFetcher(Book::class).by { storeId() }
        check(sql.findById(storeIdFetcher, id)?.storeId == 10L) { "omission preserves FK" }
        val cleared = mapper.readValue("""{"id":$id,"store":null}""", BookPatch::class.java)
        check(isLoaded(cleared.toEntity(), Book::store)) { "explicit null loaded" }
        sql.save(cleared) { setMode(SaveMode.UPDATE_ONLY) }
        check(sql.findById(storeIdFetcher, id)?.storeId == null) { "null clears FK" }
        val value = mapper.readValue("""{"id":$id,"store":{"id":10}}""", BookPatch::class.java)
        sql.save(value) { setMode(SaveMode.UPDATE_ONLY) }

        val storeOmitted = mapper.readValue("""{"id":10}""", StorePatch::class.java)
        val empty = mapper.readValue("""{"id":10,"books":[]}""", StorePatch::class.java)
        check(!isLoaded(storeOmitted.toEntity(), BookStore::books)) { "omitted collection" }
        check(isLoaded(empty.toEntity(), BookStore::books)) { "empty collection loaded" }
        sql.save(storeOmitted) { setMode(SaveMode.UPDATE_ONLY) }
        for (mode in listOf(AssociatedSaveMode.MERGE, AssociatedSaveMode.REPLACE)) {
            sql.save(empty) {
                setMode(SaveMode.UPDATE_ONLY)
                setAssociatedModeAll(mode)
            }
            val storeId = sql.findById(storeIdFetcher, id)?.storeId
            check(if (mode == AssociatedSaveMode.MERGE) storeId != null else storeId == null) {
                "association mode $mode"
            }
        }

        val version = sql.findOneById(newFetcher(Book::class).by { version() }, id).version
        check(increasePrice(sql, id, version, BigDecimal.ONE) == 1) { "conditional update" }
        check(increasePrice(sql, id, version, BigDecimal.ONE) == 0) { "stale update rejected" }
        check(sql.findOneById(newFetcher(Book::class).by { price() }, id).price.compareTo(BigDecimal("30.90")) == 0)
        val stale = new(Book::class).by { this.id = id; this.version = version; name = "Stale" }
        try {
            sql.save(stale) { setMode(SaveMode.UPDATE_ONLY) }
            error("stale save succeeded")
        } catch (expected: SaveException.OptimisticLockError) {
            // Graph saves conflict; the conditional bulk update above reports zero.
        }

        val source = baseTableSymbol {
            sql.createBaseQuery(Book::class) { selections.add(table.id).add(table.name) }
        }
        check(sql.createQuery(source) {
            where(table._2 like "Guide")
            select(table._1, table._2)
        }.execute().size == 1) { "base query" }
        val spec = BookSpec(name = "guide")
        check(sql.createQuery(Book::class) { where(spec); select(table.id) }.exists()) { "Specification" }
        check(sql.createQuery(Book::class) { orderBy(table.id); select(table.id) }.fetchSlice(1, 0).isTail)
        var visited = 0
        sql.createQuery(Book::class) { select(table.fetch(fetcher)) }
            .forEach(batchSize = 1) { visited++ }
        check(visited == 1) { "batched traversal" }

        sql.save(value) { setMode(SaveMode.UPDATE_ONLY) }
        check(sql.createQuery(BookStore::class) {
            where(table.books { name eq "Jimmer Guide" })
            select(table)
        }.execute().size == 1) { "collection membership" }
        for (tenant in listOf("a", "b")) {
            val scoped = newKSqlClient {
                setDialect(H2Dialect())
                setConnectionManager(manager)
                addFilters(TenantFilter(tenant))
            }
            check(search(scoped, null, 0, 10).rows.size == if (tenant == "a") 1 else 0) { "tenant isolation $tenant" }
        }
        connection.rollback()
        check(search(sql, null, 0, 10).rows.isEmpty()) { "rollback" }
    }
    println("Kotlin 0.9.111: KSP/DTO, queries, PATCH, association modes, locking, filters and rollback passed")
}
