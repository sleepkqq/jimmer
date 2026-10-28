package example

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.OneToMany

@Entity
interface BookStore {
    @Id val id: Long
    val name: String

    @OneToMany(mappedBy = "store")
    val books: List<Book>
}
