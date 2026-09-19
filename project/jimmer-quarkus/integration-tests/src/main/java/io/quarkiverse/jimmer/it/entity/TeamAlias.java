package io.quarkiverse.jimmer.it.entity;

import org.babyfish.jimmer.sql.DiscriminatorValue;
import org.babyfish.jimmer.sql.Entity;

@Entity
@DiscriminatorValue("TEAM")
public interface TeamAlias extends Alias {
}
