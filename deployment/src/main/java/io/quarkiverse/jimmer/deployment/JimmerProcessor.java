package io.quarkiverse.jimmer.deployment;

import java.beans.Introspector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

import jakarta.enterprise.inject.Default;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Priorities;

import org.babyfish.jimmer.Draft;
import org.babyfish.jimmer.Input;
import org.babyfish.jimmer.View;
import org.babyfish.jimmer.error.ClientExceptionMetadata;
import org.babyfish.jimmer.error.CodeBasedException;
import org.babyfish.jimmer.error.CodeBasedRuntimeException;
import org.babyfish.jimmer.jackson.ConverterMetadata;
import org.babyfish.jimmer.meta.impl.Metadata;
import org.babyfish.jimmer.sql.association.meta.AssociationType;
import org.babyfish.jimmer.sql.ast.impl.table.AssociationTableProxyImpl;
import org.babyfish.jimmer.sql.ast.impl.table.TableProxies;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.cache.CacheLoader;
import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.babyfish.jimmer.sql.fetcher.DtoMetadata;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;
import org.babyfish.jimmer.sql.runtime.Customizer;
import org.babyfish.jimmer.sql.runtime.EntityManager;
import org.babyfish.jimmer.sql.runtime.ExceptionTranslator;
import org.babyfish.jimmer.sql.runtime.Executor;
import org.babyfish.jimmer.sql.runtime.Initializer;
import org.babyfish.jimmer.sql.runtime.MicroServiceExchange;
import org.babyfish.jimmer.sql.runtime.ScalarProvider;
import org.babyfish.jimmer.sql.runtime.SqlFormatter;
import org.babyfish.jimmer.sql.di.AopProxyProvider;
import org.babyfish.jimmer.sql.di.LogicalDeletedValueGeneratorProvider;
import org.babyfish.jimmer.sql.di.TransientResolverProvider;
import org.babyfish.jimmer.sql.di.UserIdGeneratorProvider;
import org.babyfish.jimmer.sql.meta.DatabaseNamingStrategy;
import org.babyfish.jimmer.sql.meta.DatabaseSchemaStrategy;
import org.babyfish.jimmer.sql.meta.LogicalDeletedValueGenerator;
import org.babyfish.jimmer.sql.meta.MetaStringResolver;
import org.babyfish.jimmer.sql.meta.UserIdGenerator;
import org.babyfish.jimmer.sql.cache.CacheAbandonedCallback;
import org.babyfish.jimmer.sql.cache.CacheFactory;
import org.babyfish.jimmer.sql.cache.CacheOperator;
import org.babyfish.jimmer.sql.DraftInterceptor;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.MappedSuperclass;
import org.babyfish.jimmer.sql.dialect.Dialect;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.dialect.MySqlDialect;
import org.babyfish.jimmer.sql.dialect.OracleDialect;
import org.babyfish.jimmer.sql.dialect.PostgresDialect;
import org.babyfish.jimmer.sql.dialect.SqlServerDialect;
import org.babyfish.jimmer.sql.dialect.TiDBDialect;
import org.babyfish.jimmer.sql.meta.UUIDIdGenerator;
import io.quarkiverse.jimmer.runtime.generator.UUIDv7IdGenerator;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.babyfish.jimmer.sql.ast.table.Table;
import org.babyfish.jimmer.sql.TransientResolver;
import org.babyfish.jimmer.sql.TypedTransientResolver;
import org.babyfish.jimmer.sql.cache.PropCacheInvalidator;
import org.babyfish.jimmer.sql.cache.TransactionCacheOperator;
import org.babyfish.jimmer.sql.cache.caffeine.CaffeineHashBinder;
import org.babyfish.jimmer.sql.cache.caffeine.CaffeineValueBinder;
import org.babyfish.jimmer.sql.event.AssociationEvent;
import org.babyfish.jimmer.sql.event.EntityEvent;
import org.babyfish.jimmer.sql.event.TriggerType;
import org.babyfish.jimmer.sql.filter.CacheableFilter;
import org.babyfish.jimmer.sql.filter.AssociationIntegrityAssuranceFilter;
import org.babyfish.jimmer.sql.filter.impl.FilterWrapper;
import org.babyfish.jimmer.sql.filter.Filter;
import org.babyfish.jimmer.sql.filter.ShardingCacheableFilter;
import org.babyfish.jimmer.sql.filter.ShardingFilter;
import org.babyfish.jimmer.sql.fetcher.Fetcher;
import org.babyfish.jimmer.sql.kt.KSqlClient;
import org.babyfish.jimmer.sql.kt.cfg.KCustomizer;
import org.babyfish.jimmer.sql.kt.cfg.KInitializer;
import org.babyfish.jimmer.sql.kt.filter.KFilter;
import org.babyfish.jimmer.sql.kt.filter.KCacheableFilter;
import org.babyfish.jimmer.sql.kt.filter.KShardingFilter;
import org.babyfish.jimmer.sql.kt.filter.KAssociationIntegrityAssuranceFilter;
import org.jboss.jandex.*;
import org.jboss.logging.Logger;

import io.quarkiverse.jimmer.deployment.bytecode.JimmerRepositoryFactory;
import io.quarkiverse.jimmer.runtime.*;
import io.quarkiverse.jimmer.runtime.QuarkusSqlClientProducer;
import io.quarkiverse.jimmer.runtime.cache.JimmerLocalCacheProducer;
import io.quarkiverse.jimmer.runtime.cache.JimmerRedisCacheProducer;
import io.quarkiverse.jimmer.runtime.cache.QuarkusRedisCacheTracker;
import io.quarkiverse.jimmer.runtime.cache.impl.TransactionCacheOperatorFlusher;
import io.quarkiverse.jimmer.runtime.cfg.JimmerBuildTimeConfig;
import io.quarkiverse.jimmer.runtime.client.CodeBasedExceptionAdvice;
import io.quarkiverse.jimmer.runtime.client.CodeBasedRuntimeExceptionAdvice;
import io.quarkiverse.jimmer.runtime.client.openapi.CssRecorder;
import io.quarkiverse.jimmer.runtime.client.openapi.JsRecorder;
import io.quarkiverse.jimmer.runtime.client.openapi.OpenApiRecorder;
import io.quarkiverse.jimmer.runtime.client.openapi.OpenApiUiRecorder;
import io.quarkiverse.jimmer.runtime.client.ts.TypeScriptRecorder;
import io.quarkiverse.jimmer.runtime.cloud.ExchangeRestClient;
import io.quarkiverse.jimmer.runtime.cloud.MicroServiceExporterAssociatedIdsRecorder;
import io.quarkiverse.jimmer.runtime.cloud.MicroServiceExporterIdsRecorder;
import io.quarkiverse.jimmer.runtime.cloud.QuarkusExchange;
import io.quarkiverse.jimmer.runtime.dialect.DialectDetector;
import io.quarkiverse.jimmer.runtime.java.QuarkusJSqlClientContainer;
import io.quarkiverse.jimmer.runtime.kotlin.QuarkusKSqlClientContainer;
import io.quarkiverse.jimmer.runtime.repo.RepoRecord;
import io.quarkiverse.jimmer.runtime.repo.support.AbstractJavaRepository;
import io.quarkiverse.jimmer.runtime.repo.support.AbstractKotlinRepository;
import io.quarkiverse.jimmer.runtime.repository.*;
import io.quarkiverse.jimmer.runtime.repository.support.JRepositoryImpl;
import io.quarkiverse.jimmer.runtime.repository.support.KRepositoryImpl;
import io.quarkiverse.jimmer.runtime.util.Constant;
import io.quarkus.agroal.DataSource;
import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.arc.deployment.*;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.*;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.*;
import io.quarkus.deployment.builditem.nativeimage.LambdaCapturingTypeBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyIgnoreWarningBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.deployment.util.JandexUtil;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.resteasy.reactive.spi.ExceptionMapperBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.runtime.management.ManagementInterfaceBuildTimeConfig;

