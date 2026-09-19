package io.quarkiverse.jimmer.it;

import static io.quarkiverse.jimmer.runtime.executor.CompactSqlExecutor.extractOperation;
import static io.quarkiverse.jimmer.runtime.executor.CompactSqlExecutor.extractTable;
import static io.quarkiverse.jimmer.runtime.executor.CompactSqlExecutor.formatRows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class CompactSqlExecutorTest {

    @Test
    void extractsSelectOperationAndTable() {
        String sql = "select tb_1_.ID, tb_1_.NAME from test_entity tb_1_ where tb_1_.ID = ?";
        assertEquals("SELECT", extractOperation(sql));
        assertEquals("test_entity", extractTable(sql));
    }

    @Test
    void extractsInsertOperationAndTable() {
        String sql = "insert into user_profile(ID, NAME) values(?, ?)";
        assertEquals("INSERT", extractOperation(sql));
        assertEquals("user_profile", extractTable(sql));
    }

    @Test
    void extractsUpdateOperationAndTable() {
        String sql = "update city set NAME = ? where ID = ?";
        assertEquals("UPDATE", extractOperation(sql));
        assertEquals("city", extractTable(sql));
    }

    @Test
    void extractsDeleteOperationAndTable() {
        String sql = "delete from subway_line where ID = ?";
        assertEquals("DELETE", extractOperation(sql));
        assertEquals("subway_line", extractTable(sql));
    }

    @Test
    void handlesMultilineJimmerSql() {
        String sql = "select\n    tb_1_.ID,\n    tb_1_.CREATED_AT,\n    tb_1_.NAME\nfrom\n    test_entity tb_1_\nwhere\n    tb_1_.ID = ?";
        assertEquals("SELECT", extractOperation(sql));
        assertEquals("test_entity", extractTable(sql));
    }

    @Test
    void handlesMergeOperation() {
        String sql = "merge into localization(ID, EN, RU) values(?, ?, ?)";
        assertEquals("MERGE", extractOperation(sql));
        assertEquals("localization", extractTable(sql));
    }

    @Test
    void handlesQuotedTableNames() {
        String sql = "select tb_1_.ID from \"user\" tb_1_ where tb_1_.ID = ?";
        assertEquals("user", extractTable(sql));
    }

    @Test
    void returnsQuestionMarkForUnknownOperation() {
        assertEquals("?", extractTable("EXPLAIN select * from foo"));
    }

    @Test
    void formatsRows() {
        assertEquals("3 rows", formatRows(List.of(1, 2, 3)));
        assertEquals("0 rows", formatRows(List.of()));
        assertEquals("5 rows", formatRows(5));
        assertEquals("0 rows", formatRows(null));
        assertEquals("1 row", formatRows("entity"));
    }
}
