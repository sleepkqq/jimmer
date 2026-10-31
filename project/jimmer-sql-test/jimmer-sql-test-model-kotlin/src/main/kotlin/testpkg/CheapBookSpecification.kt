package testpkg

import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.babyfish.jimmer.sql.kt.ast.query.specification.KSpecificationArgs
import org.babyfish.jimmer.sql.kt.ast.query.specification.KSpecificationExtension
import org.babyfish.jimmer.sql.kt.model.classic.book.Book
import org.babyfish.jimmer.sql.kt.model.classic.book.price
import java.math.BigDecimal

interface CheapBookSpecification : KSpecificationExtension<Book> {

    val cheap: Boolean?

    override fun applyExtension(args: KSpecificationArgs<Book>) {
        if (cheap == true) {
            args.where(args.table.price lt BigDecimal(50))
        }
    }
}
