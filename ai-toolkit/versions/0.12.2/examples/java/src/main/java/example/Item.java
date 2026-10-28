package example;

import org.babyfish.jimmer.sql.*;

@Entity
public interface Item {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key
    String sku();

    String name();
}
