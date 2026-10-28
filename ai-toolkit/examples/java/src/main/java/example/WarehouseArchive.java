package example;

import org.babyfish.jimmer.sql.*;

@Entity
public interface WarehouseArchive {
    @Id long id();
    String name();
}
