package testpkg;

import org.babyfish.jimmer.sql.ast.query.specification.JSpecificationExtension;
import org.babyfish.jimmer.sql.ast.query.specification.SpecificationArgs;
import org.babyfish.jimmer.sql.model.Book;
import org.babyfish.jimmer.sql.model.BookTable;

import java.math.BigDecimal;

public interface CheapBookSpecification extends JSpecificationExtension<Book, BookTable> {

    Boolean getCheap();

    void setCheap(Boolean cheap);

    @Override
    default void applyExtension(SpecificationArgs<Book, BookTable> args) {
        if (Boolean.TRUE.equals(getCheap())) {
            args.where(args.getTable().price().lt(new BigDecimal("50")));
        }
    }
}
