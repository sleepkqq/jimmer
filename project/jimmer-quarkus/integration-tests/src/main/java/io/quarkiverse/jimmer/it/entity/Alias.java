package io.quarkiverse.jimmer.it.entity;

import org.babyfish.jimmer.sql.Discriminator;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.EntityInstantiability;
import org.babyfish.jimmer.sql.GeneratedValue;
import org.babyfish.jimmer.sql.GenerationType;
import org.babyfish.jimmer.sql.Id;
import org.babyfish.jimmer.sql.Inheritance;
import org.babyfish.jimmer.sql.InheritanceType;
import org.babyfish.jimmer.sql.Key;

@Entity(instantiability = EntityInstantiability.ABSTRACT)
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public interface Alias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key
    String value();

    @Discriminator
    String type();
}