@BuildSteps(onlyIf = JimmerProcessor.JimmerEnable.class)
final class JimmerProcessor {

    private static final Logger log = Logger.getLogger(JimmerProcessor.class);

    private static final String FEATURE = "jimmer";

    private static final String JIMMER_CONTAINER_BEAN_NAME_PREFIX = "jimmer_container_";

    private static final List<DotName> ENTITY_ANNOTATIONS = List.of(
            DotName.createSimple(Entity.class),
            DotName.createSimple(MappedSuperclass.class));

    private static final Set<DotName> IMPLEMENTOR_TYPES = Set.of(
            DotName.createSimple(View.class.getName()),
            DotName.createSimple(Input.class.getName()),
            DotName.createSimple(Draft.class.getName()),
            DotName.createSimple(Table.class.getName()),
            DotName.createSimple(Fetcher.class.getName()));

    // Whole Jimmer SQL annotation surface (incl. the nested @JoinTable filters) kept for kotlin-reflect,
    // which resolves every annotation member type when building entity metadata at run time. Referenced
    // by .class (not strings) so a renamed/moved annotation breaks the build, not the native image.
    private static final Class<?>[] JIMMER_SQL_ANNOTATIONS = {
            org.babyfish.jimmer.sql.Column.class,
            org.babyfish.jimmer.sql.Default.class,
            org.babyfish.jimmer.sql.Embeddable.class,
            org.babyfish.jimmer.sql.Entity.class,
            org.babyfish.jimmer.sql.EnumItem.class,
            org.babyfish.jimmer.sql.EnumType.class,
            org.babyfish.jimmer.sql.GeneratedValue.class,
            org.babyfish.jimmer.sql.Id.class,
            org.babyfish.jimmer.sql.IdView.class,
            org.babyfish.jimmer.sql.JoinColumn.class,
            org.babyfish.jimmer.sql.JoinColumns.class,
            org.babyfish.jimmer.sql.JoinSql.class,
            org.babyfish.jimmer.sql.JoinTable.class,
            org.babyfish.jimmer.sql.JoinTable.JoinTableFilter.class,
            org.babyfish.jimmer.sql.JoinTable.LogicalDeletedFilter.class,
            org.babyfish.jimmer.sql.Key.class,
            org.babyfish.jimmer.sql.Keys.class,
            org.babyfish.jimmer.sql.KeyUniqueConstraint.class,
            org.babyfish.jimmer.sql.LogicalDeleted.class,
            org.babyfish.jimmer.sql.ManyToMany.class,
            org.babyfish.jimmer.sql.ManyToManyView.class,
            org.babyfish.jimmer.sql.ManyToOne.class,
            org.babyfish.jimmer.sql.MappedSuperclass.class,
            org.babyfish.jimmer.sql.MapsId.class,
            org.babyfish.jimmer.sql.OnDissociate.class,
            org.babyfish.jimmer.sql.OneToMany.class,
            org.babyfish.jimmer.sql.OneToOne.class,
            org.babyfish.jimmer.sql.OrderedProp.class,
            org.babyfish.jimmer.sql.PropOverride.class,
            org.babyfish.jimmer.sql.PropOverrides.class,
            org.babyfish.jimmer.sql.Serialized.class,
            org.babyfish.jimmer.sql.Table.class,
            org.babyfish.jimmer.sql.Transient.class,
            org.babyfish.jimmer.sql.Version.class };

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    IgnoreSplitPackageBuildItem splitPackages() {
        return new IgnoreSplitPackageBuildItem(List.of("org.babyfish.jimmer", "org.babyfish.jimmer.sql"));
    }

    @BuildStep
    ReflectiveHierarchyIgnoreWarningBuildItem ignoreJackson3ReflectionWarnings() {
        // Jimmer bundles Jackson 3 support classes in the same artifacts as the Jackson 2 runtime used by Quarkus.
        // When Quarkus scans Jackson 2 annotations, it can reach those unused Jackson 3 signatures and warn.
        return new ReflectiveHierarchyIgnoreWarningBuildItem(dotName -> dotName.toString().startsWith("tools.jackson."));
    }

    @BuildStep
    AnnotationsTransformerBuildItem transform(CustomScopeAnnotationsBuildItem customScopes) {
        return new AnnotationsTransformerBuildItem(AnnotationTransformation.forClasses()
                .whenClass(classInfo -> classInfo.interfaceNames().contains(
                        DotName.createSimple(TransientResolver.class.getName())) && customScopes.isScopeDeclaredOn(classInfo))
                .transform(transformationContext -> {
                    transformationContext.add(AnnotationInstance.builder(Named.class)
                            .add(AnnotationValue.createStringValue("value",
                                    Introspector.decapitalize(transformationContext.declaration().asClass().simpleName())))
                            .build());
                }));
    }

    @BuildStep
    void setUpExceptionMapper(JimmerBuildTimeConfig buildTimeConfig,
            BuildProducer<ExceptionMapperBuildItem> exceptionMapperProducer) {
        if (buildTimeConfig.errorTranslator().isPresent()) {
            if (!buildTimeConfig.errorTranslator().get().disabled()) {
                exceptionMapperProducer.produce(new ExceptionMapperBuildItem(CodeBasedExceptionAdvice.class.getName(),
                        CodeBasedException.class.getName(), Priorities.USER + 1, true));
                exceptionMapperProducer.produce(new ExceptionMapperBuildItem(CodeBasedRuntimeExceptionAdvice.class.getName(),
                        CodeBasedRuntimeException.class.getName(), Priorities.USER + 1, true));
            }
        }
    }

