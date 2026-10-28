package example;

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import org.babyfish.jimmer.sql.event.EntityEvent;
import org.babyfish.jimmer.sql.filter.CacheableFilter;
import org.babyfish.jimmer.sql.filter.FilterArgs;

public final class TenantFilter implements CacheableFilter<TenantScopedProps> {
    private final String tenant;

    public TenantFilter(String tenant) {
        this.tenant = Objects.requireNonNull(tenant);
    }

    @Override
    public void filter(FilterArgs<TenantScopedProps> args) {
        args.where(args.getTable().tenantId().eq(tenant));
    }

    @Override
    public SortedMap<String, Object> getParameters() {
        return new TreeMap<>(Map.of("tenant", tenant));
    }

    @Override
    public boolean isAffectedBy(EntityEvent<?> event) {
        // ponytail: invalidate on every relevant event; narrow when cache churn matters.
        return true;
    }
}
