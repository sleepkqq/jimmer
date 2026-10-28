package example

import org.babyfish.jimmer.sql.MappedSuperclass

@MappedSuperclass
interface TenantScoped {
    val tenantId: String
}
