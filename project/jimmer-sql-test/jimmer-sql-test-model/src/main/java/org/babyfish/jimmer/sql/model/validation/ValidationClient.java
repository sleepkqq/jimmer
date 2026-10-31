package org.babyfish.jimmer.sql.model.validation;

import org.babyfish.jimmer.sql.Column;
import org.babyfish.jimmer.sql.Discriminator;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.Inheritance;
import org.babyfish.jimmer.sql.InheritanceType;

@Entity(microServiceName = "database-validation")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public interface ValidationClient extends ValidationVersionedBase {

    @Discriminator
    @Column(name = "CLIENT_TYPE")
    String type();

    String name();
}
