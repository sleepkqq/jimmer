package example;

import java.util.List;
import org.babyfish.jimmer.sql.*;

@Entity
public interface BookStore {
    @Id long id();
    String name();

    @OneToMany(mappedBy = "store")
    List<Book> books();
}
