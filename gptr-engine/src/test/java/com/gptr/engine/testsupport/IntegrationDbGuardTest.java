package com.gptr.engine.testsupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link IntegrationDbGuard} 的白名单逻辑单测。
 *
 * <p><b>不需要数据库</b>（{@code JdbcTemplate} 打桩）⇒ 归入默认单测集，随日常
  * {@code mvn test} 执行，使护栏逻辑本身天天被验证。这与"集成测试才跑"
 * 的护栏形成互补：**逻辑正确性天天验，端到端拦截在集成测试里验**。
 */
class IntegrationDbGuardTest {

    /** 打桩一个"自称连的是 currentDatabase"的 JdbcTemplate。 */
    private static JdbcTemplate jdbcReporting(String currentDatabase) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_database()", String.class))
                .thenReturn(currentDatabase);
        return jdbc;
    }

    @Test
    @DisplayName("库名以 _test 结尾（大小写不敏感）⇒ 放行并执行清空")
    void allowsTestSuffixedDatabase() {
        assertDoesNotThrow(() -> IntegrationDbGuard.truncateTasks(jdbcReporting("gptr_test")));
        assertDoesNotThrow(() -> IntegrationDbGuard.deleteTasks(jdbcReporting("GPTR_TEST")));
        assertDoesNotThrow(() -> IntegrationDbGuard.truncateCheckpoints(jdbcReporting("x_test")));
    }

    @Test
    @DisplayName("非测试库 / 库名为 null ⇒ 拒绝")
    void rejectsNonTestDatabase() {
        for (String db : new String[] {"gptr", "postgres", "gptr_test_backup", null}) {
            assertThrows(IllegalStateException.class,
                    () -> IntegrationDbGuard.truncateTasks(jdbcReporting(db)),
                    "应拒绝库名: " + db);
        }
    }

    @Test
    @DisplayName("拒绝时**不得发起任何写操作**（先校验后执行）")
    void doesNotExecuteAnyStatementWhenRejected() {
        JdbcTemplate jdbc = jdbcReporting("gptr");

        assertThrows(IllegalStateException.class, () -> IntegrationDbGuard.truncateTasks(jdbc));

        verify(jdbc, never()).execute(anyString());
    }
}
