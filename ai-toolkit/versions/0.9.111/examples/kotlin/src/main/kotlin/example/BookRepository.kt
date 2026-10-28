package example

import org.babyfish.jimmer.spring.repository.KRepository

interface BookRepository : KRepository<Book, Long> {
    fun findByName(name: String): List<Book>
}
