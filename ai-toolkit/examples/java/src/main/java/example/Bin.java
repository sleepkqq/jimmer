package example;

import org.babyfish.jimmer.sql.*;

@Entity
public interface Bin {
    @Id long id();
    @ManyToOne
    @OnDissociate(DissociateAction.DELETE)
    Warehouse warehouse();
}
