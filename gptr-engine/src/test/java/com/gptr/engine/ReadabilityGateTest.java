package com.gptr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gptr.engine.gate.ReadabilityGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 可读性门禁的构建期入口 —— 直接调 {@link ReadabilityGate}，不再起 PowerShell 子进程。
 *
 * <p><b>为什么从「起脚本」改成「直接调用」</b>：判据已用 Java + JavaParser 实现，
 * 不再依赖 PowerShell。于是本测试从「找不到 shell 就 {@code assumeTrue} 静默跳过」
 * 变成**必然执行** —— 那正是旧形态最坏的一面：没门禁的环境会显示为绿。
 * 就地调用还顺带消掉了进程间 stdout 的编解码环节，异常栈直接指向判据代码。
 *
 * <p>落盘仍保留（{@code target/readability-gate.txt}）：Windows 控制台按 GBK 显示，
 * 中文明细到终端是乱码；落盘后无论终端编码如何都能正确阅读。
 *
 * <p>报告里未实现的判据会显示 {@code -} 而不是 {@code 0} ——
 * 「没测」与「测了没违规」必须区分。
 */
class ReadabilityGateTest {

    /**
     * 门禁必须通过：退出码 0，且**没有文件解析失败**。
     *
     * <p>解析失败单独断言且排在前面 —— 它是 AST 方案的**安全前提**：一旦某文件解析失败，
     * 它的一条 AST 判据都没跑过，此时报「合格」比任何单条判据漏报都严重（漏报有边界，它没有）。
     */
    @Test
    void readabilityGatePasses() throws Exception {
        ReadabilityGate.Result r = ReadabilityGate.run(repoRoot());
        writeReport(r.report());
        assertEquals(0, r.parseFailures(),
                "有文件解析失败 —— 那些文件的 AST 判据一条都没跑过，不得当作「无违规」：\n" + r.report());
        assertEquals(0, r.exitCode(), "可读性门禁未通过：\n" + r.report());
    }

    /** 仓库根 = 向上第一个含 {@code .git} 的目录（发布树无 {@code .git} 时退回工作目录）。 */
    private static Path repoRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    /** 把门禁输出落盘为 UTF-8 报告（JVM 18+ 默认 UTF-8，与控制台编码无关）。 */
    private static void writeReport(String output) throws IOException {
        Path targetDir = Path.of(System.getProperty("user.dir"), "target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("readability-gate.txt"), output, StandardCharsets.UTF_8);
    }
}
