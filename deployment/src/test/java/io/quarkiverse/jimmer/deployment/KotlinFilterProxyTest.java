package io.quarkiverse.jimmer.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.sql.filter.AssociationIntegrityAssuranceFilter;
import org.babyfish.jimmer.sql.filter.CacheableFilter;
import org.babyfish.jimmer.sql.filter.Filter;
import org.babyfish.jimmer.sql.filter.ShardingFilter;
import org.babyfish.jimmer.sql.filter.impl.FilterWrapper;
import org.babyfish.jimmer.sql.kt.filter.KAssociationIntegrityAssuranceFilter;
import org.babyfish.jimmer.sql.kt.filter.KCacheableFilter;
import org.babyfish.jimmer.sql.kt.filter.KFilter;
import org.babyfish.jimmer.sql.kt.filter.KShardingFilter;
import org.babyfish.jimmer.sql.kt.filter.impl.JavaFiltersKt;
import org.babyfish.jimmer.sql.kt.filter.impl.KtFiltersKt;
import org.junit.jupiter.api.Test;

import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;

class KotlinFilterProxyTest {

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void registersActualJimmerProxyInterfacesInBothDirections() {
        List<NativeImageProxyDefinitionBuildItem> registrations = new ArrayList<>();
        new JimmerProcessor().registerKotlinFilterProxies(registrations::add);
        List<List<String>> registered = registrations.stream()
                .map(NativeImageProxyDefinitionBuildItem::getClasses).toList();
        ClassLoader loader = getClass().getClassLoader();
        Object immutableType = Proxy.newProxyInstance(loader, new Class<?>[] { ImmutableType.class },
                (proxy, method, args) -> null);

        for (boolean kotlin : List.of(false, true)) {
            for (int flags = 2; flags < 8; flags++) {
                List<Class<?>> interfaces = new ArrayList<>();
                interfaces.add(FilterWrapper.class);
                interfaces.add(kotlin ? KFilter.class : Filter.class);
                if ((flags & 1) != 0) {
                    interfaces.add(kotlin ? KCacheableFilter.class : CacheableFilter.class);
                }
                if ((flags & 2) != 0) {
                    interfaces.add(kotlin ? KShardingFilter.class : ShardingFilter.class);
                }
                if ((flags & 4) != 0) {
                    interfaces.add(kotlin ? KAssociationIntegrityAssuranceFilter.class : AssociationIntegrityAssuranceFilter.class);
                }
                Object filter = Proxy.newProxyInstance(loader, interfaces.toArray(Class<?>[]::new),
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getImmutableType" -> immutableType;
                            case "getFilterType" -> proxy.getClass();
                            default -> null;
                        });
                Object converted = kotlin
                        ? JavaFiltersKt.toJavaFilter((KFilter<?>) filter)
                        : KtFiltersKt.toKtFilter((Filter) filter);
                List<String> actual = Arrays.stream(converted.getClass().getInterfaces()).map(Class::getName).toList();
                assertTrue(registered.contains(actual), () -> "Missing native proxy registration: " + actual);
            }
        }
    }
}
