package org.babyfish.jimmer.sql.transaction;

import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Focused proof for the native {@link AbstractTxConnectionManager#isTransactionKnownInactive(Connection)}
 * scope check: the manager may only positively recognize a connection that is actually
 * owned by the current thread's non-transactional scope <em>and</em> is in auto-commit
 * mode. A missing scope, a scope owning another connection, a transactional scope, a
 * disabled auto-commit or any JDBC/runtime error during introspection must all deny.
 *
 * <p>This is a standalone H2 fixture with no ambient framework transaction, so
 * {@code autoCommit == true} is a genuine proof.</p>
 */
public class NativeScopeConnectionManagerTest {

    private static final JdbcDataSource DATA_SOURCE = new JdbcDataSource();

    static {
        DATA_SOURCE.setURL("jdbc:h2:mem:native_scope_manager;DB_CLOSE_DELAY=-1");
    }

    private final TxConnectionManager manager = ConnectionManager.simpleConnectionManager(DATA_SOURCE);

    @Test
    public void ownedSupportsScopeIsKnownInactive() {
        manager.execute(null, con -> {
            Assertions.assertTrue(autoCommit(con));
            Assertions.assertTrue(manager.isTransactionKnownInactive(con));
            return null;
        });
    }

    @Test
    public void openedSupportsScopeIsKnownInactive() {
        try (ConnectionManager.ConnectionScope scope = manager.open(null)) {
            Connection con = scope.connection();
            Assertions.assertTrue(autoCommit(con));
            Assertions.assertTrue(manager.isTransactionKnownInactive(con));
        }
    }

    @Test
    public void nullConnectionIsNotKnownInactive() {
        Assertions.assertFalse(manager.isTransactionKnownInactive(null));
    }

    @Test
    public void externalAutoCommitConnectionWithoutScopeIsNotKnownInactive() {
        Connection con = newConnection();
        try {
            setAutoCommit(con, true);
            Assertions.assertFalse(manager.isTransactionKnownInactive(con));
        } finally {
            close(con);
        }
    }

    @Test
    public void connectionOwnedByAnotherScopeIsNotKnownInactive() {
        manager.execute(null, owned -> {
            Connection other = newConnection();
            try {
                setAutoCommit(other, true);
                Assertions.assertFalse(manager.isTransactionKnownInactive(other));
            } finally {
                close(other);
            }
            return null;
        });
    }

    @Test
    public void transactionScopeIsNotKnownInactive() {
        manager.executeTransaction(Propagation.REQUIRED, con -> {
            Assertions.assertFalse(autoCommit(con));
            Assertions.assertFalse(manager.isTransactionKnownInactive(con));
            return null;
        });
    }

    @Test
    public void nonTransactionalScopeWithAutoCommitDisabledIsNotKnownInactive() {
        try (ConnectionManager.ConnectionScope scope = manager.open(null)) {
            Connection con = scope.connection();
            setAutoCommit(con, false);
            Assertions.assertFalse(manager.isTransactionKnownInactive(con));
        }
    }

    @Test
    public void introspectionSqlExceptionIsNotKnownInactive() {
        TxConnectionManager throwing = new AbstractTxConnectionManager() {
            @Override
            protected Connection openConnection() {
                return failingConnection(false);
            }
        };
        throwing.execute(null, con -> {
            Assertions.assertFalse(throwing.isTransactionKnownInactive(con));
            return null;
        });
    }

    @Test
    public void introspectionRuntimeExceptionIsNotKnownInactive() {
        TxConnectionManager throwing = new AbstractTxConnectionManager() {
            @Override
            protected Connection openConnection() {
                return failingConnection(true);
            }
        };
        throwing.execute(null, con -> {
            Assertions.assertFalse(throwing.isTransactionKnownInactive(con));
            return null;
        });
    }

    private static Connection failingConnection(boolean runtimeFailure) {
        return (Connection) Proxy.newProxyInstance(
                NativeScopeConnectionManagerTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        switch (method.getName()) {
                            case "getAutoCommit":
                                if (runtimeFailure) {
                                    throw new IllegalStateException("introspection failure");
                                }
                                throw new SQLException("introspection failure");
                            case "close":
                                return null;
                            case "toString":
                                return "failing-con";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return null;
                        }
                    }
                }
        );
    }

    private static Connection newConnection() {
        try {
            return DATA_SOURCE.getConnection();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static boolean autoCommit(Connection con) {
        try {
            return con.getAutoCommit();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void setAutoCommit(Connection con, boolean value) {
        try {
            con.setAutoCommit(value);
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void close(Connection con) {
        try {
            con.close();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }
}
