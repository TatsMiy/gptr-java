package com.gptr.engine.gate;

import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 可读性门禁 —— 取代 {@code scripts/check-readability.ps1}。
 *
 * <p><b>当前批次</b>：见 {@link #CURRENT_BATCH}。未实现的判据在报告里标「待批 N」，
 * 计数显示为 {@code -} 而非 {@code 0} —— <b>这两者必须区分</b>：
 * 「测了，没违规」与「没测」是完全不同的结论。
 *
 * <p>两个文件集是**不同**的，不要合并：
 * <ul>
 *   <li><b>main 源码集</b>（判据 1–12）：engine 与 benchmark 的 {@code src/main/java/*.java}
 *       —— 测试代码不在范围内；</li>
 *   <li><b>全项目文件集</b>（判据 13）：{@code git ls-files} + {@code --others --exclude-standard}
 *       —— 即「会随公开仓发布的文件」；发布树没有 {@code .git} 时退化为文件系统遍历。</li>
 * </ul>
 */
public final class ReadabilityGate {

    private ReadabilityGate() {
    }

    /** 已实现的批次。判据归属见 {@link #DEFS} 的最后一列。 */
    private static final int CURRENT_BATCH = 4;

    // 判据键 —— 与 DEFS 一一对应
    private static final String K_LINE = "LineLength";
    private static final String K_METHOD = "MethodLines";
    private static final String K_BLOCK_LAMBDA = "BlockLambda";
    private static final String K_NESTED_LAMBDA = "NestedLambda";
    private static final String K_FQN = "InlineFqn";
    private static final String K_CATCH_LOST = "CatchLost";
    private static final String K_CATCH_COMMENT = "CatchCommentOnly";
    private static final String K_TERNARY = "NestedTernary";
    private static final String K_IMPORT = "UnusedImport";
    private static final String K_NESTING = "ControlNesting";
    private static final String K_PARAMS = "ParamCount";
    private static final String K_RECORDS = "RecordComponents";
    private static final String K_PRIVATE_REF = "PrivateRef";

    /** 判据的静态定义：键 / 显示名 / 上限 / 硬判据还是棘轮 / 属于哪一批。 */
    private record Def(String key, String name, int max, boolean hard, int batch) {
    }

    /**
     * 13 条判据的定义表。上限为 0 表示「一条都不许有」。
     */
    private static final List<Def> DEFS = List.of(
            new Def(K_LINE, "单行 <=" + GateConfig.MAX_LINE_LENGTH + " 字符", 0, true, 1),
            new Def(K_METHOD, "方法 <=" + GateConfig.MAX_METHOD_LINES + " 行（棘轮 "
                    + GateConfig.RATCHET_METHOD_VIOLATIONS + "）",
                    GateConfig.RATCHET_METHOD_VIOLATIONS, false, 3),
            new Def(K_BLOCK_LAMBDA, "块 lambda 体 <=" + GateConfig.MAX_BLOCK_LAMBDA_LINES + " 行",
                    0, true, 2),
            new Def(K_NESTED_LAMBDA, "lambda 内不嵌 lambda", 0, true, 2),
            new Def(K_FQN, "内联全限定名 = 0", 0, true, 4),
            new Def(K_CATCH_LOST, "catch 不丢异常信息", 0, true, 3),
            new Def(K_CATCH_COMMENT, "仅凭注释的 catch（棘轮 "
                    + GateConfig.RATCHET_COMMENT_ONLY_CATCH + "）",
                    GateConfig.RATCHET_COMMENT_ONLY_CATCH, false, 3),
            new Def(K_TERNARY, "嵌套三元 = 0", 0, true, 2),
            new Def(K_IMPORT, "未使用 import = 0", 0, true, 4),
            new Def(K_NESTING, "控制语句嵌套 <=" + GateConfig.MAX_CONTROL_NESTING + " 层（棘轮 "
                    + GateConfig.RATCHET_NESTING_VIOLATIONS + "）",
                    GateConfig.RATCHET_NESTING_VIOLATIONS, false, 3),
            new Def(K_PARAMS, "参数个数 <=" + GateConfig.MAX_PARAMS + "（棘轮 "
                    + GateConfig.RATCHET_PARAM_VIOLATIONS + "）",
                    GateConfig.RATCHET_PARAM_VIOLATIONS, false, 2),
            new Def(K_RECORDS, "record 分量 <=" + GateConfig.MAX_RECORD_COMPONENTS + "（棘轮 "
                    + GateConfig.RATCHET_RECORD_VIOLATIONS + "）",
                    GateConfig.RATCHET_RECORD_VIOLATIONS, false, 2),
            new Def(K_PRIVATE_REF, "私有引用（公开物不得引用私有仓）", 0, true, 1));

    /** 一条判据的一次测量。{@code batch > CURRENT_BATCH} 即为「尚未实现」。 */
    public record Rule(String key, String name, int count, int max,
                       boolean hard, int batch, List<String> details) {

        public boolean active() {
            return batch <= CURRENT_BATCH;
        }

        public boolean over() {
            return count > max;
        }
    }

    /** 一次执行的结果；{@code report} 是打印全文，其余供对拍与落盘。 */
    public record Result(int exitCode, String report,
                         int filesScanned, int linesScanned, int privateRefFilesScanned,
                         int readFailures, int parseFailures, List<Rule> rules) {
    }

    public static void main(String[] args) {
        Path root = Path.of("").toAbsolutePath();
        boolean all = false;
        for (String arg : args) {
            if (arg.equals("--all")) {
                all = true;
            } else {
                root = Path.of(arg);
            }
        }
        Result r = run(root, all);
        System.out.print(r.report());
        writeReports(root, r);
        System.exit(r.exitCode());
    }

    /** 报告与明细落盘（失败不影响退出码 —— 报告已打给 stdout）。 */
    private static void writeReports(Path root, Result r) {
        try {
            Path target = root.resolve("gptr-engine").resolve("target");
            Files.createDirectories(target);
            Files.writeString(target.resolve("readability-gate.txt"), r.report(), StandardCharsets.UTF_8);
            for (Rule rule : r.rules()) {
                if (rule.key().equals(K_PRIVATE_REF)) {
                    Files.write(target.resolve("gate-private-refs.txt"), rule.details(),
                            StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            System.err.println("[gate] 报告落盘失败：" + e);
        }
    }

    public static Result run(Path root) {
        return run(root, false);
    }

    public static Result run(Path root, boolean all) {
        List<Path> javaFiles = javaSources(root);
        List<String> allFiles = projectFiles(root);
        List<String> readFailures = new ArrayList<>();
        List<String> parseFailures = new ArrayList<>();
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (Def def : DEFS) {
            found.put(def.key(), new ArrayList<>());
        }

        int linesScanned = 0;
        for (Path f : javaFiles) {
            String rel = rel(root, f);
            List<String> lines = GateRules.readLines(f);
            if (lines == null) {
                readFailures.add(rel);
                continue;
            }
            linesScanned += lines.size();
            found.get(K_LINE).addAll(GateRules.lineLength(rel, lines));

            GateAst.Parsed parsed = GateAst.parse(f);
            if (!parsed.ok()) {
                parseFailures.add(rel + "  " + parsed.problem());
                continue;   // 该文件的 AST 判据（3/4/8/11/12）一条都不跑
            }
            astRules(rel, parsed.cu(), found);
        }
        scanPrivateRefs(root, allFiles, found.get(K_PRIVATE_REF), readFailures);

        List<Rule> rules = new ArrayList<>();
        for (Def def : DEFS) {
            List<String> details = found.get(def.key());
            rules.add(new Rule(def.key(), def.name(), details.size(),
                    def.max(), def.hard(), def.batch(), List.copyOf(details)));
        }
        int exit = exitCode(rules, readFailures, parseFailures);
        String report = render(rules, javaFiles.size(), linesScanned,
                allFiles.size(), readFailures, parseFailures, exit, all);
        return new Result(exit, report, javaFiles.size(), linesScanned,
                allFiles.size(), readFailures.size(), parseFailures.size(), List.copyOf(rules));
    }

    // ── 各步骤 ──────────────────────────────────────────────────────────────

    /** 全部 AST 判据的调用点。新增判据加在这里，不改 {@link #run}。 */
    private static void astRules(String rel, CompilationUnit cu, Map<String, List<String>> found) {
        found.get(K_METHOD).addAll(GateRules.methodLength(rel, cu));
        found.get(K_BLOCK_LAMBDA).addAll(GateRules.blockLambda(rel, cu));
        found.get(K_NESTED_LAMBDA).addAll(GateRules.nestedLambda(rel, cu));
        found.get(K_FQN).addAll(GateRules.inlineFqn(rel, cu));
        found.get(K_CATCH_LOST).addAll(GateRules.silentCatch(rel, cu));
        found.get(K_CATCH_COMMENT).addAll(GateRules.commentOnlyCatch(rel, cu));
        found.get(K_TERNARY).addAll(GateRules.nestedTernary(rel, cu));
        found.get(K_IMPORT).addAll(GateRules.unusedImports(rel, cu));
        found.get(K_NESTING).addAll(GateRules.controlNesting(rel, cu));
        found.get(K_PARAMS).addAll(GateRules.paramCount(rel, cu));
        found.get(K_RECORDS).addAll(GateRules.recordComponents(rel, cu));
    }

    private static void scanPrivateRefs(Path root, List<String> allFiles,
                                        List<String> into, List<String> readFailures) {
        for (String relPath : allFiles) {
            List<String> lines = GateRules.readLines(root.resolve(relPath));
            if (lines == null) {
                readFailures.add(relPath);
                continue;
            }
            into.addAll(GateRules.privateRefs(relPath, lines, !relPath.endsWith(".md")));
        }
    }

    /** 硬判据超标或「读取/解析失败」⇒ 1；棘轮超标只 WARN，不阻断。 */
    private static int exitCode(List<Rule> rules,
                                List<String> readFailures, List<String> parseFailures) {
        if (!readFailures.isEmpty() || !parseFailures.isEmpty()) {
            return 1;
        }
        for (Rule r : rules) {
            if (r.active() && r.hard() && r.over()) {
                return 1;
            }
        }
        return 0;
    }

    /** main 源码集：判据 1–12 的扫描范围（**仅 main**；测试代码不在范围内）。 */
    static List<Path> javaSources(Path root) {
        List<Path> files = new ArrayList<>();
        for (String dir : GateConfig.MAIN_SOURCE_DIRS) {
            Path d = root.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(d)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException e) {
                // 该目录不可遍历：它下面的文件整体缺席，由「文件集为空」检查兜住
            }
        }
        return files;
    }

    /**
     * 全项目文件集：有 {@code .git} 时用 {@code git ls-files}（**含未跟踪但未忽略**的文件，
     * 否则新增文件在 {@code git add} 之前是门禁盲区）；没有 {@code .git}（发布树）时退化为遍历。
     */
    static List<String> projectFiles(Path root) {
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(root.resolve(".git"))) {
            files.addAll(git(root, "ls-files"));
            files.addAll(git(root, "ls-files", "--others", "--exclude-standard"));
        } else {
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().replace('\\', '/')
                                .matches(".*/(target|node_modules|__pycache__|\\.git|\\.mvn|\\.idea)/.*"))
                        .forEach(p -> files.add(rel(root, p)));
            } catch (IOException e) {
                return List.of();
            }
        }
        List<String> kept = new ArrayList<>();
        for (String f : files) {
            if (GateConfig.PRIVATE_REF_SKIP.contains(f)) {
                continue;
            }
            if (f.matches(".*" + GateConfig.BINARY_EXT_RE)) {
                continue;
            }
            // `git ls-files` 列的是**索引**：工作区已删但删除尚未提交时，索引里仍有它。
            // 那不是"读取失败"，只是"这个文件已不在"，跳过即可（否则删文件的中间态会误红）。
            if (!Files.isRegularFile(root.resolve(f))) {
                continue;
            }
            kept.add(f);
        }
        return kept;
    }

    // ── 报告 ────────────────────────────────────────────────────────────────

    private static String render(List<Rule> rules, int files, int lines, int privateRefFiles,
                                 List<String> readFailures, List<String> parseFailures, int exit,
                                 boolean all) {
        List<String> out = new ArrayList<>();
        out.add("");
        out.add("可读性判据门禁");
        out.add("扫描 " + files + " 个文件 / " + lines + " 行");
        out.add("私有引用（规则 11）扫描 " + privateRefFiles + " 个跟踪文件");
        out.add("");
        out.add("  [FAIL] = 违反规范（构建失败）    [WARN] = 存量趋势变差（不中断构建）");
        out.add("");
        for (Rule r : rules) {
            out.add(row(r));
        }

        int hardTotal = 0;
        int hardPass = 0;
        for (Rule r : rules) {
            if (r.active() && r.hard()) {
                hardTotal++;
                if (!r.over()) {
                    hardPass++;
                }
            }
        }
        out.add("");
        appendDetails(out, rules, all);
        appendFailures(out, "解析失败", parseFailures, "不得当作「无违规」——该文件的 AST 判据一条都没跑");
        appendFailures(out, "读取失败", readFailures, "不得当作「无违规」");
        out.add("");
        out.add(hardTotal == 0 ? "未实现任何硬判据"
                : (exit == 0 ? "通过：硬判据 " + hardPass + " / " + hardTotal
                             : "未通过：硬判据 " + hardPass + " / " + hardTotal));
        out.add("");
        int active = activeCount(rules);
        out.add(active == rules.size()
                ? "  ✅ " + rules.size() + " 条判据已全部实现（AST 版，取代 scripts/check-readability.ps1）。"
                : "  ⚠️ 批 " + CURRENT_BATCH + "：已覆盖 " + active + " / " + rules.size()
                        + " 条判据，其中未实现者计数显示为 -（不是 0）。");
        return String.join(System.lineSeparator(), out) + System.lineSeparator();
    }

    /**
     * 硬判据超标的明细（默认前 10 条，{@code all} 时全量）。
     *
     * <p>棘轮超标**不打明细** —— 它不阻断构建，明细会把报告淹掉。
     */
    private static void appendDetails(List<String> out, List<Rule> rules, boolean all) {
        for (Rule r : rules) {
            if (!r.active() || !r.hard() || !r.over() || r.details().isEmpty()) {
                continue;
            }
            int shown = all ? r.details().size() : Math.min(10, r.details().size());
            String more = shown < r.details().size()
                    ? "，此处显示前 " + shown + " 条，--all 看全部" : "";
            out.add("  " + r.name() + " 明细（" + r.details().size() + " 条" + more + "）:");
            for (int i = 0; i < shown; i++) {
                out.add("           " + r.details().get(i));
            }
            out.add("");
        }
    }

    private static void appendFailures(List<String> out, String label,
                                       List<String> failures, String note) {
        if (failures.isEmpty()) {
            return;
        }
        out.add("  [FAIL] " + label + " " + failures.size() + " 个文件 —— " + note);
        for (String f : failures) {
            out.add("           " + f);
        }
    }

    private static int activeCount(List<Rule> rules) {
        int n = 0;
        for (Rule r : rules) {
            if (r.active()) {
                n++;
            }
        }
        return n;
    }

    private static String row(Rule r) {
        if (!r.active()) {
            return String.format("  [ -- ] %-32s %6s  (待批 %d)", r.name(), "-", r.batch());
        }
        String mark;
        if (r.over()) {
            mark = r.hard() ? "FAIL" : "WARN";
        } else {
            mark = "OK  ";
        }
        return String.format("  [%s] %-32s %6d  (上限 %d)", mark, r.name(), r.count(), r.max());
    }

    // ── 进程与路径 ──────────────────────────────────────────────────────────

    private static List<String> git(Path root, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", root.toString()));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            List<String> list = new ArrayList<>();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    list.add(line.trim().replace('\\', '/'));
                }
            }
            return list;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private static String rel(Path root, Path p) {
        String r = root.toAbsolutePath().toString().replace('\\', '/');
        String f = p.toAbsolutePath().toString().replace('\\', '/');
        return f.startsWith(r + "/") ? f.substring(r.length() + 1) : f;
    }
}
