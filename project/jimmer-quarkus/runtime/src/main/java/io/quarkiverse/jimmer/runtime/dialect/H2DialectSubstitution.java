package io.quarkiverse.jimmer.runtime.dialect;

import java.sql.SQLException;

import org.babyfish.jimmer.sql.dialect.H2Dialect;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM substitution that prevents native-image from parsing H2Dialect.jsonToBaseValue,
 * which references org.h2.value.ValueJson (not on classpath for non-H2 apps).
 */
@TargetClass(H2Dialect.class)
final class Target_org_babyfish_jimmer_sql_dialect_H2Dialect {

    @Substitute
    public Object jsonToBaseValue(String value) throws SQLException {
        throw new UnsupportedOperationException("H2Dialect is not supported in native image");
    }
}
