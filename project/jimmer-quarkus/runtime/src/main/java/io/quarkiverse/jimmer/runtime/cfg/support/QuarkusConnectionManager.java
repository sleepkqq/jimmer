package io.quarkiverse.jimmer.runtime.cfg.support;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.cache.Cache;
import org.babyfish.jimmer.sql.cache.Caches;
import org.babyfish.jimmer.sql.runtime.MutationKeyListener;
import org.babyfish.jimmer.sql.transaction.Propagation;
import org.babyfish.jimmer.sql.transaction.TxConnectionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

public class QuarkusConnectionManager implements DataSourceAwareConnectionManager, TxConnectionManager, MutationKeyListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuarkusConnectionManager.class);

    private final Object connectionKey = new Object();

    private final Object mutationKeysKey = new Object();

    /**
     * Connection acquired by the current thread's {@link #execute(Connection, Function)}
     * managed branch, installed only while its callback runs. Used to prove ownership
     * for {@link #isTransactionKnownInactive(Connection)}; never used to manage the
     * connection lifecycle.
     */
    private final ThreadLocal<Connection> managedExecutionConnection = new ThreadLocal<>();

    /**
     * Evictions accumulated by the current thread's non-transactional managed
     * {@link #execute(Connection, Function)} scope. Auto-commit makes every statement durable
     * before the scope ends, so they are flushed once the scope's connection is closed.
     */
    private final ThreadLocal<MutationBatch> autoCommitMutationBatch = new ThreadLocal<>();

    /**
     * Caches of the client that owns this manager; {@code null} while the client has no caches
     * or does not use {@code BINLOG_ONLY}, in which case no mutation key is accumulated.
     */
    private volatile Caches caches;

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

    /**
     * Called by the Quarkus client once its caches exist. A {@code BINLOG_ONLY} client fires no
     * transaction triggers, so this manager reports mutated keys and evicts them after commit.
     */
    public void setCaches(@Nullable Caches caches) {
        this.caches = caches;
    }

    @Override
    public void entitiesMutated(Connection con, ImmutableType type, Collection<?> ids) {
        if (ids.isEmpty()) {
            return;
        }
        MutationBatch batch = mutationBatch(con);
        if (batch == null) {
            return;
        }
        batch.entityIds.computeIfAbsent(type, it -> new LinkedHashSet<>()).addAll(ids);
        evictIfImmediate(batch);
    }

    @Override
    public void associationsMutated(Connection con, ImmutableProp prop, Collection<?> sourceIds) {
        if (sourceIds.isEmpty()) {
            return;
        }
        MutationBatch batch = mutationBatch(con);
        if (batch == null) {
            return;
        }
        batch.propIds.computeIfAbsent(prop, it -> new LinkedHashSet<>()).addAll(sourceIds);
        evictIfImmediate(batch);
    }

    /**
     * Selects the batch a mutation belongs to:
     * <ul>
     *     <li>an active JTA transaction uses its {@link TransactionSynchronizationRegistry} resource,
     *     so eviction runs once, from {@code afterCompletion};</li>
     *     <li>the thread's auto-commit managed {@code execute} scope uses its own batch;</li>
     *     <li>statements executed outside both (streamed update-returning closes its stream after
     *     the executing scope) are already durable, so they get an immediately evicted batch.</li>
     * </ul>
     * A user-supplied connection is never ours and is ignored.
     */
    @Nullable
    private MutationBatch mutationBatch(Connection con) {
        if (caches == null || con == null || !isManagedConnection(con)) {
            return null;
        }
        if (isTransactionActive()) {
            return transactionalMutationBatch();
        }
        MutationBatch batch = autoCommitMutationBatch.get();
        return batch != null ? batch : new MutationBatch(true);
    }

    private static boolean isManagedConnection(Connection con) {
        return Proxy.isProxyClass(con.getClass()) &&
                Proxy.getInvocationHandler(con) instanceof LazyConnection;
    }

    private MutationBatch transactionalMutationBatch() {
        Object resource = tsr.getResource(mutationKeysKey);
        if (resource instanceof MutationBatch) {
            return (MutationBatch) resource;
        }
        MutationBatch batch = new MutationBatch(false);
        try {
            tsr.putResource(mutationKeysKey, batch);
            tsr.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        evict(batch);
                    }
                }
            });
        } catch (RuntimeException ex) {
            LOGGER.warn("Cannot register post-commit cache eviction for the current transaction", ex);
        }
        return batch;
    }

    private void evictIfImmediate(MutationBatch batch) {
        if (batch.immediate) {
            evict(batch);
        }
    }

    private void evict(MutationBatch batch) {
        Caches caches = this.caches;
        if (caches == null || batch.isEmpty()) {
            return;
        }
        for (Map.Entry<ImmutableType, Set<Object>> entry : batch.entityIds.entrySet()) {
            try {
                Cache<Object, Object> cache = caches.getObjectCache(entry.getKey());
                if (cache != null) {
                    cache.deleteAll(entry.getValue());
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Failed to evict object cache of \"{}\" after commit", entry.getKey(), ex);
            }
        }
        for (Map.Entry<ImmutableProp, Set<Object>> entry : batch.propIds.entrySet()) {
            try {
                Cache<Object, Object> cache = caches.getPropertyCache(entry.getKey());
                if (cache != null) {
                    cache.deleteAll(entry.getValue());
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Failed to evict property cache of \"{}\" after commit", entry.getKey(), ex);
            }
        }
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

        try (Connection lazyConnection = new LazyConnection(dataSource).proxy()) {
            MutationBatch batch = new MutationBatch(false);
            MutationBatch previous = autoCommitMutationBatch.get();
            autoCommitMutationBatch.set(batch);
            try {
                return executeWithTrackedConnection(lazyConnection, block);
            } finally {
                if (previous != null) {
                    autoCommitMutationBatch.set(previous);
                } else {
                    autoCommitMutationBatch.remove();
                }
                // The connection is closed here, so every auto-committed statement is durable.
                evict(batch);
            }
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

        Connection lazyConnection = new LazyConnection(dataSource).proxy();
        return new ConnectionScope() {

            private boolean closed;

            @Override
            public Connection connection() {
                return lazyConnection;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    lazyConnection.close();
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
        Connection conn = new LazyConnection(dataSource).proxy();
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

    private static final class MutationBatch {

        private final boolean immediate;

        private final Map<ImmutableType, Set<Object>> entityIds = new LinkedHashMap<>();

        private final Map<ImmutableProp, Set<Object>> propIds = new LinkedHashMap<>();

        private MutationBatch(boolean immediate) {
            this.immediate = immediate;
        }

        private boolean isEmpty() {
            return entityIds.isEmpty() && propIds.isEmpty();
        }
    }
}
