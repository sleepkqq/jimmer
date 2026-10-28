package example;

import org.babyfish.jimmer.sql.*;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public interface Asset {
    @Id long id();
    @Discriminator String kind();
    String name();
}
