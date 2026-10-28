package example;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.dialect.H2Dialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;

public class Examples {
    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:generated-java");
             var statement = connection.createStatement();
             var schema = Examples.class.getResourceAsStream("/schema.sql")) {
            for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            connection.setAutoCommit(false);
            var sql = JSqlClient.newBuilder().setDialect(new H2Dialect())
                .setConnectionManager(ConnectionManager.singleConnectionManager(connection)).build();
            var created = CanaryJava.create(sql, "Canary Guide", new BigDecimal("25.00"), "test");
            check(created.getId() > 0, "generated ID missing");
            check(created.getName().equals("Canary Guide"), "wrong saved View");
            try {
                CanaryJava.create(sql, "Canary Guide", new BigDecimal("99.00"), "test");
                throw new AssertionError("duplicate create did not fail");
            } catch (RuntimeException expected) {
                // The contract requires propagation, not a particular translator.
            }
            var original = sql.findById(BookFetcher.$.price(), created.getId());
            check(original.price().compareTo(new BigDecimal("25.00")) == 0, "duplicate changed price");
            CanaryJava.create(sql, "A title", BigDecimal.ONE, "test");
            CanaryJava.create(sql, "Z title", BigDecimal.TEN, "test");
            var first = CanaryJava.search(sql, null, 0, 1);
            check(first.getTotalRowCount() == 3, "wrong total or null predicate");
            check(first.getRows().size() == 1 && first.getRows().get(0).getName().equals("A title"),
                "ordering/page size");
            check(CanaryJava.search(sql, "Guide", 0, 10).getRows().size() == 1, "name filter");
            check(CanaryJava.search(sql, "missing", 0, 10).getRows().isEmpty(), "missing filter");
            connection.rollback();
        }
        System.out.println("MODEL-GENERATED JAVA: strict create, duplicate preservation, generated ID and filtered pagination passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
