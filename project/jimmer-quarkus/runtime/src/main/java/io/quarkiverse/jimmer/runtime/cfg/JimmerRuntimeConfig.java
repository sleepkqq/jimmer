package io.quarkiverse.jimmer.runtime.cfg;

import java.util.*;

import org.babyfish.jimmer.sql.runtime.DatabaseValidationMode;

import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.*;

@ConfigMapping(prefix = "quarkus.jimmer")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface JimmerRuntimeConfig {

    /**
     * Datasource.
     */
    @ConfigDocMapKey("datasource-name")
    @WithParentName
    @WithDefaults
    @WithUnnamedKey(DataSourceUtil.DEFAULT_DATASOURCE_NAME)
    Map<String, JimmerDataSourceRuntimeConfig> dataSources();

    /**
     * jimmer.transactionCacheOperatorFixedDelay
     */
    @WithDefault("5s")
    String transactionCacheOperatorFixedDelay();

    /**
     * jimmer.eagerMetadataInitialization
     * <p>
     * Builds the Jimmer metadata graph and the SqlClient on the main thread during startup,
     * before the Quarkus scheduler starts. Prevents scheduled jobs or other concurrent startup
     * work from racing repository class-init on the lazily initialized SqlClient (stall on the
     * static lock guarding lazy target-type resolution, {@code ImmutablePropImpl.META_LOCK}). Disable to restore
     * fully lazy initialization, e.g. when the application must be able to start without a
     * reachable database.
     */
    @WithDefault("true")
    boolean eagerMetadataInitialization();

    /**
     * jimmer.databaseValidationMode
     */
    @WithDefault("NONE")
    DatabaseValidationMode databaseValidationMode();

    /**
     * jimmer.databaseValidation
     */
    @Deprecated
    DatabaseValidation databaseValidation();

    @Deprecated
    @ConfigGroup
    interface DatabaseValidation {

        /**
         * mode
         */
        @WithDefault("NONE")
        DatabaseValidationMode mode();

        /**
         * catalog
         */
        @Deprecated
        Optional<String> catalog();

        /**
         * schema
         */
        @Deprecated
        Optional<String> schema();
    }
}
