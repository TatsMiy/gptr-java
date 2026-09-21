package com.gptr.engine.testsupport;

import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试的数据库护栏：执行任何"清空"动作**之前**，先确认当前连的是测试库。
 *
 * <p><b>为什么校验 {@code current_database()} 而不是 {@code spring.datasource.url}</b>：
 * 配置是**意图**（可被 {@code -D} / 环境变量 / profile / 将来的重构覆盖），库名才是
 * **运行时事实**；两者不一致时只有后者能拦住误清。
 *
 * <p><b>背景</b>：各集成测试在 {@code @BeforeEach} 里 TRUNCATE/DELETE **全表**
 * {@code tasks} 与 {@code graph_checkpoints}。若连到开发库或生产库，会不可逆地清空任务记录
 * 与 checkpoint —— 连带 {@code /tasks/{id}/evidence}（从 checkpoint 读证据库）对历史任务的
  * 查询能力。
 *
  * <p>本类是该清空动作的**唯一出口**：测试代码里不得再出现裸的清空 SQL。
 */
public final class IntegrationDbGuard {

    /** 测试库名必须以此后缀结尾（大小写不敏感）。 */
    private static final String TEST_DB_SUFFIX = "_test";

    private IntegrationDbGuard() {
    }

    /** 断言当前库是测试库；不通过则抛 {@link IllegalStateException}，且**不执行任何写操作**。 */
    public static void assertTestDatabase(JdbcTemplate jdbc) {
        String db = jdbc.queryForObject("SELECT current_database()", String.class);
        if (db == null || !db.toLowerCase(Locale.ROOT).endsWith(TEST_DB_SUFFIX)) {
            throw new IllegalStateException(
                    "集成测试拒绝在非测试库上运行：current_database()=" + db
                            + "（要求库名以 \"" + TEST_DB_SUFFIX + "\" 结尾）\n"
                            + "  为什么危险：本调用会 TRUNCATE/DELETE 全表 tasks / graph_checkpoints，\n"
                            + "   不可逆地清空任务记录与 checkpoint"
                            + "（/tasks/{id}/evidence 依赖后者）。\n"
                            + "  怎么修：把 spring.datasource.url 指向测试库，例如\n"
                            + "   jdbc:postgresql://localhost:5432/gptr_test\n"
                            + "   若尚未建库：docker exec gptr-postgres psql -U gptr -d postgres"
                            + " -c 'CREATE DATABASE gptr_test OWNER gptr;'\n"
                            + "  本护栏的存在理由：清空动作曾误删开发库的真实数据。");
        }
    }

    /** 清空 tasks（**表级锁**语义；与改动前各测试的 {@code TRUNCATE ... CASCADE} 逐字一致）。 */
    public static void truncateTasks(JdbcTemplate jdbc) {
        assertTestDatabase(jdbc);
        jdbc.execute("TRUNCATE TABLE tasks CASCADE");
    }

    /** 清空 tasks（**行锁**语义）——保留 {@code BudgetIntegrationTest} 的防死锁理由，
          *  勿与 {@link #truncateTasks} 合并。 */
    public static void deleteTasks(JdbcTemplate jdbc) {
        assertTestDatabase(jdbc);
        jdbc.execute("DELETE FROM tasks");
    }

    /** 清空 graph_checkpoints。 */
    public static void truncateCheckpoints(JdbcTemplate jdbc) {
        assertTestDatabase(jdbc);
        jdbc.execute("TRUNCATE graph_checkpoints");
    }
}