    /**
     * Jimmer SPI beans are looked up reflectively via ArcContainer.listAll()/instance() when the
     * SqlClient is built (see JQuarkusSqlClient#createBuilder), so ArC's build-time removal sees no
     * injection point for them and would silently drop user-defined beans such as an
     * ExceptionTranslator or DraftInterceptor that is not injected anywhere else.
     */
    @BuildStep
    void unremovableJimmerSpiBeans(BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {
        unremovableBeans.produce(UnremovableBeanBuildItem.beanTypes(
                ExceptionTranslator.class,
                DraftInterceptor.class,
                ScalarProvider.class,
                Filter.class,
                KFilter.class,
                Customizer.class,
                KCustomizer.class,
                Initializer.class,
                KInitializer.class,
                CacheAbandonedCallback.class,
                CacheFactory.class,
                CacheOperator.class,
                ConnectionManager.class,
                Dialect.class,
                DialectDetector.class,
                Executor.class,
                SqlFormatter.class,
                EntityManager.class,
                MetaStringResolver.class,
                DatabaseSchemaStrategy.class,
                DatabaseNamingStrategy.class,
                UserIdGenerator.class,
                UserIdGeneratorProvider.class,
                LogicalDeletedValueGenerator.class,
                LogicalDeletedValueGeneratorProvider.class,
                TransientResolver.class,
                TransientResolverProvider.class,
                AopProxyProvider.class,
                MicroServiceExchange.class));

        // Consumer<JSqlClient.Builder> beans (see Constant.J_SQL_CLIENT_BUILDER_TYPE_LITERAL) cannot be
        // matched by raw type without keeping every Consumer bean alive, so match the exact type argument.
        DotName consumerName = DotName.createSimple(Consumer.class);
        DotName builderName = DotName.createSimple(JSqlClient.Builder.class);
        unremovableBeans.produce(new UnremovableBeanBuildItem(beanInfo -> beanInfo.getTypes().stream()
                .anyMatch(type -> type.kind() == Type.Kind.PARAMETERIZED_TYPE
                        && type.name().equals(consumerName)
                        && type.asParameterizedType().arguments().size() == 1
                        && type.asParameterizedType().arguments().getFirst().name().equals(builderName))));
    }

    /**
     * Jimmer translates weak-join lambdas into SQL at runtime: it extracts the
     * SerializedLambda via the synthetic writeReplace method and re-reads the byte code
     * of the lambda's declaring class (SerializedLambda#getImplClass) with ASM. In a
     * native image both steps fail unless the declaring class is registered as a
     * lambda-capturing type for serialization and its .class file is kept as a resource,
     * which surfaces as "The argument `weakJoinFun` must be lambda".
     */
    @BuildStep
    void registerWeakJoinLambdaClasses(ApplicationArchivesBuildItem applicationArchives,
            BuildProducer<NativeImageResourceBuildItem> nativeResources,
            BuildProducer<LambdaCapturingTypeBuildItem> lambdaCapturingTypes) {
        byte[][] markers = new byte[][] {
                "KWeakJoinFun".getBytes(StandardCharsets.UTF_8),
                "KPropsWeakJoinFun".getBytes(StandardCharsets.UTF_8),
                "org/babyfish/jimmer/sql/ast/table/WeakJoin".getBytes(StandardCharsets.UTF_8)
        };
        Set<String> registered = new HashSet<>();
        for (ApplicationArchive archive : applicationArchives.getAllArchives()) {
            archive.accept(tree -> tree.walk(visit -> {
                String relativePath = visit.getRelativePath("/");
                // Only user code can declare weak-join lambdas; Jimmer's own classes merely
                // reference the marker types in their signatures and would be registered as
                // lambda-capturing for nothing (GraalVM warns per class).
                if (!relativePath.endsWith(".class")
                        || relativePath.startsWith("org/babyfish/jimmer/")
                        || !registered.add(relativePath)) {
                    return;
                }
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(visit.getPath());
                } catch (IOException ex) {
                    return;
                }
                if (containsAnyMarker(bytes, markers)) {
                    nativeResources.produce(new NativeImageResourceBuildItem(relativePath));
                    String className = relativePath
                            .substring(0, relativePath.length() - ".class".length())
                            .replace('/', '.');
                    lambdaCapturingTypes.produce(new LambdaCapturingTypeBuildItem(className));
                }
            }));
        }
    }

