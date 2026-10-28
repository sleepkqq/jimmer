package example

import java.util.SortedMap
import org.babyfish.jimmer.sql.event.EntityEvent
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.filter.KCacheableFilter
import org.babyfish.jimmer.sql.kt.filter.KFilterArgs

class TenantFilter(private val tenant: String) : KCacheableFilter<TenantScoped> {
    override fun filter(args: KFilterArgs<TenantScoped>) {
        args.where(args.table.tenantId eq tenant)
    }

    override fun getParameters(): SortedMap<String, Any> = sortedMapOf("tenant" to tenant)

    // ponytail: invalidate on every relevant event; narrow when cache churn matters.
    override fun isAffectedBy(e: EntityEvent<*>): Boolean = true
}
