package com.gptr.engine.gate;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 判据的实现。每条规则一个静态方法，输入文件标识（与 AST 或源码文本），
 * 输出**违规点的人类可读描述**（形如 {@code 相对路径:行号  说明}）。
 *
 * <p><b>13 条判据全部实现</b>：1（单行长度）· 2（方法行数）· 3（块 lambda 体）·
 * 4（lambda 嵌套）· 5（内联全限定名）· 6（catch 丢异常信息）· 7（仅凭注释的 catch）·
 * 8（嵌套三元）· 9（未使用 import）· 10（控制嵌套）· 11（参数个数）·
 * 12（record 分量）· 13（私有引用）。
 *
 * <p>为什么判据 1 与 13 保持文本实现：前者是**文本属性**（行长度），换成 AST 无增益；
 * 后者要扫本项目**所有格式**（{@code .py}/{@code .md}/{@code .yml}/{@code .sql}），
 * 而 AST 只管 Java。
 */
final class GateRules {

    private GateRules() {
    }

    // ── 判据 1：单行字符数 ≤ 120（硬）────────────────────────────────────────

    /**
     * 逐行测长度。**不剥离注释与字符串** —— 行长度是物理属性：一行过长的注释同样影响可读性。
     *
     * @param rel   相对路径（用于违规描述）
     * @param lines 文件的全部行
     */
    static List<String> lineLength(String rel, List<String> lines) {
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int len = lines.get(i).length();
            if (len > GateConfig.MAX_LINE_LENGTH) {
                bad.add(rel + ":" + (i + 1) + "  " + len + " 字符（上限 " + GateConfig.MAX_LINE_LENGTH + "）");
            }
        }
        return bad;
    }

    // ── 判据 2：方法 ≤ 80 行（棘轮）──────────────────────────────────────────

    /**
     * 口径 = AST 的 {@code getRange()} 行差（**含注解行**）。
     *
     * <p>用 AST 而非"数花括号"：字符串与注释里的花括号会干扰配平，按缩进识别声明
     * 又会漏掉嵌套类里的方法。
     *
     * <p>构造器**不计** —— 阿里·代码格式 11 管的是方法。
     */
    static List<String> methodLength(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            int lines = m.getRange().map(r -> r.end.line - r.begin.line + 1).orElse(0);
            if (lines > GateConfig.MAX_METHOD_LINES) {
                bad.add(rel + ":" + line(m) + "  " + m.getNameAsString() + "  " + lines + " 行");
            }
        }
        return bad;
    }

    // ── 判据 3：块 lambda 体 ≤ 3 语句（硬）───────────────────────────────────

    /**
     * 只看**块体** lambda（{@code -> { … }}）；表达式体 lambda 不适用（它没有语句）。
     *
     * <p>口径是**语句数**而非行数（规格「接口规格」判据 3 定）：行数会把
     * 「一条长语句拆三行」判成超标，而 Effective Java 那条规则要说的是
     * 「塞了几件事」—— 语句数才对应「事」。
     */
    static List<String> blockLambda(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (LambdaExpr le : cu.findAll(LambdaExpr.class)) {
            if (!(le.getBody() instanceof BlockStmt body)) {
                continue;
            }
            int n = body.getStatements().size();
            if (n > GateConfig.MAX_BLOCK_LAMBDA_LINES) {
                bad.add(rel + ":" + line(le) + "  body=" + n + " 语句");
            }
        }
        return bad;
    }

    // ── 判据 4：lambda 内不得再嵌 lambda（硬）────────────────────────────────

    /**
     * {@code findAll} 的自含性**不作为前提**：这里显式排除自身
     * （{@code x != le}），于是「含自身」与「不含自身」两种语义下结果都正确。
     * 这比依赖某个版本的具体行为更稳 —— 实测 JavaParser 的 {@code findAll}
     * **包含自身**，若照「非空即违规」写会把每个 lambda 都判违规。
     */
    static List<String> nestedLambda(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (LambdaExpr le : cu.findAll(LambdaExpr.class)) {
            long inner = le.findAll(LambdaExpr.class).stream().filter(x -> x != le).count();
            if (inner > 0) {
                bad.add(rel + ":" + line(le) + "  含 " + inner + " 个内层 lambda");
            }
        }
        return bad;
    }

    // ── 判据 6：catch 不得丢异常信息（硬）────────────────────────────────────

    /**
     * catch 块**既无实质动作、又无注释** ⇒ 违规（零容忍）。
     *
     * <p>与判据 7 共用 {@link #hasRealAction}：两条判据是**同一份判定的两个出口**
     * （有注释 ⇒ 降级为棘轮），各自再写一份必然漂移。
     */
    static List<String> silentCatch(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (CatchClause c : cu.findAll(CatchClause.class)) {
            BlockStmt body = c.getBody();
            if (!hasRealAction(body) && body.getAllContainedComments().isEmpty()) {
                bad.add(rel + ":" + line(c) + "  catch(" + c.getParameter().getTypeAsString() + ")");
            }
        }
        return bad;
    }

    // ── 判据 7：仅凭注释的 catch（棘轮）──────────────────────────────────────

    /**
     * catch 块**无实质动作但有注释** ⇒ 记棘轮。
     *
     * <p>为什么"有注释"仍要记：注释只说明「**为什么不管**」，不能替代「**失败去了哪**」。
     * 但它比"连注释都没有"轻一档，故降为棘轮而不阻断构建。
     */
    static List<String> commentOnlyCatch(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (CatchClause c : cu.findAll(CatchClause.class)) {
            BlockStmt body = c.getBody();
            if (hasRealAction(body) || body.getAllContainedComments().isEmpty()) {
                continue;
            }
            bad.add(rel + ":" + line(c) + "  catch(" + c.getParameter().getTypeAsString()
                    + ") -> " + firstStatement(body));
        }
        return bad;
    }

    /**
     * 「实质动作」判定。白名单（命中任一即认为异常信息未丢失）：
     * {@code LOG.}/{@code log.}/{@code throw }/{@code interrupt()}/{@code notePayload(}/
     * {@code activity(}/{@code .emit(}/{@code sink.}/{@code System.(out|err).}/{@code .put(}/
     * {@code last =}/{@code lastFailure =}/{@code new ResearchOutcome(}/{@code getMessage()}/
     * {@code toString()}（{@code AssertionError} 由 {@code throw} 覆盖）。
     *
     * <p>注意**裸 {@code return} 不算**实质动作 —— 规范要求的是"返回值**含该异常信息**"。
     * 这个区分是有意的：{@code return null;} 把异常丢了，正是要抓的形态。
     */
    private static boolean hasRealAction(BlockStmt body) {
        if (!body.findAll(ThrowStmt.class).isEmpty()) {
            return true;
        }
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (isRealCall(call)) {
                return true;
            }
        }
        for (AssignExpr a : body.findAll(AssignExpr.class)) {
            if (a.getTarget().toString().startsWith("last")) {
                return true;
            }
        }
        for (ObjectCreationExpr o : body.findAll(ObjectCreationExpr.class)) {
            if (o.getType().getNameAsString().equals("ResearchOutcome")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRealCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (name.equals("interrupt") || name.equals("notePayload") || name.equals("activity")
                || name.equals("emit") || name.equals("put")
                || name.equals("getMessage") || name.equals("toString")) {
            return true;
        }
        String scope = call.getScope().map(Object::toString).orElse("");
        return scope.equals("LOG") || scope.equals("log") || scope.startsWith("sink")
                || scope.startsWith("System.out") || scope.startsWith("System.err");
    }

    /** 块体首句（供明细阅读，不必打开文件就能判断该不该修）。 */
    private static String firstStatement(BlockStmt body) {
        if (body.getStatements().isEmpty()) {
            return "(仅注释，块体内无语句)";
        }
        String s = body.getStatement(0).toString().replace('\n', ' ').trim();
        return s.length() > 70 ? s.substring(0, 70) + "..." : s;
    }

    // ── 判据 8：不得嵌套三元（硬）────────────────────────────────────────────

    /**
     * 与判据 4 同形：一个三元表达式的**内部**若还有三元，即判嵌套。
     * 三个位置都算（条件、真分支、假分支），因此
     * {@code a ? b : c ? d : e} 这种**链式**也会被报。
     */
    static List<String> nestedTernary(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (ConditionalExpr ce : cu.findAll(ConditionalExpr.class)) {
            boolean nested = ce.findAll(ConditionalExpr.class).stream().anyMatch(x -> x != ce);
            if (nested) {
                bad.add(rel + ":" + line(ce) + "  嵌套三元");
            }
        }
        return bad;
    }

    // ── 判据 10：控制语句嵌套 ≤ 3 层（棘轮）──────────────────────────────────

    /**
     * 嵌套层 = 控制语句节点的**纵向叠加**：{@code if}/{@code for}/{@code for-each}/
     * {@code while}/{@code do}/{@code switch}/{@code try}。报告的是**文件级最大值**
     * （问的是"这个文件里有没有难读的深层嵌套"）。
     *
     * <p><b>{@code else if} 不计入</b>：{@code else} 分支上的 {@code if} 是**平铺的分支链**，
     * 不是嵌套。把它算进去会把
     * {@code if (a) … else if (b) … else if (c) …} 这种完全平铺的写法判成 3 层，
     * 与规范出处（阿里·控制语句 7，针对"嵌套"而非"分支"）的原意相悖。
     */
    static List<String> controlNesting(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        int max = maxNesting(cu, 0);
        if (max > GateConfig.MAX_CONTROL_NESTING) {
            bad.add(rel + "  max=" + max + "（上限 " + GateConfig.MAX_CONTROL_NESTING + "）");
        }
        return bad;
    }

    private static int maxNesting(Node node, int depth) {
        int best = depth;
        for (Node child : node.getChildNodes()) {
            best = Math.max(best, maxNesting(child, isControl(child) ? depth + 1 : depth));
        }
        return best;
    }

    private static boolean isControl(Node n) {
        if (isElseIf(n)) {
            return false;
        }
        return n instanceof IfStmt || n instanceof ForStmt || n instanceof ForEachStmt
                || n instanceof WhileStmt || n instanceof DoStmt || n instanceof SwitchStmt
                || n instanceof TryStmt;
    }

    private static boolean isElseIf(Node n) {
        return n instanceof IfStmt
                && n.getParentNode().filter(p -> p instanceof IfStmt)
                        .map(p -> ((IfStmt) p).getElseStmt().orElse(null) == n)
                        .orElse(false);
    }

    // ── 判据 11：方法 / 构造器参数个数 ≤ 7（棘轮）─────────────────────────────

    /**
     * 对齐 Checkstyle {@code ParameterNumber} 的 token 集（{@code METHOD_DEF} /
     * {@code CTOR_DEF}）：**record 头不计入** —— 其规范构造器是**隐式**的、
     * 不产生构造器节点（实测 {@code record C(int a, int b) {}} 的构造器数为 0），
     * record 的分量数归判据 12。
     *
     * <p>AST 口径不受"声明必须写在一行、且缩进恰好 4 空格"这类形式限制。
     */
    static List<String> paramCount(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            checkParams(bad, rel, m, m.getParameters().size(), m.getNameAsString());
        }
        for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class)) {
            checkParams(bad, rel, c, c.getParameters().size(), "<init>");
        }
        return bad;
    }

    // ── 判据 12：record 头分量个数 ≤ 8（棘轮）────────────────────────────────

    /** 与判据 11 分开：参数过多拆**签名**（提取参数对象），record 头过宽拆**字段组**。 */
    static List<String> recordComponents(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (RecordDeclaration r : cu.findAll(RecordDeclaration.class)) {
            int n = r.getParameters().size();
            if (n > GateConfig.MAX_RECORD_COMPONENTS) {
                bad.add(rel + ":" + line(r) + "  " + r.getNameAsString() + "  components=" + n);
            }
        }
        return bad;
    }

    // ── 判据 5：内联全限定名 = 0（硬）────────────────────────────────────────

    /**
     * 内联全限定名（如 {@code java.util.List} 直接写在代码里，而非 import 后写 {@code List}）。
     *
     * <p>口径：{@code ClassOrInterfaceType} 且有 scope、且**点分深度 ≥ 2**。
     * 之所以要 ≥2：{@code Map.Entry} 这类**嵌套类型**只有 1 个点，它是正当写法
     * （Java 没有"import 嵌套类型"的常规做法以外的选择），不该报。
     * 而 {@code java.util.Map.Entry} 是 3 个点 ⇒ 报。
     *
     * <p>判据是"读者不该在正文里看到包路径"，与包名是不是 {@code java} 开头无关 ——
     * 所以不按前缀白名单过滤。
     */
    static List<String> inlineFqn(String rel, CompilationUnit cu) {
        List<String> bad = new ArrayList<>();
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            if (!t.getScope().isPresent()) {
                continue;
            }
            // 只报**最外层**：`com.a.b.C` 在 AST 里是一串嵌套的 ClassOrInterfaceType
            // （`com.a.b.C` → scope `com.a.b` → …），若逐层都报，同一处会重复 4 次。
            if (t.getParentNode().filter(p -> p instanceof ClassOrInterfaceType).isPresent()) {
                continue;
            }
            String full = t.getNameWithScope();
            if (countDots(full) >= 2) {
                bad.add(rel + ":" + line(t) + "  " + full);
            }
        }
        return bad;
    }

    private static int countDots(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '.') {
                n++;
            }
        }
        return n;
    }

    // ── 判据 9：未使用 import = 0（硬）───────────────────────────────────────

    /**
     * 未使用的 import。判定：import 的**简单名**在文件正文里出现过就算用了。
     *
     * <p>扫描的节点类型要盖住三类 import：
     * <ul>
     *   <li>普通类型 import → {@code ClassOrInterfaceType}（{@code List<String>}）；</li>
     *   <li>{@code import static X.Y.method;} → {@code MethodCallExpr} /
     *       {@code NameExpr}（{@code method(...)}）；</li>
     *   <li>注解 import → {@code AnnotationExpr} 与 {@code NameExpr}。</li>
     * </ul>
     *
     * <p>只看**真实引用**：注释/javadoc 里提到类型名不算"用了" —— 若据此保留
     * import，删掉注释后代码就编译不过。
     */
    static List<String> unusedImports(String rel, CompilationUnit cu) {
        Set<String> used = new HashSet<>();
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            used.add(t.getNameAsString());
        }
        for (NameExpr n : cu.findAll(NameExpr.class)) {
            used.add(n.getNameAsString());
        }
        for (MethodCallExpr m : cu.findAll(MethodCallExpr.class)) {
            used.add(m.getNameAsString());
        }
        for (AnnotationExpr a : cu.findAll(AnnotationExpr.class)) {
            used.add(a.getNameAsString());
        }
        List<String> bad = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            String simple = imp.getName().getIdentifier();
            if (!used.contains(simple)) {
                bad.add(rel + ":" + line(imp) + "  " + imp.toString().trim());
            }
        }
        return bad;
    }

    // ── 判据 13：私有引用（硬）───────────────────────────────────────────────

    /**
     * 扫描一个文件的全部行，找出指向私有仓的引用。
     *
     * <p>两种模式（口径与旧 PowerShell 版一致）：
     * <ul>
     *   <li>模式 A：路径 / 文件名 / 仓名 —— 对**所有**文件生效；</li>
     *   <li>模式 B：裸章节号（SectionSign 紧跟字母或数字）—— **仅对代码文件**（非 {@code .md}）
     *       生效。代码不属于任何带章节编号的文档，故不可能是自引用；
     *       而 {@code .md} 里的「见某节」是合法的文档内自引用，不得误伤。</li>
     * </ul>
     *
     * @param rel    相对路径
     * @param lines  文件全部行
     * @param isCode 是否代码文件（非 {@code .md}）
     */
    static List<String> privateRefs(String rel, List<String> lines, boolean isCode) {
        List<String> bad = new ArrayList<>();
        Pattern bare = Pattern.compile(GateConfig.BARE_SECTION_RE);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(GateConfig.SELF_EXEMPT_MARK)) {
                continue;   // 自豁免行：模式定义处（见 GateConfig 的说明）
            }
            String hit = null;
            for (String p : GateConfig.PRIVATE_REF_PATTERNS) {
                if (line.contains(p)) {
                    hit = p;
                    break;
                }
            }
            if (hit == null && isCode && bare.matcher(line).find()) {
                hit = "裸章节号";
            }
            if (hit != null) {
                bad.add(rel + ":" + (i + 1) + "  [" + hit + "] " + line.trim());
            }
        }
        return bad;
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private static void checkParams(List<String> bad, String rel, Node node, int n, String name) {
        if (n > GateConfig.MAX_PARAMS) {
            bad.add(rel + ":" + line(node) + "  " + name + "  params=" + n);
        }
    }

    /** 节点起始行；取不到时返回 0（宁可报「未知行」也不吞掉这条违规）。 */
    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    /** 读文件全部行；失败返回 {@code null}（调用方计入「读取失败」，不当作「无违规」）。 */
    static List<String> readLines(Path p) {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
