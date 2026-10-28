package example;

import org.babyfish.jimmer.sql.*;

@Entity
@DiscriminatorValue("TOOL")
public interface ToolAsset extends Asset {
    int inspectionInterval();
}
