package org.babyfish.jimmer.sql.runtime;

import org.babyfish.jimmer.sql.transaction.AbstractTxConnectionManager;
import org.babyfish.jimmer.sql.transaction.TxConnectionManager;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

@FunctionalInterface
public interface ConnectionManager {

    <R> R execute(@Nullable Connection con, Function<Connection, R> block);

    default <R> R execute(Function<Connection, R> block) {
        return execute(null, block);
    }

    default ConnectionScope open(@Nullable Connection con) {
        if (con != null) {
            return ConnectionScope.userConnection(con);
        }
        throw new UnsupportedOperationException(
                "The current connection manager does not support streaming without explicit JDBC connection"
        );
    }

    /**
     * <p>Best-effort proof that the given connection is not inside a managed
     * transaction. Only the object-cache query optimization consults this; ordinary
     * execution is unaffected.</p>
     *
     * <p>The default returns {@code false} because a custom, external or otherwise
     * unknown manager cannot prove anything, and the optimization must then use
     * ordinary SQL. A built-in transaction manager overrides this to positively
     * prove that its scope has no active transaction and that the JDBC connection
     * really is in auto-commit mode.</p>
     *
     * @param con the connection that will be used, never {@code null}
     * @return {@code true} only if the manager can prove the connection is not in a
     *         transaction
     */
    default boolean isTransactionKnownInactive(Connection con) {
        return false;
    }

    ConnectionManager EXTERNAL_ONLY = new ConnectionManager() {
        @Override
        public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
            if (con == null) {
                throw new IllegalArgumentException(
                        "The connection manager is not specified " +
                                "so \"ConnectionManager.EXTERNAL_ONLY\" " +
                                "which does not support no explicit JDBC " +
                                "connection execution is used as default. " +
                                "There are 2 choices: " +
                                "1. Specify the connection when execute statement/command" +
                                "2. Specify the connection manager"
                );
            }
            return block.apply(con);
        }
    };

    static ConnectionManager singleConnectionManager(Connection connection) {
        if (connection == null) {
            return EXTERNAL_ONLY;
        }
        return new ConnectionManager() {
            @Override
            public <R> R execute(@Nullable Connection con, Function<Connection, R> block) {
                return block.apply(con == null ? connection : con);
            }
        };
    }

    static TxConnectionManager simpleConnectionManager(DataSource dataSource) {
        return new AbstractTxConnectionManager() {

            @Override
            protected Connection openConnection() throws SQLException {
                return dataSource.getConnection();
            }
        };
    }

    interface ConnectionScope extends AutoCloseable {

        Connection connection();

        @Override
        void close();

        static ConnectionScope userConnection(Connection con) {
            return new ConnectionScope() {
                @Override
                public Connection connection() {
                    return con;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
