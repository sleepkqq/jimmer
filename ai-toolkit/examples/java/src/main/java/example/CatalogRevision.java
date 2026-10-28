package example;

import org.babyfish.jimmer.sql.*;

@Entity
public interface CatalogRevision {
    @Id long id();
    @Version int version();
    String payload();
}
