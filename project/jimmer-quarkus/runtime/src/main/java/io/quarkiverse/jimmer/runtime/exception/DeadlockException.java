package io.quarkiverse.jimmer.runtime.exception;

import java.sql.SQLException;

/**
 * Deadlock detected (SQLState {@code 40P01}, PostgreSQL). Transient — safe to retry the whole
 * transaction.
 */
public class DeadlockException extends JimmerDataAccessException implements TransientDataAccessException {

    public DeadlockException(SQLException cause) {
        this(cause, null);
    }

    DeadlockException(SQLException cause, Metadata metadata) {
        super("Deadlock detected: " + cause.getMessage(), cause, metadata);
    }
}
