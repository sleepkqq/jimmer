package io.quarkiverse.jimmer.deployment;

import java.util.List;

import io.quarkiverse.jimmer.runtime.JimmerMetadataInitializer;
import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;

final class JimmerMetadataInitializerProcessor {

    @BuildStep
    void registerMetadataInitializer(List<JdbcDataSourceBuildItem> jdbcDataSourceBuildItems,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        if (jdbcDataSourceBuildItems.isEmpty()) {
            return;
        }
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(JimmerMetadataInitializer.class)
                .build());
    }
}
