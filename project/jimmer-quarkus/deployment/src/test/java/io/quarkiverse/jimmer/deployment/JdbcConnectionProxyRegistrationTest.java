package io.quarkiverse.jimmer.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;

class JdbcConnectionProxyRegistrationTest {

    @Test
    void registersJdbcConnectionProxy() {
        List<NativeImageProxyDefinitionBuildItem> registrations = new ArrayList<>();
        new JimmerProcessor().registerJdbcConnectionProxy(registrations::add);
        List<List<String>> registered = registrations.stream()
                .map(NativeImageProxyDefinitionBuildItem::getClasses).toList();
        assertEquals(List.of(List.of(Connection.class.getName())), registered);
    }
}
