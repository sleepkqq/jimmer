package org.babyfish.jimmer.sql.model;

import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

@Entity
@DatabaseValidationIgnore
public interface NullableKeyUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id();

    @Key(group = "alias")
    @Nullable
    String alias();

    @Key
    String name();

    @Nullable
    String description();
}
