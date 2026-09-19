package io.quarkiverse.jimmer.runtime.exception;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.InheritanceType;
import org.babyfish.jimmer.sql.meta.ColumnDefinition;
import org.babyfish.jimmer.sql.meta.Storage;
import org.babyfish.jimmer.sql.runtime.ExceptionTranslator;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.babyfish.jimmer.sql.runtime.ExecutionPurpose;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Built-in translator that maps a raw {@link SQLException} to a {@link JimmerDataAccessException}
 * subtype by its {@code SQLState}.
 *
 * <p>Registered by default when
 * {@code quarkus.jimmer.<datasource>.sql-state-exception-translator=true} (the default). It only
 * fires when Jimmer does not translate the failure itself, i.e. when
 * {@code constraint-violation-translatable=false} — otherwise constraint violations become
 * {@code SaveException} before ever reaching an {@code ExceptionTranslator<SQLException>}.
 *
 * <p>SQLState is the SQL-standard, DB-agnostic error code. The class {@code 23} (integrity
 * constraint violation) is standardized; finer states below are PostgreSQL/H2 aligned. Databases
 * that only report the generic {@code 23000} (e.g. MySQL) fall through to
 * {@link DataIntegrityViolationException}.
 *
 * <p>Returning {@code null} for an unmapped exception leaves it untouched, so any user-registered
 * {@link ExceptionTranslator} still gets its turn.
 */
public class SqlStateExceptionTranslator implements ExceptionTranslator<SQLException> {

    private static final Map<JSqlClientImplementor, TableTypeResolver> TABLE_TYPE_RESOLVERS = new WeakHashMap<>();

    private static final ThreadLocal<ExecutionContext> EXECUTION_CONTEXT = new ThreadLocal<>();

    public static <R> R executeWithContext(Executor.Args<R> args, Supplier<R> block) {
        ExecutionContext previous = EXECUTION_CONTEXT.get();
        EXECUTION_CONTEXT.set(new ExecutionContext(args.sql, args.variables, args.purpose, args.sqlClient));
        try {
            return block.get();
        } finally {
            if (previous == null) {
                EXECUTION_CONTEXT.remove();
            } else {
                EXECUTION_CONTEXT.set(previous);
            }
        }
    }

    @Override
    @Nullable
    public Exception translate(@NotNull SQLException exception, @NotNull Args args) {
        String sqlState = exception.getSQLState();
        if (sqlState == null) {
            return null;
        }
        JimmerDataAccessException.Metadata metadata = metadata(exception, args);
        return switch (sqlState) {
            case "23505" -> new DuplicateKeyException(exception, metadata);
            case "23503" -> new ForeignKeyViolationException(exception, metadata);
            case "23502" -> new NotNullViolationException(exception, metadata);
            case "23514" -> new CheckViolationException(exception, metadata);
            case "40001" -> new SerializationFailureException(exception, metadata);
            case "40P01" -> new DeadlockException(exception, metadata);
            default -> sqlState.startsWith("23") ? new DataIntegrityViolationException(exception, metadata) : null;
        };
    }

    @Nullable
    private static JimmerDataAccessException.Metadata metadata(SQLException exception, Args args) {
        PostgreSqlError error = PostgreSqlError.from(exception);
        if (error == null) {
            return null;
        }
        ImmutableType immutableType = error.tableName != null
                ? resolver(args.sqlClient()).resolve(error.schemaName, error.tableName)
                : null;
        if (immutableType != null) {
            immutableType = resolveSubtype(immutableType, args.sqlClient());
        }
        return new JimmerDataAccessException.Metadata(
                error.tableName,
                error.schemaName,
                error.constraintName,
                error.detail,
                immutableType);
    }

    private static TableTypeResolver resolver(JSqlClientImplementor sqlClient) {
        synchronized (TABLE_TYPE_RESOLVERS) {
            return TABLE_TYPE_RESOLVERS.computeIfAbsent(sqlClient, TableTypeResolver::new);
        }
    }

    private static ImmutableType resolveSubtype(ImmutableType rootType, JSqlClientImplementor sqlClient) {
        ExecutionContext context = EXECUTION_CONTEXT.get();
        if (context == null || context.sqlClient != sqlClient ||
                context.purpose.getType() != ExecutionPurpose.Type.MUTATE) {
            return rootType;
        }
        if (rootType.getInheritanceInfo() == null ||
                rootType.getInheritanceInfo().getStrategy() != InheritanceType.SINGLE_TABLE) {
            return rootType;
        }
        ImmutableProp discriminatorProp = rootType.getInheritanceInfo().getDiscriminatorProp();
        Storage storage = discriminatorProp.getStorage(sqlClient.getMetadataStrategy());
        if (!(storage instanceof ColumnDefinition) || ((ColumnDefinition) storage).size() != 1) {
            return rootType;
        }
        int variableIndex = InsertStatement.discriminatorVariableIndex(
                context.sql,
                rootType.getTableName(sqlClient.getMetadataStrategy()),
                ((ColumnDefinition) storage).name(0));
        if (variableIndex < 0 || variableIndex >= context.variables.size()) {
            return rootType;
        }
        ImmutableType subtype = rootType.getInheritanceInfo()
                .getDiscriminatorTypeMap()
                .get(context.variables.get(variableIndex));
        return subtype != null ? subtype : rootType;
    }

