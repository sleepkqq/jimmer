package io.quarkiverse.jimmer.runtime.exception;

import java.sql.SQLException;

/**
 * NOT NULL constraint violation (SQLState {@code 23502}).
 */
public class NotNullViolationException extends JimmerDataAccessException {

    public NotNullViolationException(SQLException cause) {
        this(cause, null);
    }

    NotNullViolationException(SQLException cause, Metadata metadata) {
        super("Not-null violation: " + cause.getMessage(), cause, metadata);
    }
}
