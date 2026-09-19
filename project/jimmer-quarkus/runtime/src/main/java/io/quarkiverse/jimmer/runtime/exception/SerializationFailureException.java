package io.quarkiverse.jimmer.runtime.exception;

import java.sql.SQLException;

/**
 * Serialization failure (SQLState {@code 40001}). Transient — safe to retry the whole transaction.
 */
public class SerializationFailureException extends JimmerDataAccessException implements TransientDataAccessException {

    public SerializationFailureException(SQLException cause) {
        this(cause, null);
    }

    SerializationFailureException(SQLException cause, Metadata metadata) {
        super("Serialization failure: " + cause.getMessage(), cause, metadata);
    }
}
