package io.quarkiverse.jimmer.runtime.executor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.sql.runtime.DefaultExecutor;
import org.babyfish.jimmer.sql.runtime.ExceptionTranslator;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.babyfish.jimmer.sql.runtime.ExecutorContext;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-line-per-statement SQL log under the {@code jimmer.sql} logger:
 * {@code SQL SELECT social.users (20 rows) | 12ms [QUERY]}. An alternative to Jimmer's verbose
 * {@code show-sql} output for production-like log volumes; enabled per datasource with
 * {@code quarkus.jimmer.compact-sql-log} (takes precedence over {@code show-sql}).
 */
public class CompactSqlExecutor implements Executor {

    private static final Logger LOG = LoggerFactory.getLogger("jimmer.sql");

    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[\\s,()]+");

    private final Executor delegate;

    public CompactSqlExecutor(@Nullable Executor delegate) {
        this.delegate = delegate != null ? delegate : DefaultExecutor.INSTANCE;
    }

    @Override
    public <R> R execute(@NotNull Args<R> args) {
        long start = System.nanoTime();
        R result = delegate.execute(args);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        LOG.info("SQL {} {} ({}) | {}ms [{}]",
                extractOperation(args.sql),
                extractTable(args.sql),
                formatRows(result),
                durationMs,
                args.purpose.getType().name());
        return result;
    }

    @Override
    public BatchContext executeBatch(
            @NotNull Connection con,
            @NotNull String sql,
            @Nullable ImmutableProp generatedIdProp,
            @NotNull ExecutionPurpose purpose,
            @NotNull JSqlClientImplementor sqlClient,
            boolean constraintViolationTranslatable) {
        BatchContext raw = delegate.executeBatch(con, sql, generatedIdProp, purpose, sqlClient,
                constraintViolationTranslatable);
        String operation = extractOperation(sql);
        String table = extractTable(sql);
        return new BatchContext() {

            private int rowCount;

            @Override
            public JSqlClientImplementor sqlClient() {
                return raw.sqlClient();
            }

            @Override
            public String sql() {
                return raw.sql();
            }

            @Override
            public ExecutionPurpose purpose() {
                return raw.purpose();
            }

            @Override
            public ExecutorContext ctx() {
                return raw.ctx();
            }

            @Override
            public void add(List<Object> variables) {
                rowCount++;
                raw.add(variables);
            }

            @Override
            public int[] execute(BiFunction<SQLException, ExceptionTranslator.Args, Exception> exceptionTranslator) {
                long start = System.nanoTime();
                int[] result = raw.execute(exceptionTranslator);
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                LOG.info("SQL BATCH {} {} ({} rows) | {}ms [{}]",
                        operation, table, rowCount, durationMs, purpose.getType().name());
                return result;
            }

            @Override
            public Object[] generatedIds() {
                return raw.generatedIds();
            }

            @Override
            public void addExecutedListener(Runnable listener) {
                raw.addExecutedListener(listener);
            }

            @Override
            public void close() {
                raw.close();
            }
        };
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

    public static String extractOperation(String sql) {
        List<String> tokens = tokenize(sql);
        return tokens.isEmpty() ? "?" : tokens.get(0).toUpperCase();
    }

    public static String extractTable(String sql) {
        List<String> tokens = tokenize(sql);
        if (tokens.isEmpty()) {
            return "?";
        }
        String keyword;
        switch (tokens.get(0).toUpperCase()) {
            case "SELECT":
            case "DELETE":
                keyword = "from";
                break;
            case "INSERT":
            case "MERGE":
                keyword = "into";
                break;
            case "UPDATE":
                keyword = "update";
                break;
            default:
                return "?";
        }
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).equalsIgnoreCase(keyword)) {
                String table = tokens.get(i + 1).toLowerCase();
                return stripQuotes(table);
            }
        }
        return "?";
    }

    public static String formatRows(Object result) {
        if (result instanceof Collection<?>) {
            return ((Collection<?>) result).size() + " rows";
        }
        if (result instanceof Integer || result instanceof Long) {
            return result + " rows";
        }
        if (result == null) {
            return "0 rows";
        }
        return "1 row";
    }

    private static String stripQuotes(String identifier) {
        if (identifier.length() >= 2 && identifier.startsWith("\"") && identifier.endsWith("\"")) {
            return identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }

    private static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        for (String token : TOKEN_SEPARATOR.split(sql)) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
