package example;

import org.babyfish.jimmer.sql.*;

@Entity
@DiscriminatorValue("BOOK")
public interface BookAsset extends Asset {
    String isbn();
}
