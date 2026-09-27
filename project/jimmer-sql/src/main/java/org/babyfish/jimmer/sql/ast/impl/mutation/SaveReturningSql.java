package org.babyfish.jimmer.sql.ast.impl.mutation;

import org.babyfish.jimmer.runtime.DraftSpi;
import org.babyfish.jimmer.sql.ast.impl.render.AbstractSqlBuilder;
import org.babyfish.jimmer.sql.ast.impl.value.GetterMetadata;
import org.babyfish.jimmer.sql.ast.impl.value.PropertyGetter;
import org.babyfish.jimmer.sql.runtime.SqlBuilder;

import java.time.temporal.Temporal;
import java.util.Date;

class SaveReturningSql {

    private SaveReturningSql() {}

    static void appendSourceTuples(
            SaveReturning returning,
            SqlBuilder builder,
            EntityCollection<DraftSpi> entities
    ) {
        boolean addComma = false;
        for (DraftSpi draft : entities) {
            if (addComma) {
                builder.sql(", ");
            } else {
                addComma = true;
            }
            builder.enter(AbstractSqlBuilder.ScopeType.TUPLE);
            appendSourceValues(returning, builder, draft);
            builder.leave();
        }
    }

    static void appendSourceValues(
            SaveReturning returning,
            SqlBuilder builder,
            DraftSpi draft
    ) {
        for (SaveReturningColumnValue sourceValue : returning.sourceValues) {
            builder.separator();
            String sqlType = null;
            if (returning.kind == SaveReturningKind.UPDATE) {
                GetterMetadata metadata = sourceValue.getter.metadata();
                Class<?> type = metadata.getSqlType();
                // JDBC temporal parameters can be untyped; derived VALUES lack target-column context.
                if (Date.class.isAssignableFrom(type) || Temporal.class.isAssignableFrom(type)) {
                    sqlType = metadata.getSqlTypeName();
                }
            }
            if (sqlType != null) {
                builder.sql("cast(");
            }
            sourceValue.appendValue(builder, draft);
            if (sqlType != null) {
                builder.sql(" as ").sql(sqlType).sql(")");
            }
        }
    }

    static void appendSourceColumns(SaveReturning returning, SqlBuilder builder) {
        for (SaveReturningColumnValue sourceValue : returning.sourceValues) {
            builder.separator().sql(sourceValue.getter);
        }
    }

    static void appendReturning(SaveReturning returning, SqlBuilder builder, String prefix) {
        boolean addComma = false;
        for (PropertyGetter getter : returning.returningGetters) {
            if (addComma) {
                builder.sql(", ");
            } else {
                addComma = true;
            }
            builder.sql(prefix).sql(getter);
        }
    }

}
