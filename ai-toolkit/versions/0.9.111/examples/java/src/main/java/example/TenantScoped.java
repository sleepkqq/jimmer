package example;

import org.babyfish.jimmer.sql.MappedSuperclass;

@MappedSuperclass
public interface TenantScoped {
    String tenantId();
}
