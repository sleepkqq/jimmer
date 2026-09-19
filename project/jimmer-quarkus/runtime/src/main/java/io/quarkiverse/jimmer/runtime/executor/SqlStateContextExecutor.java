package io.quarkiverse.jimmer.runtime.executor;

import java.sql.Connection;
import java.util.List;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.sql.runtime.ExceptionTranslator;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.babyfish.jimmer.sql.runtime.ExecutorContext;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.quarkiverse.jimmer.runtime.exception.SqlStateExceptionTranslator;

public class SqlStateContextExecutor implements Executor {

    private final Executor delegate;

    public SqlStateContextExecutor(@Nullable Executor delegate) {
        this.delegate = delegate != null ? delegate : org.babyfish.jimmer.sql.runtime.DefaultExecutor.INSTANCE;
    }

    @Override
    public <R> R execute(@NotNull Args<R> args) {
        return SqlStateExceptionTranslator.executeWithContext(args, () -> delegate.execute(args));
    }

    @Override
    public BatchContext executeBatch(
            @NotNull Connection con,
            @NotNull String sql,
            @Nullable ImmutableProp generatedIdProp,
            @NotNull ExecutionPurpose purpose,
            @NotNull JSqlClientImplementor sqlClient,
            boolean constraintViolationTranslatable) {
        return delegate.executeBatch(con, sql, generatedIdProp, purpose, sqlClient, constraintViolationTranslatable);
    }

    @Override
    public void openCursor(
            long cursorId,
            String sql,
            List<Object> variables,
            List<Integer> variablePositions,
            ExecutionPurpose purpose,
            @Nullable ExecutorContext ctx,
            JSqlClientImplementor sqlClient) {
        delegate.openCursor(cursorId, sql, variables, variablePositions, purpose, ctx, sqlClient);
    }

    @Override
    public void closeCursor(long cursorId) {
        delegate.closeCursor(cursorId);
    }
}
