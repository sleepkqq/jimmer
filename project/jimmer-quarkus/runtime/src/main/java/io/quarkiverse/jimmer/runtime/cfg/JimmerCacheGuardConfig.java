package io.quarkiverse.jimmer.runtime.cfg;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.jimmer.cache.guard")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface JimmerCacheGuardConfig {
    /** Require exactly one CacheReadiness bean and gate every configured cache through it. */
    @WithDefault("false")
    boolean enabled();
}
