package org.babyfish.jimmer.sql.model.time;

import org.babyfish.jimmer.sql.DatabaseValidationIgnore;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.Id;
import org.babyfish.jimmer.sql.Version;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;

@DatabaseValidationIgnore
@Entity
public interface TemporalRecord {

    @Id
    long id();

    @Version
    int version();

    LocalDateTime createdTime();

    @Nullable
    Instant modifiedTime();

    String description();
}
