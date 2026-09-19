package io.quarkiverse.jimmer.runtime.exception;

import java.sql.SQLException;

import org.babyfish.jimmer.meta.ImmutableType;

/**
 * Base type for the runtime exceptions produced by {@link SqlStateExceptionTranslator} when
 * {@code quarkus.jimmer.<datasource>.constraint-violation-translatable=false}.
 *
 * <p>Instead of Jimmer's {@code SaveException}, a raw {@link SQLException} is mapped to a concrete
 * subtype of this exception based on its {@code SQLState}. The original {@link SQLException} is kept
 * as the cause and exposed through {@link #getSqlException()} / {@link #getSqlState()}.
 */
public abstract class JimmerDataAccessException extends RuntimeException {

    private final String sqlState;

    private final String tableName;

    private final String schemaName;

    private final String constraintName;

    private final String detail;

    private final ImmutableType immutableType;

    protected JimmerDataAccessException(String message, SQLException cause) {
        this(message, cause, null);
    }

    protected JimmerDataAccessException(String message, SQLException cause, Metadata metadata) {
        super(message, cause);
        this.sqlState = cause.getSQLState();
        this.tableName = metadata != null ? metadata.tableName : null;
        this.schemaName = metadata != null ? metadata.schemaName : null;
        this.constraintName = metadata != null ? metadata.constraintName : null;
        this.detail = metadata != null ? metadata.detail : null;
        this.immutableType = metadata != null ? metadata.immutableType : null;
    }

    /**
     * The {@code SQLState} of the underlying {@link SQLException} (e.g. {@code 23505} for a unique
     * violation), or {@code null} if the driver did not report one.
     */
    public String getSqlState() {
        return sqlState;
    }

    /**
     * The original driver exception that triggered this translation.
     */
    public SQLException getSqlException() {
        return (SQLException) getCause();
    }

    public String getTableName() {
        return tableName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getConstraintName() {
        return constraintName;
    }

    public String getDetail() {
        return detail;
    }

    public ImmutableType getImmutableType() {
        return immutableType;
    }

    static final class Metadata {

        final String tableName;

        final String schemaName;

        final String constraintName;

        final String detail;

        final ImmutableType immutableType;

        Metadata(String tableName, String schemaName, String constraintName, String detail, ImmutableType immutableType) {
            this.tableName = tableName;
            this.schemaName = schemaName;
            this.constraintName = constraintName;
            this.detail = detail;
            this.immutableType = immutableType;
        }
    }
}
