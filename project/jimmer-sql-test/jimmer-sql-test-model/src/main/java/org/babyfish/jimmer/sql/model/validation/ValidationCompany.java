package org.babyfish.jimmer.sql.model.validation;

import org.babyfish.jimmer.sql.DiscriminatorValue;
import org.babyfish.jimmer.sql.Entity;

@Entity(microServiceName = "database-validation")
@DiscriminatorValue("COMPANY")
public interface ValidationCompany extends ValidationClient {

    String taxCode();
}
