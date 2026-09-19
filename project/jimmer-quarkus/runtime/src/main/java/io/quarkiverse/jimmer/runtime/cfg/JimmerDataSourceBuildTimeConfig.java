package io.quarkiverse.jimmer.runtime.cfg;

import org.babyfish.jimmer.sql.event.TriggerType;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.smallrye.config.WithDefault;

@ConfigGroup
public interface JimmerDataSourceBuildTimeConfig {

    /**
     * jimmer.showSql
     */
    @WithDefault("false")
    boolean showSql();

    /**
     * jimmer.prettySql
     */
    @WithDefault("false")
    boolean prettySql();

    /**
     * One-line-per-statement SQL log under the {@code jimmer.sql} logger
     * ({@code SQL SELECT social.users (20 rows) | 12ms [QUERY]}) — a production-friendly
     * alternative to the verbose {@code show-sql} output. Takes precedence over {@code show-sql}.
     */
    @WithDefault("false")
    boolean compactSqlLog();

    /**
     * jimmer.inlineSqlVariables
     */
    @WithDefault("false")
    boolean inlineSqlVariables();

    /**
     * jimmer.triggerType
     */
    @WithDefault("BINLOG_ONLY")
    TriggerType triggerType();
}
