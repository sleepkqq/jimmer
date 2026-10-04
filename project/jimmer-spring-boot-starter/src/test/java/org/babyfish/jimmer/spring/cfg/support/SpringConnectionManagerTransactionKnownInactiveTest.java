package org.babyfish.jimmer.spring.cfg.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.babyfish.jimmer.spring.datasource.DataSources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Covers the {@link SpringConnectionManager#isTransactionKnownInactive(Connection)}
 * guard. The manager may only positively recognize a connection that is actually
 * owned by the current thread's managed {@code execute} scope and is in auto-commit
 * mode: an external auto-commit connection, a mismatched connection, a disabled
 * auto-commit, an active/rollback-only transaction, a null connection or any JDBC
 * introspection error is denied. Nested managed scopes and failing callbacks must
 * restore the outer ownership proof.
 */
public class SpringConnectionManagerTransactionKnownInactiveTest {

    private DataSource dataSource;

    private SpringConnectionManager connectionManager;

    @BeforeEach
    public void setUp() {
        dataSource = DataSources.create(null);
        connectionManager = new SpringConnectionManager(dataSource);
    }

    @Test
    public void managedAutoCommitConnectionIsKnownInactive() {
        connectionManager.execute(con -> {
            assertTrue(autoCommit(con));
            assertTrue(connectionManager.isTransactionKnownInactive(con));
            return null;
        });
    }

    @Test
    public void externalAutoCommitConnectionOutsideManagedScopeIsNotKnownInactive() throws SQLException {
        try (Connection con = dataSource.getConnection()) {
            assertTrue(con.getAutoCommit());
            assertFalse(connectionManager.isTransactionKnownInactive(con));
        }
    }

    @Test
    public void differentExternalConnectionInsideManagedScopeIsNotKnownInactive() {
        connectionManager.execute(managed -> {
            try (Connection external = dataSource.getConnection()) {
                assertTrue(external.getAutoCommit());
                assertFalse(connectionManager.isTransactionKnownInactive(external));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Test
    public void ownedAutoCommitDisabledConnectionIsNotKnownInactive() {
        connectionManager.execute(con -> {
            try {
                con.setAutoCommit(false);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            assertFalse(connectionManager.isTransactionKnownInactive(con));
            return null;
        });
    }

    @Test
    public void nullConnectionIsNotKnownInactive() {
        assertFalse(connectionManager.isTransactionKnownInactive(null));
    }

    @Test
    public void activeSpringTransactionIsNotKnownInactive() {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            connectionManager.execute(con -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(connectionManager.isTransactionKnownInactive(con));
                return null;
            });
        } finally {
            transactionManager.rollback(status);
        }
    }

    @Test
    public void rollbackOnlySpringTransactionIsNotKnownInactive() {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            status.setRollbackOnly();
            connectionManager.execute(con -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(connectionManager.isTransactionKnownInactive(con));
                return null;
            });
        } finally {
            transactionManager.rollback(status);
        }
    }

    @Test
    public void synchronizationOnlyScopeIsNotKnownInactive() throws SQLException {
        TransactionSynchronizationManager.initSynchronization();
        try (Connection con = dataSource.getConnection()) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            assertFalse(connectionManager.isTransactionKnownInactive(con));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    public void resourceBoundConnectionIsNotKnownInactive() throws SQLException {
        try (Connection con = dataSource.getConnection()) {
            TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(con));
            try {
                assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
                assertTrue(DataSourceUtils.isConnectionTransactional(con, dataSource));
                assertFalse(connectionManager.isTransactionKnownInactive(con));
            } finally {
                TransactionSynchronizationManager.unbindResource(dataSource);
            }
        }
    }

    @Test
    public void nestedManagedScopeRestoresOuterOwnership() {
        connectionManager.execute(outer -> {
            assertTrue(connectionManager.isTransactionKnownInactive(outer));
            connectionManager.execute(inner -> {
                assertTrue(connectionManager.isTransactionKnownInactive(inner));
                return null;
            });
            assertTrue(connectionManager.isTransactionKnownInactive(outer));
            return null;
        });
    }

    @Test
    public void failingNestedScopeRestoresOuterOwnership() {
        connectionManager.execute(outer -> {
            assertThrows(
                    IllegalStateException.class,
                    () -> connectionManager.execute(inner -> {
                        throw new IllegalStateException("boom");
                    })
            );
            assertTrue(connectionManager.isTransactionKnownInactive(outer));
            return null;
        });
    }

    @Test
    public void introspectionSqlExceptionIsNotKnownInactive() {
        AtomicBoolean fail = new AtomicBoolean();
        SpringConnectionManager manager = failingManager(fail, false);
        manager.execute(con -> {
            fail.set(true);
            assertFalse(manager.isTransactionKnownInactive(con));
            return null;
        });
    }

    @Test
    public void introspectionRuntimeExceptionIsNotKnownInactive() {
        AtomicBoolean fail = new AtomicBoolean();
        SpringConnectionManager manager = failingManager(fail, true);
        manager.execute(con -> {
            fail.set(true);
            assertFalse(manager.isTransactionKnownInactive(con));
            return null;
        });
    }

    private SpringConnectionManager failingManager(AtomicBoolean fail, boolean runtimeFailure) {
        Connection real = newConnection();
        Connection proxy = (Connection) Proxy.newProxyInstance(
                SpringConnectionManagerTransactionKnownInactiveTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (p, method, args) -> {
                    if (method.getName().equals("getAutoCommit") && fail.get()) {
                        if (runtimeFailure) {
                            throw new IllegalStateException("introspection failure");
                        }
                        throw new SQLException("introspection failure");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
        DataSource proxyDataSource = new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                return proxy;
            }

            @Override
            public Connection getConnection(String username, String password) {
                return getConnection();
            }
        };
        return new SpringConnectionManager(proxyDataSource);
    }

    private Connection newConnection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean autoCommit(Connection con) {
        try {
            return con.getAutoCommit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
