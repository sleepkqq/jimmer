package io.quarkiverse.jimmer.runtime.cfg.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Deferred {@link Connection} proxy: the pool is asked for a connection only when a
 * JDBC method actually needs one, so a read fully served by the object cache never
 * acquires (or enlists) a connection.
 *
 * <p>Not thread-safe by design: a connection is confined to the thread that runs its
 * executing scope or transaction, which is the only thread that may close it.</p>
 */
final class LazyConnection implements InvocationHandler {

    private final DataSource dataSource;
    private final Connection proxy;

    private Connection target;
    private boolean closed;

    LazyConnection(DataSource dataSource) {
        this.dataSource = dataSource;
        this.proxy = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                this);
    }

    Connection proxy() {
        return proxy;
    }

    boolean isAcquired() {
        return target != null;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "equals":
                return ignored == args[0];
            case "hashCode":
                return System.identityHashCode(ignored);
            case "toString":
                return "LazyConnection@" + Integer.toHexString(System.identityHashCode(ignored));
            case "close":
                close();
                return null;
            case "isClosed":
                return isClosed();
            default:
                return invokeJdbc(method, args);
        }
    }

    private Object invokeJdbc(Method method, Object[] args) throws Throwable {
        Connection acquired = acquire();
        try {
            return method.invoke(acquired, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Connection acquire() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
        if (target == null) {
            target = dataSource.getConnection();
        }
        return target;
    }

    private boolean isClosed() throws SQLException {
        return closed || (isAcquired() && target.isClosed());
    }

    private void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        if (isAcquired()) {
            target.close();
        }
    }
}
