package io.quarkiverse.jimmer.runtime.exception;

import java.sql.SQLException;

/**
 * Generic integrity constraint violation (SQLState class {@code 23}) that does not match a more
 * specific subtype. Databases that collapse constraint errors into a single state — e.g. MySQL
 * reports {@code 23000} for unique, foreign key and not-null alike — land here; use
 * {@link #getSqlException()} and the vendor error code for finer discrimination.
 */
public class DataIntegrityViolationException extends JimmerDataAccessException {

    public DataIntegrityViolationException(SQLException cause) {
        this(cause, null);
    }

    DataIntegrityViolationException(SQLException cause, Metadata metadata) {
        super("Integrity constraint violation: " + cause.getMessage(), cause, metadata);
    }
}
