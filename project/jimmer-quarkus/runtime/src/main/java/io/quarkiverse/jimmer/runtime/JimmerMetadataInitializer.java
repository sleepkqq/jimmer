package io.quarkiverse.jimmer.runtime;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

import org.babyfish.jimmer.sql.kt.KSqlClient;
import org.babyfish.jimmer.sql.runtime.JSqlClientImplementor;

import io.quarkiverse.jimmer.runtime.cfg.JimmerRuntimeConfig;
import io.quarkiverse.jimmer.runtime.java.QuarkusJSqlClientContainer;
import io.quarkiverse.jimmer.runtime.java.UnConfiguredDataSourceQuarkusJSqlClientContainer;
import io.quarkiverse.jimmer.runtime.kotlin.QuarkusKSqlClientContainer;
import io.quarkiverse.jimmer.runtime.kotlin.UnConfiguredDataSourceQuarkusKSqlClientContainer;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.runtime.StartupEvent;

/**
 * Eagerly initializes every configured SqlClient on the main thread during startup.
 * <p>
 * The observer priority is below {@code Interceptor.Priority.PLATFORM_BEFORE} on purpose: the
 * Quarkus scheduler starts its own StartupEvent observer at {@code PLATFORM_BEFORE} and interval
 * triggers fire their first execution immediately, so the metadata graph must already be built
 * when the first scheduled job runs. Otherwise a scheduled job racing repository class-init on
 * another thread can stall Jimmer metadata initialization on the static lock guarding lazy
 * target-type resolution ({@code ImmutablePropImpl.META_LOCK}).
 */
@ApplicationScoped
public class JimmerMetadataInitializer {

    private final JimmerRuntimeConfig runtimeConfig;

    public JimmerMetadataInitializer(JimmerRuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE - 100) StartupEvent event) {
        if (!runtimeConfig.eagerMetadataInitialization()) {
            return;
        }
        for (InstanceHandle<QuarkusJSqlClientContainer> handle : Arc.container()
                .listAll(QuarkusJSqlClientContainer.class)) {
            QuarkusJSqlClientContainer container = handle.get();
            if (container instanceof UnConfiguredDataSourceQuarkusJSqlClientContainer) {
                continue;
            }
            ((JSqlClientImplementor) container.getjSqlClient()).getEntityManager();
        }
        for (InstanceHandle<QuarkusKSqlClientContainer> handle : Arc.container()
                .listAll(QuarkusKSqlClientContainer.class)) {
            QuarkusKSqlClientContainer container = handle.get();
            if (container instanceof UnConfiguredDataSourceQuarkusKSqlClientContainer) {
                continue;
            }
            KSqlClient kSqlClient = container.getKSqlClient();
            if (kSqlClient != null) {
                kSqlClient.getJavaClient().getEntityManager();
            }
        }
    }
}
