package org.babyfish.jimmer.sql.model.validation;

import org.babyfish.jimmer.sql.Id;
import org.babyfish.jimmer.sql.MappedSuperclass;
import org.babyfish.jimmer.sql.Version;

@MappedSuperclass(microServiceName = "database-validation")
public interface ValidationVersionedBase {

    @Id
    long id();

    @Version
    int version();
}
