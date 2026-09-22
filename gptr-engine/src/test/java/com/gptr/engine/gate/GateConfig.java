package com.gptr.engine.gate;

import java.util.List;

/**
 * 可读性门禁的阈值与棘轮基线 —— 对应旧 PowerShell 版脚本的 {@code $Limits}。
 *
 * <p>纪律：**每一项阈值都必须能追溯到公开规范或工具默认值**，并在下方注明出处；
 * 自定阈值一律无效。棘轮基线（{@code RATCHET_*}）可以调 —— 但每次调整都要进
 * {@code git diff} 并在提交信息里说明理由。
 *
 * <p>为什么不写在 {@code ReadabilityGate} 里：阈值是**被审对象**，与执行逻辑分开才看得清。
 */
final class GateConfig {

    private GateConfig() {
    }

    // ── 阈值 ────────────────────────────────────────────────────────────────

    /** 单行字符上限。出处：阿里《Java 开发手册（泰山版）》·代码格式 8【强制】。 */
    static final int MAX_LINE_LENGTH = 120;

    /** 方法行数上限。出处：阿里·代码格式 11【推荐】（取三者最严：阿里 80 / PMD 100 / Checkstyle 50）。 */
    static final int MAX_METHOD_LINES = 80;

    /** 块 lambda 体行数上限。出处：Effective Java 3rd, Item 42（1 行理想、3 行上限；超限应「消除」）。 */
    static final int MAX_BLOCK_LAMBDA_LINES = 3;

    /** 方法/构造器参数个数上限。出处：Checkstyle {@code ParameterNumber} 官方默认 {@code max=7}。 */
    static final int MAX_PARAMS = 7;

    /** record 头分量个数上限。出处：Checkstyle {@code RecordComponentNumber} 官方默认 {@code max=8}。 */
    static final int MAX_RECORD_COMPONENTS = 8;

    /** 控制语句嵌套层数上限。出处：阿里·控制语句 7【推荐】。 */
    static final int MAX_CONTROL_NESTING = 3;

    /**
     * 控制嵌套超标的存量（**棘轮**，不是硬判据）。
     *
     * <p>为什么是棘轮：benchmark 侧的存量属**书面接受**（评测入口重构的收益低于回归
     * 风险），所以留一条基线而不是要求归零。
     *
     * <p><b>engine 侧的预算是 0</b>：它新增一处嵌套就会让全仓计数超出此基线而触发 WARN。
     * 另注：{@code else if} 是**分支**不是嵌套，不计入层数。
     */
    static final int RATCHET_NESTING_VIOLATIONS = 8;

    // ── 棘轮基线（超标只 WARN，不阻断）──────────────────────────────────────
    // ⚠️ 调整须在提交信息里说明「是新增代码引入的，还是判定某处确实应保留现状」。

    /**
     * 方法行数超标的存量。棘轮基线**只允许下调** —— 存量被真实消除时应同步调低，
     * 否则基线会慢慢变成"允许新增"。
     */
    static final int RATCHET_METHOD_VIOLATIONS = 6;

    /** 「仅凭注释的 catch」存量。初测 42，修 {@code SearchNode#safeSearch} 后 41。 */
    static final int RATCHET_COMMENT_ONLY_CATCH = 41;

    /** 参数超标的存量。逐处收窄后归零。 */
    static final int RATCHET_PARAM_VIOLATIONS = 0;

    /** record 分量超标的存量（均为书面接受项）。 */
    static final int RATCHET_RECORD_VIOLATIONS = 4;

    // ── 文件集 ──────────────────────────────────────────────────────────────

    /** 判据 1–12 的扫描范围（**仅 main** 源码；测试代码不在范围内）。 */
    static final List<String> MAIN_SOURCE_DIRS = List.of(
            "gptr-engine/src/main/java",
            "gptr-benchmark/src/main/java");

    /**
     * 判据 13 的排除项：导出时被 {@code .release} 版本整文件替换（不进发布树）的文件
     * —— 它们的内容由导出流程决定，不由本仓源码决定。
     */
    static final List<String> PRIVATE_REF_SKIP = List.of(
            "BUILD.md",
            "docker-compose.yml");

    /** 判据 13 排除的二进制/外部数据扩展名。 */
    static final String BINARY_EXT_RE =
            "\\.(csv|png|jpe?g|gif|ico|svg|jar|bundle|zip|gz|woff2?|ttf|db)$";

    /**
     * 判据 13 的模式 A：路径 / 文件名 / 仓名（对**所有**文件生效）。
     *
     * <p>{@code BACKLOG} 用精确形态而非裸词 —— 裸词在本仓也可能是**描述性提及**
     * （实例：{@code .git-blame-ignore-revs} 里写着"门禁模式集扩展后清掉的 BACKLOG 引用"，
     * 实测误报过）。指向私有 backlog 的引用一定带编号或文件名。
     */
    static final List<String> PRIVATE_REF_PATTERNS = List.of(
            "gptr-java-design", "design/active/", "design/done/", "results/result-",       // gate-self-exempt
            "reviews/review-", "todo/BACKLOG", "rules/coding-discipline", "bugfix-logs/", // gate-self-exempt
            "papers-review", "设计文档 §", "规格 §",                                        // gate-self-exempt
            "BACKLOG #", "BACKLOG.md", "CURRENT-STATE", "glossary", "coding-discipline");  // gate-self-exempt

    /**
     * 自豁免标记：含它的**行**不参与判据 13。
     *
     * <p>为什么需要：本类必须**字面写出**那些模式串，否则判据无从定义 —— 门禁扫到自己就必然报自己，
     * 这是**自指**问题。整文件豁免不可取（这几个类恰恰最该被检查），故用逐行显式标记，
     * 且标记本身可被人工审计。
     */
    static final String SELF_EXEMPT_MARK = "gate-self-exempt";

    /** 判据 13 的模式 B（**仅代码文件**，即非 {@code .md}）：裸章节号。 */
    static final String BARE_SECTION_RE = "§[A-Za-z0-9]";
}
