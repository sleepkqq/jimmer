package io.quarkiverse.jimmer.runtime.exception;

import java.sql.SQLException;

/**
 * Foreign key constraint violation (SQLState {@code 23503}).
 */
public class ForeignKeyViolationException extends JimmerDataAccessException {

    public ForeignKeyViolationException(SQLException cause) {
        this(cause, null);
    }

    ForeignKeyViolationException(SQLException cause, Metadata metadata) {
        super("Foreign key violation: " + cause.getMessage(), cause, metadata);
    }
}