    private static final class ExecutionContext {

        final String sql;

        final List<Object> variables;

        final ExecutionPurpose purpose;

        final JSqlClientImplementor sqlClient;

        private ExecutionContext(
                String sql,
                List<Object> variables,
                ExecutionPurpose purpose,
                JSqlClientImplementor sqlClient) {
            this.sql = sql;
            this.variables = variables;
            this.purpose = purpose;
            this.sqlClient = sqlClient;
        }
    }

    private static final class InsertStatement {

        private static int discriminatorVariableIndex(String sql, String tableName, String discriminatorColumn) {
            int index = skipWhitespace(sql, 0);
            if (!wordAt(sql, index, "insert")) {
                return -1;
            }
            index = skipWhitespace(sql, index + "insert".length());
            if (!wordAt(sql, index, "into")) {
                return -1;
            }
            index = skipWhitespace(sql, index + "into".length());
            int tableStart = index;
            index = identifierEnd(sql, index);
            if (!TableKey.from(sql.substring(tableStart, index)).tableName
                    .equals(TableKey.from(tableName).tableName)) {
                return -1;
            }
            index = skipWhitespace(sql, index);
            if (index >= sql.length() || sql.charAt(index) != '(') {
                return -1;
            }
            Segment columns = segment(sql, index);
            if (columns == null) {
                return -1;
            }
            index = skipWhitespace(sql, columns.end + 1);
            if (!wordAt(sql, index, "values")) {
                return -1;
            }
            index = skipWhitespace(sql, index + "values".length());
            if (index >= sql.length() || sql.charAt(index) != '(') {
                return -1;
            }
            Segment values = segment(sql, index);
            int nextIndex = values != null ? skipWhitespace(sql, values.end + 1) : -1;
            if (values == null || nextIndex < sql.length() && sql.charAt(nextIndex) == ',') {
                return -1;
            }
            List<String> columnNames = split(columns.value);
            List<String> valueExpressions = split(values.value);
            if (columnNames == null || valueExpressions == null || columnNames.size() != valueExpressions.size()) {
                return -1;
            }
            int discriminatorIndex = -1;
            for (int position = 0; position < columnNames.size(); position++) {
                if (!"?".equals(valueExpressions.get(position))) {
                    return -1;
                }
                if (TableKey.normalize(columnNames.get(position)).equals(TableKey.normalize(discriminatorColumn))) {
                    if (discriminatorIndex != -1) {
                        return -1;
                    }
                    discriminatorIndex = position;
                }
            }
            return discriminatorIndex;
        }

        private static int skipWhitespace(String value, int index) {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
            return index;
        }

        private static boolean wordAt(String value, int index, String word) {
            int end = index + word.length();
            return end <= value.length() && value.regionMatches(true, index, word, 0, word.length()) &&
                    (index == 0 || !Character.isLetterOrDigit(value.charAt(index - 1))) &&
                    (end == value.length() || !Character.isLetterOrDigit(value.charAt(end)));
        }

        private static int identifierEnd(String value, int index) {
            boolean quoted = false;
            while (index < value.length()) {
                char character = value.charAt(index);
                if (character == '"') {
                    quoted = !quoted;
                } else if (!quoted && (Character.isWhitespace(character) || character == '(')) {
                    break;
                }
                index++;
            }
            return index;
        }

        @Nullable
        private static Segment segment(String value, int start) {
            int depth = 0;
            boolean quoted = false;
            for (int index = start; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '"') {
                    quoted = !quoted;
                } else if (!quoted && character == '(') {
                    depth++;
                } else if (!quoted && character == ')' && --depth == 0) {
                    return new Segment(value.substring(start + 1, index), index);
                }
            }
            return null;
        }

