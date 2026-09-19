package io.quarkiverse.jimmer.deployment;

import org.babyfish.jimmer.jackson.v2.ImmutableModuleV2;

import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.jackson.spi.ClassPathJacksonModuleBuildItem;

final class JimmerJacksonProcessor {

    private static final String JIMMER_JACKSON_MODULE = ImmutableModuleV2.class.getName();

    @BuildStep
    void registerJimmerJacksonModule(BuildProducer<ClassPathJacksonModuleBuildItem> classPathJacksonModules) {
        if (!QuarkusClassLoader.isClassPresentAtRuntime(JIMMER_JACKSON_MODULE)) {
            return;
        }
        classPathJacksonModules.produce(new ClassPathJacksonModuleBuildItem(JIMMER_JACKSON_MODULE));
    }
}
