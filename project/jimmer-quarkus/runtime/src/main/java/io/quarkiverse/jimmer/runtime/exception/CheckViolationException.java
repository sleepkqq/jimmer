package io.quarkiverse.jimmer.runtime.exception;

import java.sql.SQLException;

/**
 * CHECK constraint violation (SQLState {@code 23514}).
 */
public class CheckViolationException extends JimmerDataAccessException {

    public CheckViolationException(SQLException cause) {
        this(cause, null);
    }

    CheckViolationException(SQLException cause, Metadata metadata) {
        super("Check violation: " + cause.getMessage(), cause, metadata);
    }
}
