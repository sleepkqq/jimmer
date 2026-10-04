package io.quarkiverse.jimmer.runtime.cfg.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

import javax.sql.DataSource;

import org.babyfish.jimmer.sql.transaction.Propagation;
import org.babyfish.jimmer.sql.transaction.TxConnectionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

public class QuarkusConnectionManager implements DataSourceAwareConnectionManager, TxConnectionManager {

    private final Object connectionKey = new Object();

    /**
     * Connection acquired by the current thread's {@link #execute(Connection, Function)}
     * managed branch, installed only while its callback runs. Used to prove ownership
     * for {@link #isTransactionKnownInactive(Connection)}; never used to manage the
     * connection lifecycle.
     */
    private final ThreadLocal<Connection> managedExecutionConnection = new ThreadLocal<>();

    private final DataSource dataSource;
    private final TransactionManager transactionManager;
    private final TransactionSynchronizationRegistry tsr;

    public QuarkusConnectionManager(DataSource dataSource) {
        this.dataSource = dataSource;
        this.transactionManager = Arc.container().instance(TransactionManager.class).get();
        this.tsr = Arc.container().instance(TransactionSynchronizationRegistry.class).get();
    }

    @NotNull
    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    public final <R> R execute(Function<Connection, R> block) {
        return execute(null, block);
    }

    @Override
    public final <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
        if (null != con) {
            return block.apply(con);
        }

        if (isTransactionActive()) {
            return executeWithTrackedConnection(transactionalConnection(), block);
        }

        try (Connection newConnection = dataSource.getConnection()) {
            return executeWithTrackedConnection(newConnection, block);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private <R> R executeWithTrackedConnection(Connection con, Function<Connection, R> block) {
        Connection previous = managedExecutionConnection.get();
        managedExecutionConnection.set(con);
        try {
            return block.apply(con);
        } finally {
            if (previous != null) {
                managedExecutionConnection.set(previous);
            } else {
                managedExecutionConnection.remove();
            }
        }
    }

    @Override
    public final ConnectionScope open(@Nullable Connection con) {
        if (null != con) {
            return ConnectionScope.userConnection(con);
        }

        if (isTransactionActive()) {
            // connection is bound to the JTA transaction and closed by its synchronization
            return ConnectionScope.userConnection(transactionalConnection());
        }

        Connection newConnection;
        try {
            newConnection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new ConnectionScope() {

            private boolean closed;

            @Override
            public Connection connection() {
                return newConnection;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    newConnection.close();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private Connection transactionalConnection() {
        Connection txConn = (Connection) tsr.getResource(connectionKey);
        if (txConn != null) {
            return txConn;
        }
        Connection conn;
        try {
            conn = dataSource.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        tsr.putResource(connectionKey, conn);
        tsr.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }

            @Override
            public void afterCompletion(int status) {
            }
        });
        return conn;
    }

    @Override
    public <R> R executeTransaction(Propagation propagation, Function<Connection, R> block) {
        TransactionRunnerOptions transactionRunnerOptions = behavior(propagation);
        return transactionRunnerOptions.call(() -> execute(block));
    }

    private boolean isTransactionActive() {
        try {
            Transaction tx = transactionManager.getTransaction();
            return tx != null && tx.getStatus() == Status.STATUS_ACTIVE;
        } catch (SystemException e) {
            return false;
        }
    }

    /**
     * Positive proof for the optional object-cache query hint: the current thread's
     * managed {@code execute} scope must actually own <em>this</em> connection (a
     * null, external or mismatched connection is denied), the JTA manager reports no
     * transaction at all and the JDBC connection really is in auto-commit mode. Any
     * other status (active, marked rollback, preparing, committing, committed, rolled
     * back, unknown), a disabled auto-commit or a transaction-manager error is
     * conservatively denied. This deliberately does not reuse
     * {@link #isTransactionActive()}, which only recognizes {@link Status#STATUS_ACTIVE}.
     */
    @Override
    public boolean isTransactionKnownInactive(Connection con) {
        if (con == null || managedExecutionConnection.get() != con) {
            return false;
        }
        try {
            if (transactionManager.getStatus() != Status.STATUS_NO_TRANSACTION) {
                return false;
            }
            return con.getAutoCommit();
        } catch (SystemException | SQLException | RuntimeException e) {
            return false;
        }
    }

    private TransactionRunnerOptions behavior(Propagation propagation) {
        switch (propagation) {
            case REQUIRES_NEW:
                return QuarkusTransaction.requiringNew();
            case SUPPORTS:
                throw new UnsupportedOperationException("Quarkus does not support SUPPORTS");
            case NOT_SUPPORTED:
                return QuarkusTransaction.suspendingExisting();
            case MANDATORY:
                throw new UnsupportedOperationException("Quarkus does not support MANDATORY");
            case NEVER:
                throw new UnsupportedOperationException("Quarkus does not support NEVER");
            default:
                return QuarkusTransaction.joiningExisting();
        }
    }
}
