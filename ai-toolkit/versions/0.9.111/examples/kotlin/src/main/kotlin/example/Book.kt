package example

import java.math.BigDecimal
import org.babyfish.jimmer.Formula
import org.babyfish.jimmer.sql.*

@Entity
@KeyUniqueConstraint
interface Book : TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @Key val name: String
    val price: BigDecimal
    @Version val version: Int

    @ManyToOne
    @OnDissociate(DissociateAction.SET_NULL)
    val store: BookStore?

    @IdView("store")
    val storeId: Long?

    @Formula(dependencies = ["name"])
    val displayName: String
        get() = "Book: $name"
}
