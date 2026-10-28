package example;

import java.math.BigDecimal;
import org.babyfish.jimmer.Formula;
import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

@Entity
@KeyUniqueConstraint
public interface Book extends TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key String name();
    BigDecimal price();
    @Version int version();

    @Nullable
    @ManyToOne
    @OnDissociate(DissociateAction.SET_NULL)
    BookStore store();

    @IdView("store")
    @Nullable Long storeId();

    @Formula(dependencies = {"name"})
    default String displayName() {
        return "Book: " + name();
    }
}
