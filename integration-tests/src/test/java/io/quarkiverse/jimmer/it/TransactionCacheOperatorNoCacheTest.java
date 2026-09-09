package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import jakarta.inject.Inject;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.cache.CachesImpl;
import org.babyfish.jimmer.sql.cache.TransactionCacheOperator;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.jimmer.it.entity.BookStore;
import io.quarkiverse.jimmer.it.entity.BookStoreTable;
import io.quarkiverse.jimmer.runtime.SqlClientInitializationAware;
import io.quarkiverse.jimmer.runtime.cache.impl.TransactionCacheOperatorFlusher;
import io.quarkiverse.jimmer.runtime.java.QuarkusJSqlClientContainer;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(TransactionCacheOperatorNoCacheTest.Profile.class)
class TransactionCacheOperatorNoCacheTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.arc.exclude-types", "io.quarkiverse.jimmer.it.config.CacheConfig",
                    "quarkus.jimmer.transaction-cache-operator-fixed-delay", "off");
        }
    }

    @Inject JSqlClient client;
    @Inject AgroalDataSource dataSource;
    @Inject TransactionCacheOperatorFlusher flusher;
    @Inject @DataSource(Constant.DATASOURCE2) QuarkusJSqlClientContainer secondClient;

    @Test
    void writesAndRetryDoNotInitializeAnUnusedOperator() throws Exception {
        SqlClientInitializationAware second = (SqlClientInitializationAware) secondClient.getjSqlClient();
        boolean secondInitialized = second.isSqlClientInitialized();
        flusher.retry();
        assertTrue(CachesImpl.isEmpty(client.getCaches()));
        String original = client.findById(BookStore.class, 1L).website();
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            update("rollback");
            throw new IllegalStateException("rollback");
        }));
        assertEquals(original, client.findById(BookStore.class, 1L).website());
        QuarkusTransaction.requiringNew().run(() -> update("committed"));
        assertEquals("committed", client.findById(BookStore.class, 1L).website());
        update(original);
        flusher.retry();
        assertEquals(secondInitialized, second.isSqlClientInitialized(),
                "Default datasource writes and retry must not initialize DB2");
        try (var connection = dataSource.getConnection();
                var tables = connection.getMetaData().getTables(null, null,
                        TransactionCacheOperator.TABLE_NAME.toLowerCase(java.util.Locale.ROOT), null)) {
            assertFalse(tables.next(), "No cache operator table should be initialized when ORM caches are absent");
        }
    }

    @Test
    void cacheInspectionDoesNotInitializeALazyClient() throws Exception {
        JSqlClient lazyClient = (JSqlClient) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { JSqlClient.class, SqlClientInitializationAware.class }, (proxy, method, args) -> {
                    if (method.getName().equals("isSqlClientInitialized")) return false;
                    throw new AssertionError("Must not call " + method.getName() + " on a lazy SqlClient");
                });
        var check = TransactionCacheOperatorFlusher.class.getDeclaredMethod("hasInitializedCaches", JSqlClient.class);
        check.setAccessible(true);
        assertEquals(false, check.invoke(null, lazyClient));
    }

    private void update(String website) {
        client.createUpdate(BookStoreTable.$).set(BookStoreTable.$.website(), website)
                .where(BookStoreTable.$.id().eq(1L)).execute();
    }
}
