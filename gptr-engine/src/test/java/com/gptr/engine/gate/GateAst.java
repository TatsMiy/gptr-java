package com.gptr.engine.gate;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * AST 解析入口 —— 全门禁唯一持有 JavaParser 的地方。
 *
 * <p>为什么单独一个类：解析失败是一条**独立的硬判据**（规格「契约与不变量」第 1 条），
 * 它不属于 13 条可读性规则的任何一条，却比任何一条都更严重 ——
 * 单条判据漏报有边界（特定形态），解析失败没有（规模是「全部」）。
 */
final class GateAst {

    private GateAst() {
    }

    /**
     * 解析器。
     *
     * <p>必须**显式设置语言级别**：{@code StaticJavaParser.parse()} 用的是默认级别（约 Java 8），
     * 而本项目是 21 —— 实测 64 个文件里 **33 个**直接失败（record、文本块、instanceof 模式
     * 全被拒为「not supported … starting from JAVA_14/15」）。
     */
    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.CURRENT));

    /** 解析结果。成功时 {@code cu != null}；失败时 {@code problem != null}。 */
    record Parsed(CompilationUnit cu, String problem) {

        boolean ok() {
            return cu != null;
        }
    }

    /**
     * 解析一个文件。
     *
     * <p>判成功**必须查 {@code isSuccessful()}**，不能查 {@code getResult().isPresent()}：
     * 实测坏输入（如 {@code class Broken { void m( { }}）上两者**不一致** ——
     * isSuccessful 为 false 而 getResult 非空（JavaParser 做了错误恢复、返回部分 AST）。
     * 只写 {@code getResult().orElseThrow()} 的话坏文件会**静默通过**：
     * 门禁报「合格」，而它一个判据都没跑过。
     *
     * <p>不 fail-fast：调用方负责**收集全部**坏文件后一次性报出（与 javac 行为一致）。
     */
    static Parsed parse(Path file) {
        try {
            ParseResult<CompilationUnit> r = PARSER.parse(file);
            if (!r.isSuccessful()) {
                return new Parsed(null, r.getProblems().stream()
                        .map(p -> p.getVerboseMessage())
                        .collect(Collectors.joining(" | ")));
            }
            CompilationUnit cu = r.getResult().orElse(null);
            if (cu == null) {
                return new Parsed(null, "isSuccessful() 为 true 但 getResult() 为空");
            }
            return new Parsed(cu, null);
        } catch (Exception e) {
            return new Parsed(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