    private static boolean containsAnyMarker(byte[] bytes, byte[][] markers) {
        for (byte[] marker : markers) {
            outer: for (int i = 0; i <= bytes.length - marker.length; i++) {
                for (int j = 0; j < marker.length; j++) {
                    if (bytes[i + j] != marker[j]) {
                        continue outer;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @BuildStep
    void checkTransactionsSupport(Capabilities capabilities,
            BuildProducer<ValidationPhaseBuildItem.ValidationErrorBuildItem> validationErrors) {
        // JTA is necessary for Jimmer
        if (capabilities.isMissing(Capability.TRANSACTIONS)) {
            validationErrors.produce(new ValidationPhaseBuildItem.ValidationErrorBuildItem(
                    new ConfigurationException("The Jimmer extension is only functional in a JTA environment.")));
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void verifyConfig(@SuppressWarnings("unused") JimmerDataSourcesRecorder recorder, JimmerBuildTimeConfig buildTimeConfig,
            List<JdbcDataSourceBuildItem> jdbcDataSourceBuildItems) {
        if (!jdbcDataSourceBuildItems.isEmpty()) {
            for (JdbcDataSourceBuildItem jdbcDataSourceBuildItem : jdbcDataSourceBuildItems) {
                String dataSourceName = jdbcDataSourceBuildItem.getName();
                if (buildTimeConfig.dataSources().get(dataSourceName).prettySql()
                        && !buildTimeConfig.dataSources().get(dataSourceName).showSql()) {
                    throw new IllegalArgumentException(
                            "When `pretty-sql` is true, `show-sql` must be true");
                }
                if (buildTimeConfig.dataSources().get(dataSourceName).inlineSqlVariables()
                        && !buildTimeConfig.dataSources().get(dataSourceName).prettySql()) {
                    throw new IllegalArgumentException(
                            "When `inline-sql-variables` is true, `pretty-sql` must be true");
                }
                if (buildTimeConfig.client().ts().path().isPresent()) {
                    if (!buildTimeConfig.client().ts().path().get().startsWith("/")) {
                        throw new IllegalArgumentException("`jimmer.client.ts.path` must start with \"/\"");
                    }
                }
            }
        }
    }

    @BuildStep(onlyIf = IsMicroServiceEnable.class)
    @Record(ExecutionTime.STATIC_INIT)
    void setUpMicroService(BuildProducer<RouteBuildItem> routes,
            LaunchModeBuildItem launchModeBuildItem,
            BuildProducer<RegistryBuildItem> registries,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            MicroServiceExporterIdsRecorder microServiceExporterIdsRecorder,
            MicroServiceExporterAssociatedIdsRecorder microServiceExporterAssociatedIdsRecorder,
            NonApplicationRootPathBuildItem nonApplicationRootPathBuildItem,
            ManagementInterfaceBuildTimeConfig managementInterfaceBuildTimeConfig,
            BuildProducer<AdditionalIndexedClassesBuildItem> additionalIndexedClassesBuildItem) {

        routes.produce(nonApplicationRootPathBuildItem.routeBuilder()
                .management()
                .routeFunction(Constant.BY_IDS, microServiceExporterIdsRecorder.route())
                .handler(microServiceExporterIdsRecorder.getHandler())
                .blockingRoute()
                .build());

        String microServiceExporterIdsPath = nonApplicationRootPathBuildItem.resolveManagementPath(
                Constant.BY_IDS,
                managementInterfaceBuildTimeConfig, launchModeBuildItem);
        log.debug(
                "Initialized a Jimmer microServiceExporterIdsPath meter registry on path = " + microServiceExporterIdsPath);

        registries.produce(new RegistryBuildItem("microServiceExporterIdsPath", microServiceExporterIdsPath));

        routes.produce(nonApplicationRootPathBuildItem.routeBuilder()
                .management()
                .routeFunction(Constant.BY_ASSOCIATED_IDS, microServiceExporterAssociatedIdsRecorder.route())
                .handler(microServiceExporterAssociatedIdsRecorder.getHandler())
                .blockingRoute()
                .build());

        String microServiceExporterAssociatedIdsPath = nonApplicationRootPathBuildItem.resolveManagementPath(
                Constant.BY_ASSOCIATED_IDS,
                managementInterfaceBuildTimeConfig, launchModeBuildItem);
        log.debug("Initialized a Jimmer microServiceExporterAssociatedPath meter registry on path = "
                + microServiceExporterAssociatedIdsPath);

        registries.produce(
                new RegistryBuildItem("microServiceExporterAssociatedPath", microServiceExporterAssociatedIdsPath));

        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(QuarkusExchange.class));

        additionalIndexedClassesBuildItem
                .produce(new AdditionalIndexedClassesBuildItem(ExchangeRestClient.class.getName()));
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void initializeResourceRegistry(TypeScriptRecorder typeScriptRecorder,
            CssRecorder cssRecorder,
            JsRecorder jsRecorder,
            OpenApiRecorder openApiRecorder,
            OpenApiUiRecorder openApiUiRecorder,
            BuildProducer<RouteBuildItem> routes,
            NonApplicationRootPathBuildItem nonApplicationRootPathBuildItem,
            ManagementInterfaceBuildTimeConfig managementInterfaceBuildTimeConfig,
            LaunchModeBuildItem launchModeBuildItem,
            JimmerBuildTimeConfig buildTimeConfig,
            BuildProducer<RegistryBuildItem> registries) {
        if (buildTimeConfig.client().ts().path().isPresent()) {
            routes.produce(nonApplicationRootPathBuildItem.routeBuilder()
                    .management()
                    .routeFunction(buildTimeConfig.client().ts().path().get(), typeScriptRecorder.route())
                    .routeConfigKey("quarkus.jimmer.client.ts.path")
                    .handler(typeScriptRecorder.getHandler())
                    .blockingRoute()
                    .build());

            String tsPath = nonApplicationRootPathBuildItem.resolveManagementPath(buildTimeConfig.client().ts().path().get(),
                    managementInterfaceBuildTimeConfig, launchModeBuildItem);
            log.debug("Initialized a Jimmer TypeScript meter registry on path = " + tsPath);

            registries.produce(new RegistryBuildItem("TypeScriptResource", tsPath));
        }

        routes.produce(nonApplicationRootPathBuildItem.routeBuilder()
                .management()
                .routeFunction(Constant.CSS_URL, cssRecorder.route())
                .handler(cssRecorder.getHandler())
                .blockingRoute()
                .build());

        String cssPath = nonApplicationRootPathBuildItem.resolveManagementPath(Constant.CSS_URL,
                managementInterfaceBuildTimeConfig, launchModeBuildItem);
        log.debug("Initialized a Jimmer CSS meter registry on path = " + cssPath);

        routes.produce(nonApplicationRootPathBuildItem.routeBuilder()
                .management()
                .routeFunction(Constant.JS_URL, jsRecorder.route())
                .handler(jsRecorder.getHandler())
                .blockingRoute()
                .build());

        String jsPath = nonApplicationRootPathBuildItem.resolveManagementPath(Constant.JS_URL,
                managementInterfaceBuildTimeConfig, launchModeBuildItem);
        log.debug("Initialized a Jimmer JS meter registry on path = " + jsPath);

        routes.produce(nonApplicationRootPathBuildItem.routeBuilder()
                .management()
                .routeFunction(buildTimeConfig.client().openapi().path(), openApiRecorder.route())
                .routeConfigKey("quarkus.jimmer.client.openapi.path")
                .handler(openApiRecorder.getHandler())
                .blockingRoute()
                .build());

        String openapiPath = nonApplicationRootPathBuildItem.resolveManagementPath(buildTimeConfig.client().openapi().path(),
                managementInterfaceBuildTimeConfig, launchModeBuildItem);
        log.debug("Initialized a Jimmer OpenApi meter registry on path = " + openapiPath);

        registries.produce(new RegistryBuildItem("OpenApiResource", openapiPath));

        routes.produce(nonApplicationRootPathBuildItem.routeBuilder()
                .management()
                .routeFunction(buildTimeConfig.client().openapi().uiPath(), openApiUiRecorder.route())
                .routeConfigKey("quarkus.jimmer.client.openapi.ui-path")
                .handler(openApiUiRecorder.getHandler())
                .blockingRoute()
                .build());

        String uiPath = nonApplicationRootPathBuildItem.resolveManagementPath(buildTimeConfig.client().openapi().uiPath(),
                managementInterfaceBuildTimeConfig, launchModeBuildItem);
        log.debug("Initialized a Jimmer OpenApiUi meter registry on path = " + uiPath);

        registries.produce(new RegistryBuildItem("OpenApiUiResource", uiPath));
    }

    @BuildStep(onlyIf = IsJavaEnable.class)
    void contributeJRepositoryToIndex(BuildProducer<AdditionalIndexedClassesBuildItem> additionalIndexedClasses) {
        additionalIndexedClasses
                .produce(new AdditionalIndexedClassesBuildItem(JRepository.class.getName(), JRepositoryImpl.class.getName()));
    }

    @BuildStep(onlyIf = IsKotlinEnable.class)
    void contributeKRepositoryToIndex(BuildProducer<AdditionalIndexedClassesBuildItem> additionalIndexedClasses) {
        additionalIndexedClasses
                .produce(new AdditionalIndexedClassesBuildItem(KRepository.class.getName(), KRepositoryImpl.class.getName()));
    }

    @BuildStep
    void collectRepositoryMetadata(CombinedIndexBuildItem combinedIndex,
            BuildProducer<RepositoryMetadata> repositoryMetadataBuildProducer) {
        Collection<ClassInfo> jRepositoryInterfaces = combinedIndex.getIndex().getAllKnownSubinterfaces(JRepository.class);
        for (ClassInfo repositoryInterface : jRepositoryInterfaces) {
            Optional<AnnotationInstance> mapperDatasource = repositoryInterface.asClass().annotationsMap().entrySet().stream()
                    .filter(entry -> entry.getKey().equals(DotName.createSimple(DataSource.class)))
                    .map(Map.Entry::getValue)
                    .map(annotationList -> annotationList.get(0))
                    .findFirst();
            if (mapperDatasource.isPresent()) {
                String dataSourceName = mapperDatasource.get().value().asString();
                List<Type> typeParameters = JandexUtil.resolveTypeParameters(repositoryInterface.name(),
                        DotName.createSimple(JRepository.class), combinedIndex.getIndex());
                repositoryMetadataBuildProducer
                        .produce(new RepositoryMetadata(JandexReflection.loadRawType(typeParameters.get(0)),
                                JandexReflection.loadClass(repositoryInterface), dataSourceName));
            } else {
                List<Type> typeParameters = JandexUtil.resolveTypeParameters(repositoryInterface.name(),
                        DotName.createSimple(JRepository.class), combinedIndex.getIndex());
                repositoryMetadataBuildProducer
                        .produce(new RepositoryMetadata(JandexReflection.loadRawType(typeParameters.get(0)),
                                JandexReflection.loadClass(repositoryInterface), DataSourceUtil.DEFAULT_DATASOURCE_NAME));
            }
        }
        Collection<ClassInfo> kRepositoryInterfaces = combinedIndex.getIndex().getAllKnownSubinterfaces(KRepository.class);
        for (ClassInfo repositoryInterface : kRepositoryInterfaces) {
            Optional<AnnotationInstance> mapperDatasource = repositoryInterface.asClass().annotationsMap().entrySet().stream()
                    .filter(entry -> entry.getKey().equals(DotName.createSimple(DataSource.class)))
                    .map(Map.Entry::getValue)
                    .map(annotationList -> annotationList.get(0))
                    .findFirst();
            if (mapperDatasource.isPresent()) {
                String dataSourceName = mapperDatasource.get().value().asString();
                List<Type> typeParameters = JandexUtil.resolveTypeParameters(repositoryInterface.name(),
                        DotName.createSimple(KRepository.class), combinedIndex.getIndex());
                repositoryMetadataBuildProducer
                        .produce(new RepositoryMetadata(JandexReflection.loadRawType(typeParameters.get(0)),
                                JandexReflection.loadClass(repositoryInterface), dataSourceName));
            } else {
                List<Type> typeParameters = JandexUtil.resolveTypeParameters(repositoryInterface.name(),
                        DotName.createSimple(KRepository.class), combinedIndex.getIndex());
                repositoryMetadataBuildProducer
                        .produce(new RepositoryMetadata(JandexReflection.loadRawType(typeParameters.get(0)),
                                JandexReflection.loadClass(repositoryInterface), DataSourceUtil.DEFAULT_DATASOURCE_NAME));
            }
        }
    }

    @BuildStep(onlyIf = IsJavaEnable.class)
    @Record(ExecutionTime.STATIC_INIT)
    void analyzeJavaRepository(@SuppressWarnings("unused") RepoRecord repoRecord,
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeanProducer,
            BuildProducer<EntityToClassBuildItem> entityToClassProducer) {
        Collection<ClassInfo> repositoryBeans = combinedIndex.getIndex()
                .getAllKnownSubclasses(AbstractJavaRepository.class);
        for (ClassInfo repositoryBean : repositoryBeans) {
            unremovableBeanProducer.produce(UnremovableBeanBuildItem.beanTypes(repositoryBean.name()));

            List<Type> typeParameters = JandexUtil.resolveTypeParameters(repositoryBean.asClass().name(),
                    DotName.createSimple(AbstractJavaRepository.class), combinedIndex.getComputingIndex());
            entityToClassProducer.produce(new EntityToClassBuildItem(repositoryBean.asClass().name().toString(),
                    JandexReflection.loadRawType(typeParameters.get(0))));
        }
    }

    @BuildStep(onlyIf = IsKotlinEnable.class)
    @Record(ExecutionTime.STATIC_INIT)
    void analyzeKotlinRepository(@SuppressWarnings("unused") RepoRecord repoRecord,
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeanProducer,
            BuildProducer<EntityToClassBuildItem> entityToClassProducer) {
        Collection<ClassInfo> repositoryBeans = combinedIndex.getIndex()
                .getAllKnownSubclasses(AbstractKotlinRepository.class);
        for (ClassInfo repositoryBean : repositoryBeans) {
            unremovableBeanProducer.produce(UnremovableBeanBuildItem.beanTypes(repositoryBean.name()));

            List<Type> typeParameters = JandexUtil.resolveTypeParameters(repositoryBean.asClass().name(),
                    DotName.createSimple(AbstractKotlinRepository.class), combinedIndex.getComputingIndex());
            entityToClassProducer.produce(new EntityToClassBuildItem(repositoryBean.asClass().name().toString(),
                    JandexReflection.loadRawType(typeParameters.get(0))));
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void recordRepoOperationsData(RepoRecord repoRecord,
            List<EntityToClassBuildItem> entityToClassBuildItems) {
        Map<String, Class<?>> map = new HashMap<>();
        for (EntityToClassBuildItem entityToClassBuildItem : entityToClassBuildItems) {
            map.put(entityToClassBuildItem.getEntityClass(), entityToClassBuildItem.getClazz());
        }
        repoRecord.setEntityToClassUnit(map);
    }

    @BuildStep(onlyIf = IsJavaEnable.class)
    @Record(ExecutionTime.STATIC_INIT)
    void setTransactionJCacheOperatorBean(JimmerTransactionCacheOperatorRecorder recorder,
            JimmerBuildTimeConfig buildTimeConfig,
            List<JdbcDataSourceBuildItem> jdbcDataSourceBuildItems,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer) {
        if (jdbcDataSourceBuildItems.isEmpty()) {
            return;
        }

        boolean transactionCacheOperatorFlusherFlag = jdbcDataSourceBuildItems.stream()
                .anyMatch(x -> !buildTimeConfig.dataSources().get(x.getName()).triggerType().equals(TriggerType.BINLOG_ONLY));
        if (transactionCacheOperatorFlusherFlag) {
            AdditionalBeanBuildItem.Builder builder = AdditionalBeanBuildItem.builder().setUnremovable();
            builder.addBeanClass(TransactionCacheOperatorFlusher.class);
            additionalBeans.produce(builder.build());
        }

        for (JdbcDataSourceBuildItem jdbcDataSourceBuildItem : jdbcDataSourceBuildItems) {
            String dataSourceName = jdbcDataSourceBuildItem.getName();
            if (!buildTimeConfig.dataSources().get(dataSourceName).triggerType().equals(TriggerType.BINLOG_ONLY)) {
                SyntheticBeanBuildItem.ExtendedBeanConfigurator transactionCacheOperatorConfigurator = SyntheticBeanBuildItem
                        .configure(TransactionCacheOperator.class)
                        .scope(Singleton.class)
                        .unremovable()
                        .createWith(recorder.transactionJCacheOperatorFunction(dataSourceName));

                if (DataSourceUtil.isDefault(dataSourceName)) {
                    transactionCacheOperatorConfigurator.addQualifier().annotation(DataSource.class)
                            .addValue("value", dataSourceName).done();

                    transactionCacheOperatorConfigurator.priority(10);

                } else {
                    transactionCacheOperatorConfigurator.addQualifier().annotation(DataSource.class)
                            .addValue("value", dataSourceName).done();

                    transactionCacheOperatorConfigurator.priority(5);
                }

                syntheticBeanBuildItemBuildProducer.produce(transactionCacheOperatorConfigurator.done());
            }
        }
    }

    @BuildStep(onlyIf = IsKotlinEnable.class)
    @Record(ExecutionTime.STATIC_INIT)
    void setTransactionKCacheOperatorBean(JimmerTransactionCacheOperatorRecorder recorder,
            JimmerBuildTimeConfig buildTimeConfig,
            List<JdbcDataSourceBuildItem> jdbcDataSourceBuildItems,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer) {
        if (jdbcDataSourceBuildItems.isEmpty()) {
            return;
        }

        boolean transactionCacheOperatorFlusherFlag = jdbcDataSourceBuildItems.stream()
                .anyMatch(x -> !buildTimeConfig.dataSources().get(x.getName()).triggerType().equals(TriggerType.BINLOG_ONLY));
        if (transactionCacheOperatorFlusherFlag) {
            AdditionalBeanBuildItem.Builder builder = AdditionalBeanBuildItem.builder().setUnremovable();
            builder.addBeanClass(TransactionCacheOperatorFlusher.class);
            additionalBeans.produce(builder.build());
        }

        for (JdbcDataSourceBuildItem jdbcDataSourceBuildItem : jdbcDataSourceBuildItems) {
            String dataSourceName = jdbcDataSourceBuildItem.getName();
            if (!buildTimeConfig.dataSources().get(dataSourceName).triggerType().equals(TriggerType.BINLOG_ONLY)) {
                SyntheticBeanBuildItem.ExtendedBeanConfigurator transactionCacheOperatorConfigurator = SyntheticBeanBuildItem
                        .configure(TransactionCacheOperator.class)
                        .scope(Singleton.class)
                        .unremovable()
                        .createWith(recorder.transactionKCacheOperatorFunction(dataSourceName));

                if (DataSourceUtil.isDefault(dataSourceName)) {
                    transactionCacheOperatorConfigurator.addQualifier().annotation(DataSource.class)
                            .addValue("value", dataSourceName).done();

                    transactionCacheOperatorConfigurator.priority(10);

                } else {
                    transactionCacheOperatorConfigurator.addQualifier().annotation(DataSource.class)
                            .addValue("value", dataSourceName).done();

                    transactionCacheOperatorConfigurator.priority(5);
                }

                syntheticBeanBuildItemBuildProducer.produce(transactionCacheOperatorConfigurator.done());
            }
        }
    }

    @BuildStep
    void registerJimmerCache(
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeans,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        unremovableBeans.produce(UnremovableBeanBuildItem.beanTypes(CacheFactory.class));

        // Optional-capability gate: the string is deliberate — a class reference would force a
        // hard deployment dependency on quarkus-redis-client.
        if (!QuarkusClassLoader.isClassPresentAtRuntime("io.quarkus.redis.datasource.RedisDataSource")) {
            // No Redis: LOCAL_ONLY-only factory (per-instance Caffeine, no pub/sub invalidation).
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .setUnremovable()
                    .setDefaultScope(DotNames.SINGLETON)
                    .addBeanClass(JimmerLocalCacheProducer.class)
                    .build());
            return;
        }

        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .setDefaultScope(DotNames.SINGLETON)
                .addBeanClass(JimmerRedisCacheProducer.class)
                .build());
        unremovableBeans.produce(UnremovableBeanBuildItem.beanTypes(CacheTracker.class));

        // The tracker's pub/sub message is (de)serialized by Jackson via field reflection —
        // register it so the channel keeps working in a native image.
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                QuarkusRedisCacheTracker.InvalidationMessage.class)
                .constructors().fields().methods().build());
    }

    @BuildStep(onlyIf = IsJavaEnable.class)
    @Produce(SyntheticBeansRuntimeInitBuildItem.class)
    @Consume(LoggingSetupBuildItem.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    void generateJSqlClientBeans(JimmerDataSourcesRecorder recorder,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer,
            List<JdbcDataSourceBuildItem> jdbcDataSourceBuildItems,
            BuildProducer<SqlClientBuildItem> sqlClientBuildItemBuildItem) {
        if (jdbcDataSourceBuildItems.isEmpty()) {
            return;
        }

        additionalBeans.produce(new AdditionalBeanBuildItem(JSqlClient.class));

        additionalBeans
                .produce(AdditionalBeanBuildItem.builder().addBeanClasses(QuarkusSqlClientProducer.class).setUnremovable()
                        .setDefaultScope(DotNames.SINGLETON).build());

        for (JdbcDataSourceBuildItem jdbcDataSourceBuildItem : jdbcDataSourceBuildItems) {
            String dataSourceName = jdbcDataSourceBuildItem.getName();

            SyntheticBeanBuildItem.ExtendedBeanConfigurator quarkusJSqlClientContainerConfigurator = SyntheticBeanBuildItem
                    .configure(QuarkusJSqlClientContainer.class)
                    .scope(Singleton.class)
                    .setRuntimeInit()
                    .unremovable()
                    .addInjectionPoint(ClassType.create(DotName.createSimple(QuarkusSqlClientProducer.class)))
                    .createWith(recorder.jSqlClientContainerFunction(dataSourceName));

            AnnotationInstance quarkusJSqlClientContainerQualifier;

            if (DataSourceUtil.isDefault(dataSourceName)) {
                quarkusJSqlClientContainerConfigurator.addQualifier(Default.class);

                quarkusJSqlClientContainerConfigurator.priority(10);

                quarkusJSqlClientContainerQualifier = AnnotationInstance.builder(Default.class).build();
            } else {
                String beanName = JIMMER_CONTAINER_BEAN_NAME_PREFIX + dataSourceName;
                quarkusJSqlClientContainerConfigurator.name(beanName);

                quarkusJSqlClientContainerConfigurator.addQualifier().annotation(DotNames.NAMED).addValue("value", beanName)
                        .done();
                quarkusJSqlClientContainerConfigurator.addQualifier().annotation(DataSource.class)
                        .addValue("value", dataSourceName).done();
                quarkusJSqlClientContainerConfigurator.priority(5);

                quarkusJSqlClientContainerQualifier = AnnotationInstance.builder(DataSource.class).add("value", dataSourceName)
                        .build();
            }

            syntheticBeanBuildItemBuildProducer.produce(quarkusJSqlClientContainerConfigurator.done());

            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(JSqlClient.class)
                    .scope(Singleton.class)
                    .setRuntimeInit()
                    .unremovable()
                    .addInjectionPoint(ClassType.create(DotName.createSimple(QuarkusJSqlClientContainer.class)),
                            quarkusJSqlClientContainerQualifier)
                    .createWith(recorder.quarkusJSqlClientFunction(dataSourceName));

            if (DataSourceUtil.isDefault(dataSourceName)) {
                configurator.addQualifier(Default.class);
                configurator.priority(10);
            } else {
                String beanName = FEATURE + "_" + dataSourceName;
                configurator.name(beanName);
                configurator.priority(5);

                configurator.addQualifier().annotation(DotNames.NAMED).addValue("value", beanName).done();
                configurator.addQualifier().annotation(DataSource.class).addValue("value", dataSourceName).done();
            }

            syntheticBeanBuildItemBuildProducer.produce(configurator.done());

            sqlClientBuildItemBuildItem.produce(new SqlClientBuildItem(dataSourceName));
        }
    }

    @BuildStep(onlyIf = IsKotlinEnable.class)
    @Produce(SyntheticBeansRuntimeInitBuildItem.class)
    @Consume(LoggingSetupBuildItem.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    void generateKSqlClientBeans(JimmerDataSourcesRecorder recorder,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer,
            List<JdbcDataSourceBuildItem> jdbcDataSourceBuildItems,
            BuildProducer<SqlClientBuildItem> sqlClientBuildItemBuildItem) {
        if (jdbcDataSourceBuildItems.isEmpty()) {
            return;
        }

        additionalBeans.produce(new AdditionalBeanBuildItem(KSqlClient.class));

        additionalBeans
                .produce(AdditionalBeanBuildItem.builder().addBeanClasses(QuarkusSqlClientProducer.class).setUnremovable()
                        .setDefaultScope(DotNames.SINGLETON).build());

        for (JdbcDataSourceBuildItem jdbcDataSourceBuildItem : jdbcDataSourceBuildItems) {
            String dataSourceName = jdbcDataSourceBuildItem.getName();

            SyntheticBeanBuildItem.ExtendedBeanConfigurator quarkusKSqlClientContainerConfigurator = SyntheticBeanBuildItem
                    .configure(QuarkusKSqlClientContainer.class)
                    .scope(Singleton.class)
                    .setRuntimeInit()
                    .unremovable()
                    .addInjectionPoint(ClassType.create(DotName.createSimple(QuarkusSqlClientProducer.class)))
                    .createWith(recorder.kSqlClientContainerFunction(dataSourceName));

            AnnotationInstance quarkusKSqlClientContainerQualifier;

            if (DataSourceUtil.isDefault(dataSourceName)) {
                quarkusKSqlClientContainerConfigurator.addQualifier(Default.class);

                quarkusKSqlClientContainerConfigurator.priority(10);

                quarkusKSqlClientContainerQualifier = AnnotationInstance.builder(Default.class).build();
            } else {
                String beanName = JIMMER_CONTAINER_BEAN_NAME_PREFIX + dataSourceName;
                quarkusKSqlClientContainerConfigurator.name(beanName);

                quarkusKSqlClientContainerConfigurator.addQualifier().annotation(DotNames.NAMED).addValue("value", beanName)
                        .done();
                quarkusKSqlClientContainerConfigurator.addQualifier().annotation(DataSource.class)
                        .addValue("value", dataSourceName).done();
                quarkusKSqlClientContainerConfigurator.priority(5);

                quarkusKSqlClientContainerQualifier = AnnotationInstance.builder(DataSource.class).add("value", dataSourceName)
                        .build();
            }

            syntheticBeanBuildItemBuildProducer.produce(quarkusKSqlClientContainerConfigurator.done());

            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(KSqlClient.class)
                    .scope(Singleton.class)
                    .setRuntimeInit()
                    .unremovable()
                    .addInjectionPoint(ClassType.create(DotName.createSimple(QuarkusKSqlClientContainer.class)),
                            quarkusKSqlClientContainerQualifier)
                    .createWith(recorder.quarkusKSqlClientFunction(dataSourceName));

            if (DataSourceUtil.isDefault(dataSourceName)) {
                configurator.addQualifier(Default.class);
                configurator.priority(10);
            } else {
                String beanName = FEATURE + "_" + dataSourceName;
                configurator.name(beanName);
                configurator.priority(5);

                configurator.addQualifier().annotation(DotNames.NAMED).addValue("value", beanName).done();
                configurator.addQualifier().annotation(DataSource.class).addValue("value", dataSourceName).done();
            }

            syntheticBeanBuildItemBuildProducer.produce(configurator.done());

            sqlClientBuildItemBuildItem.produce(new SqlClientBuildItem(dataSourceName));
        }
    }

    @BuildStep
    @Consume(RepositoryMetadata.class)
    void generateRepositoryImpl(List<RepositoryMetadata> repositoryBuildItems,
            BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItem) {
        if (repositoryBuildItems.isEmpty()) {
            return;
        }
        ClassOutput classOutput = new GeneratedBeanGizmoAdaptor(generatedBeanBuildItem);
        for (RepositoryMetadata metadata : repositoryBuildItems) {
            JimmerRepositoryFactory jimmerRepositoryFactory = new JimmerRepositoryFactory(metadata);
            classOutput.write(jimmerRepositoryFactory.getTargetRepositoryClass().getName(),
                    jimmerRepositoryFactory.getTargetRepositoryBytes());
        }
    }

    /**
     * Jimmer builds its metadata graph (types, props, DTO/converter metadata, scalar providers,
     * association proxies) lazily into mutable static state. Baking that half-built graph into the
     * image heap is fragile, so keep those holders at run time and let Jimmer rebuild the graph at
     * startup (see {@link io.quarkiverse.jimmer.runtime.JimmerMetadataInitializer}).
     * <p>
     * The low-level cache primitives ({@code ClassCache}/{@code TypeCache}/{@code PropCache}/
     * {@code CacheSlots}) are deliberately NOT listed here: since jimmer 0.11.3 they are lock-free
     * ({@link ClassValue} + {@code AtomicReferenceArray}) and are referenced from build-time
     * initialized classes such as {@code AbstractWeakJoinLambdaFactory.CACHE}. They must therefore
     * initialize at build time — forcing them to run time puts a run-time-init object into the
     * image heap and fails the native build.
     */
    @BuildStep
    void runtimeInitializeJimmerCaches(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInitialized) {
        for (Class<?> clazz : new Class<?>[]{
                Metadata.class,
                ConverterMetadata.class,
                DtoMetadata.class,
                AssociationType.class,
                ScalarProvider.class,
                ScalarProvider.Meta.class,
                CacheLoader.class,
                ClientExceptionMetadata.class,
                AssociationTableProxyImpl.class,
                TableProxies.class}) {
            runtimeInitialized.produce(new RuntimeInitializedClassBuildItem(clazz.getName()));
        }
        // Package-private / private nested in Jimmer — not referencable as class literals.
        for (String className : new String[]{
                "org.babyfish.jimmer.sql.cache.ObjectCacheFetchers",
                "org.babyfish.jimmer.sql.ast.impl.table.WeakJoinHandleImpl",
                "org.babyfish.jimmer.sql.ast.impl.table.WeakJoinHandleImpl$EntityTableHandleImpl"}) {
            runtimeInitialized.produce(new RuntimeInitializedClassBuildItem(className));
        }
    }

    /**
     * UUIDv7IdGenerator eagerly builds a TimeBasedEpochGenerator in its static initializer, which
     * holds a SecureRandom used to fill the random node bits. Build-time class initialization would
     * bake that seeded Random into the image heap, which GraalVM rejects outright. Defer to run time.
     */
    @BuildStep
    void runtimeInitializeUuidGenerator(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInitialized) {
        runtimeInitialized.produce(new RuntimeInitializedClassBuildItem(UUIDv7IdGenerator.class.getName()));
        runtimeInitialized.produce(new RuntimeInitializedClassBuildItem(TimeBasedEpochGenerator.class.getName()));
    }

    /**
     * The generated Jimmer classes (Draft/Props/Fetcher/Table and the .dto Input/View types) build
     * their metadata in static initializers that reach the run-time cache machinery above. If they
     * were build-time initialized those metadata instances would be baked into the image heap (which
     * clashes with the run-time caches) and the concurrent build-time init would deadlock. Defer
     * them to run time as well, reusing the same discovery as reflection registration.
     */
    @BuildStep
    void runtimeInitializeGeneratedJimmerClasses(CombinedIndexBuildItem combinedIndex,
            BuildProducer<RuntimeInitializedClassBuildItem> runtimeInitialized) {
        IndexView index = combinedIndex.getIndex();
        Set<String> classNames = new HashSet<>();
        collectAnnotatedEntities(index, classNames);
        collectImplementors(index, classNames);
        for (String className : classNames) {
            runtimeInitialized.produce(new RuntimeInitializedClassBuildItem(className));
        }
    }

    @BuildStep
    void registerNativeImageResources(BuildProducer<NativeImageResourceBuildItem> resource) {
        resource.produce(new NativeImageResourceBuildItem(
                Constant.TEMPLATE_RESOURCE,
                Constant.NO_API_RESOURCE,
                Constant.NO_METADATA_RESOURCE,
                Constant.CLIENT_RESOURCE,
                Constant.ENTITIES_RESOURCE,
                Constant.IMMUTABLES_RESOURCE));
    }

    @BuildStep
    void registerDialectsForReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        reflectiveClass.produce(
                ReflectiveClassBuildItem.builder(
                        PostgresDialect.class,
                        MySqlDialect.class,
                        OracleDialect.class,
                        H2Dialect.class,
                        SqlServerDialect.class,
                        TiDBDialect.class,
                        UUIDIdGenerator.class,
                        UUIDv7IdGenerator.class)
                        .constructors(true)
                        .build());
    }

    @BuildStep
    void registerPostgreSqlExceptionReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        if (!QuarkusClassLoader.isClassPresentAtRuntime("org.postgresql.util.PSQLException")) {
            return;
        }
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(
                "org.postgresql.util.PSQLException",
                "org.postgresql.util.ServerErrorMessage").constructors(false).methods().build());
    }

    @BuildStep
    void registerKotlinFilterProxies(BuildProducer<NativeImageProxyDefinitionBuildItem> proxies) {
        for (List<Class<?>> markers : List.of(
                List.<Class<?>>of(CacheableFilter.class, ShardingFilter.class, AssociationIntegrityAssuranceFilter.class),
                List.<Class<?>>of(KCacheableFilter.class, KShardingFilter.class, KAssociationIntegrityAssuranceFilter.class))) {
            for (boolean cacheable : List.of(false, true)) {
                for (var scope : List.of(
                        List.of(markers.get(1)), List.of(markers.get(2)), markers.subList(1, 3))) {
                    List<String> interfaces = new ArrayList<>();
                    interfaces.add(FilterWrapper.class.getName());
                    interfaces.add(Filter.class.getName());
                    if (cacheable) {
                        interfaces.add(markers.get(0).getName());
                    }
                    scope.forEach(type -> interfaces.add(type.getName()));
                    proxies.produce(new NativeImageProxyDefinitionBuildItem(interfaces));
                }
            }
        }
    }

    /**
     * Cache subsystem reflection. Without this on native image, FilterManager.onInitialized
     * triggers PropCacheInvalidators.isGetAffectedSourceIdsOverridden0 which does
     * Class.getMethod("getAffectedSourceIds", EntityEvent.class) and fails with
     * NoSuchMethodException → AssertionError. Caffeine binders' anonymous CacheLoader
     * subclasses also need methods kept so Caffeine can detect loadAll override.
     */
    @BuildStep
    void registerCacheReflection(
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        // Event types + filter/resolver interfaces with default methods that
        // PropCacheInvalidators dispatches via Class.getMethod(...) reflection.
        reflectiveClass.produce(ReflectiveClassBuildItem
                .builder(
                        EntityEvent.class,
                        AssociationEvent.class,
                        PropCacheInvalidator.class,
                        Filter.class,
                        CacheableFilter.class,
                        ShardingFilter.class,
                        ShardingCacheableFilter.class,
                        TransientResolver.class,
                        TypedTransientResolver.class,
                        CaffeineValueBinder.class,
                        CaffeineHashBinder.class,
                        com.github.benmanes.caffeine.cache.CacheLoader.class)
                .methods(true)
                .build());

        // The package-private jimmer-sql impls (@LogicalDeleted / FilterManager filters and the
        // anonymous CaffeineValueBinder CacheLoader) can't be referenced as .class from outside their
        // package, so they are registered declaratively in
        // META-INF/native-image/.../reflect-config.json instead.

        // User-defined CacheableFilter / TransientResolver / PropCacheInvalidator impls.
        IndexView index = combinedIndex.getIndex();
        Set<String> userImpls = new HashSet<>();
        for (Class<?> iface : List.of(
                PropCacheInvalidator.class,
                CacheableFilter.class,
                Filter.class,
                TransientResolver.class)) {
            for (ClassInfo impl : index.getAllKnownImplementations(iface)) {
                userImpls.add(impl.name().toString());
            }
        }
        if (!userImpls.isEmpty()) {
            reflectiveClass.produce(ReflectiveClassBuildItem
                    .builder(userImpls.toArray(new String[0]))
                    .methods(true)
                    .constructors(true)
                    .build());
        }
    }

    @BuildStep
    void registerJimmerClassesForReflection(CombinedIndexBuildItem combinedIndex,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        IndexView index = combinedIndex.getIndex();
        Set<String> classNames = new HashSet<>();
        Set<String> enumClassNames = new HashSet<>();

        collectAnnotatedEntities(index, classNames);
        collectImplementors(index, classNames);
        collectEnumTypesFromEntities(index, enumClassNames);

        if (!classNames.isEmpty()) {
            reflectiveClass.produce(
                    ReflectiveClassBuildItem.builder(classNames.toArray(new String[0]))
                            .methods(true)
                            .fields(true)
                            .constructors(true)
                            .build());
        }

        if (!enumClassNames.isEmpty()) {
            reflectiveClass.produce(
                    ReflectiveClassBuildItem.builder(enumClassNames.toArray(new String[0]))
                            .fields(true)
                            .build());
        }
    }

    @BuildStep
    void registerJimmerAnnotationsForReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(JIMMER_SQL_ANNOTATIONS)
                .methods(true)
                .build());
    }

    private void collectEnumTypesFromEntities(IndexView index, Set<String> enumClassNames) {
        for (DotName annotation : ENTITY_ANNOTATIONS) {
            for (AnnotationInstance instance : index.getAnnotations(annotation)) {
                ClassInfo entityClass = instance.target().asClass();
                // Jimmer entities are interfaces — properties are represented as methods
                for (MethodInfo method : entityClass.methods()) {
                    if (method.parametersCount() == 0 && method.returnType().kind() == Type.Kind.CLASS) {
                        ClassInfo returnTypeClass = index.getClassByName(method.returnType().name());
                        if (returnTypeClass != null && returnTypeClass.isEnum()) {
                            enumClassNames.add(returnTypeClass.name().toString());
                        }
                    }
                }
                // Also check fields for non-interface cases
                for (FieldInfo field : entityClass.fields()) {
                    if (field.type().kind() == Type.Kind.CLASS) {
                        ClassInfo fieldTypeClass = index.getClassByName(field.type().name());
                        if (fieldTypeClass != null && fieldTypeClass.isEnum()) {
                            enumClassNames.add(fieldTypeClass.name().toString());
                        }
                    }
                }
            }
        }
    }

    private void collectAnnotatedEntities(IndexView index, Set<String> classNames) {
        for (DotName annotation : ENTITY_ANNOTATIONS) {
            for (AnnotationInstance instance : index.getAnnotations(annotation)) {
                ClassInfo entityClass = instance.target().asClass();
                addClassWithMemberClasses(entityClass, classNames);

                // Register generated companions found in the index (Draft, Props, Fetcher, etc.)
                for (ClassInfo candidate : index.getClassesInPackage(entityClass.name().packagePrefix())) {
                    if (candidate.name().toString().startsWith(entityClass.name().toString())) {
                        addClassWithMemberClasses(candidate, classNames);
                    }
                }
            }
        }
    }

    private void collectImplementors(IndexView index, Set<String> classNames) {
        Set<DotName> implementorTypes = new HashSet<>(IMPLEMENTOR_TYPES);
        boolean changed;
        do {
            changed = false;
            for (ClassInfo candidate : index.getKnownClasses()) {
                if (candidate.isInterface()
                        && candidate.interfaceNames().stream().anyMatch(implementorTypes::contains)) {
                    changed |= implementorTypes.add(candidate.name());
                }
            }
        } while (changed);

        // Jimmer dependencies need not be Jandex-indexed: model classes retain direct interfaces.
        for (ClassInfo candidate : index.getKnownClasses()) {
            if (candidate.interfaceNames().stream().anyMatch(implementorTypes::contains)) {
                addClassWithMemberClasses(candidate, classNames);
            }
        }
    }

    private void addClassWithMemberClasses(ClassInfo classInfo, Set<String> classNames) {
        classNames.add(classInfo.name().toString());
        for (DotName memberClass : classInfo.memberClasses()) {
            classNames.add(memberClass.toString());
        }
    }

    static final class JimmerEnable extends AbstractJimmerBooleanSupplier {
        private JimmerEnable(JimmerBuildTimeConfig jimmerBuildTimeConfig) {
            super(jimmerBuildTimeConfig);
        }

        @Override
        public boolean getAsBoolean() {
            return jimmerBuildTimeConfig.enable();
        }
    }

    static final class IsMicroServiceEnable extends AbstractJimmerBooleanSupplier {

        private IsMicroServiceEnable(JimmerBuildTimeConfig jimmerBuildTimeConfig) {
            super(jimmerBuildTimeConfig);
        }

        @Override
        public boolean getAsBoolean() {
            return jimmerBuildTimeConfig.microServiceName().isPresent()
                    && !jimmerBuildTimeConfig.microServiceName().get().isEmpty();
        }
    }

    static final class IsKotlinEnable extends AbstractJimmerBooleanSupplier {

        private IsKotlinEnable(JimmerBuildTimeConfig jimmerBuildTimeConfig) {
            super(jimmerBuildTimeConfig);
        }

        @Override
        public boolean getAsBoolean() {
            return jimmerBuildTimeConfig.language().equalsIgnoreCase("kotlin");
        }
    }

    static final class IsJavaEnable extends AbstractJimmerBooleanSupplier {

        private IsJavaEnable(JimmerBuildTimeConfig jimmerBuildTimeConfig) {
            super(jimmerBuildTimeConfig);
        }

        @Override
        public boolean getAsBoolean() {
            return jimmerBuildTimeConfig.language().equalsIgnoreCase("java");
        }
    }
}