        @Nullable
        private static List<String> split(String value) {
            List<String> items = new ArrayList<>();
            boolean quoted = false;
            int start = 0;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '"') {
                    quoted = !quoted;
                } else if (!quoted && character == ',') {
                    String item = value.substring(start, index).trim();
                    if (item.isEmpty()) {
                        return null;
                    }
                    items.add(item);
                    start = index + 1;
                }
            }
            String item = value.substring(start).trim();
            if (item.isEmpty()) {
                return null;
            }
            items.add(item);
            return items;
        }

        private record Segment(String value, int end) {}
    }

    private static final class PostgreSqlError {

        final String tableName;

        final String schemaName;

        final String constraintName;

        final String detail;

        private PostgreSqlError(String tableName, String schemaName, String constraintName, String detail) {
            this.tableName = tableName;
            this.schemaName = schemaName;
            this.constraintName = constraintName;
            this.detail = detail;
        }

        @Nullable
        static PostgreSqlError from(SQLException exception) {
            if (!isPostgreSqlException(exception)) {
                return null;
            }
            try {
                Object serverError = exception.getClass().getMethod("getServerErrorMessage").invoke(exception);
                if (serverError == null) {
                    return null;
                }
                return new PostgreSqlError(
                        value(serverError, "getTable"),
                        value(serverError, "getSchema"),
                        value(serverError, "getConstraint"),
                        value(serverError, "getDetail"));
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | LinkageError ex) {
                return null;
            }
        }

        private static boolean isPostgreSqlException(SQLException exception) {
            for (Class<?> type = exception.getClass(); type != null; type = type.getSuperclass()) {
                if ("org.postgresql.util.PSQLException".equals(type.getName())) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        private static String value(Object serverError, String methodName)
                throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
            Method method = serverError.getClass().getMethod(methodName);
            return (String) method.invoke(serverError);
        }
    }

    private static final class TableTypeResolver {

        private final Map<TableKey, ImmutableType> qualifiedTypes = new HashMap<>();

        private final Map<String, ImmutableType> unqualifiedTypes = new HashMap<>();

        private final Set<TableKey> ambiguousQualifiedTables = new HashSet<>();

        private final Set<String> ambiguousUnqualifiedTables = new HashSet<>();

        private TableTypeResolver(JSqlClientImplementor sqlClient) {
            for (ImmutableType type : sqlClient.getEntityManager().getAllTypes(null)) {
                if (!type.isEntity()) {
                    continue;
                }
                ImmutableType resolvedType = rootTypeForSingleTable(type);
                TableKey tableKey = TableKey.from(type.getTableName(sqlClient.getMetadataStrategy()));
                put(qualifiedTypes, ambiguousQualifiedTables, tableKey, resolvedType);
                put(unqualifiedTypes, ambiguousUnqualifiedTables, tableKey.tableName, resolvedType);
            }
        }

        @Nullable
        private ImmutableType resolve(@Nullable String schemaName, String tableName) {
            if (schemaName != null) {
                ImmutableType type = qualifiedTypes.get(TableKey.of(schemaName, tableName));
                if (type != null) {
                    return type;
                }
            }
            return unqualifiedTypes.get(TableKey.normalize(tableName));
        }

        private static ImmutableType rootTypeForSingleTable(ImmutableType type) {
            return type.getInheritanceInfo() != null &&
                    type.getInheritanceInfo().getStrategy() == InheritanceType.SINGLE_TABLE
                            ? type.getInheritanceInfo().getRootType()
                            : type;
        }

        private static <K> void put(Map<K, ImmutableType> types, Set<K> ambiguousTables, K table, ImmutableType type) {
            if (ambiguousTables.contains(table)) {
                return;
            }
            ImmutableType previous = types.putIfAbsent(table, type);
            if (previous != null && previous != type) {
                types.remove(table);
                ambiguousTables.add(table);
            }
        }
    }

    private record TableKey(@Nullable String schemaName, String tableName) {

        private static TableKey of(String schemaName, String tableName) {
            return new TableKey(normalize(schemaName), normalize(tableName));
        }

        private static TableKey from(String qualifiedTableName) {
            int separator = lastUnquotedPeriod(qualifiedTableName);
            return separator == -1
                    ? new TableKey(null, normalize(qualifiedTableName))
                    : new TableKey(
                            normalize(qualifiedTableName.substring(0, separator)),
                            normalize(qualifiedTableName.substring(separator + 1)));
        }

        private static int lastUnquotedPeriod(String value) {
            boolean quoted = false;
            int separator = -1;
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) == '"') {
                    quoted = !quoted;
                } else if (value.charAt(index) == '.' && !quoted) {
                    separator = index;
                }
            }
            return separator;
        }

        private static String normalize(String identifier) {
            String value = identifier.trim();
            if (value.length() > 1 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                return value.substring(1, value.length() - 1).replace("\"\"", "\"");
            }
            return value.toLowerCase(Locale.ROOT);
        }
    }
}
