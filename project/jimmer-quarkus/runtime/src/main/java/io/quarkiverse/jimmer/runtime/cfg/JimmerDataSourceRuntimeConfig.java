package io.quarkiverse.jimmer.runtime.cfg;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.babyfish.jimmer.sql.EnumType;
import org.babyfish.jimmer.sql.fetcher.ReferenceFetchType;
import org.babyfish.jimmer.sql.runtime.IdOnlyTargetCheckingLevel;

import io.quarkus.runtime.annotations.ConfigDocDefault;
import io.quarkus.runtime.annotations.ConfigGroup;
import io.smallrye.config.WithDefault;

@ConfigGroup
public interface JimmerDataSourceRuntimeConfig {

    /**
     * Flag to activate/deactivate Jimmer for a specific datasource at runtime.
     */
    @ConfigDocDefault("'true' if the datasource is active; 'false' otherwise")
    Optional<Boolean> active();

    /**
     * jimmer.dialect
     */
    Optional<String> dialect();

    /**
     * jimmer.defaultReferenceFetchType
     */
    @WithDefault("SELECT")
    ReferenceFetchType defaultReferenceFetchType();

    /**
     * jimmer.maxJoinFetchDepth
     */
    @WithDefault("3")
    OptionalInt maxJoinFetchDepth();

    /**
     * jimmer.defaultDissociationActionCheckable
     */
    @WithDefault("true")
    boolean defaultDissociationActionCheckable();

    /**
     * jimmer.idOnlyTargetCheckingLevel
     */
    @WithDefault("NONE")
    IdOnlyTargetCheckingLevel idOnlyTargetCheckingLevel();

    /**
     * jimmer.defaultEnumStrategy
     */
    @WithDefault("NAME")
    EnumType.Strategy defaultEnumStrategy();

    /**
     * jimmer.defaultSchema
     */
    Optional<String> defaultSchema();

    /**
     * jimmer.defaultBatchSize
     */
    @WithDefault("128")
    OptionalInt defaultBatchSize();

    /**
     * jimmer.inListPaddingEnabled
     */
    @WithDefault("false")
    boolean inListPaddingEnabled();

    /**
     * jimmer.expandedInListPaddingEnabled
     */
    @WithDefault("false")
    boolean expandedInListPaddingEnabled();

    /**
     * jimmer.defaultListBatchSize
     */
    @WithDefault("16")
    OptionalInt defaultListBatchSize();

    /**
     * jimmer.dissociationLogicalDeleteEnabled
     */
    @WithDefault("false")
    boolean dissociationLogicalDeleteEnabled();

    /**
     * jimmer.offsetOptimizingThreshold
     */
    @WithDefault("2147483647")
    OptionalInt offsetOptimizingThreshold();

    /**
     * jimmer.defaultListBatchSize
     */
    @WithDefault("false")
    boolean reverseSortOptimizationEnabled();

    /**
     * jimmer.isForeignKeyEnabledByDefault
     */
    @WithDefault("true")
    boolean isForeignKeyEnabledByDefault();

    /**
     * jimmer.maxCommandJoinCount
     */
    @WithDefault("2")
    int maxCommandJoinCount();

    /**
     * jimmer.mutationTransactionRequired
     */
    @WithDefault("false")
    boolean mutationTransactionRequired();

    /**
     * jimmer.targetTransferable
     */
    @WithDefault("false")
    boolean targetTransferable();

    /**
     * jimmer.explicitBatchEnabled
     */
    @WithDefault("false")
    boolean explicitBatchEnabled();

    /**
     * jimmer.dumbBatchAcceptable
     */
    @WithDefault("false")
    boolean dumbBatchAcceptable();

    /**
     * jimmer.constraintViolationTranslatable
     */
    @WithDefault("false")
    boolean constraintViolationTranslatable();

    /**
     * Register the built-in {@code SqlStateExceptionTranslator}, which maps a raw
     * {@code SQLException} to a {@code JimmerDataAccessException} subtype by its {@code SQLState}.
     * Effective only together with {@code constraintViolationTranslatable=false}. Disable to fully
     * own exception translation with your own {@code ExceptionTranslator} beans.
     */
    @WithDefault("true")
    boolean sqlStateExceptionTranslator();

    /**
     * jimmer.executorContextPrefixes
     */
    Optional<List<String>> executorContextPrefixes();

    /**
     * jimmer.defaultTypeChangeAllowed
     */
    @WithDefault("false")
    boolean defaultTypeChangeAllowed();

    /**
     * jimmer.defaultSaveReturningEnabled
     */
    @WithDefault("true")
    boolean defaultSaveReturningEnabled();

    /**
     * jimmer.defaultSaveResultReadsAllProperties
     */
    @WithDefault("false")
    boolean defaultSaveResultReadsAllProperties();

    /**
     * jimmer.jdbc
     */
    Jdbc jdbc();

    @ConfigGroup
    interface Jdbc {

        /**
         * jimmer.jdbc.defaultFetchSize
         */
        OptionalInt defaultFetchSize();

        /**
         * jimmer.jdbc.defaultQueryTimeout
         */
        OptionalInt defaultQueryTimeout();
    }
}
