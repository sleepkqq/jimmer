package example;

import java.util.List;
import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

@Entity
public interface Warehouse {
    @Id long id();
    String name();
    boolean active();
    @Nullable String note();
    @OneToMany(mappedBy = "warehouse") List<Bin> bins();
}
