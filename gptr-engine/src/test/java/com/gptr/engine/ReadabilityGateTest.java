package com.gptr.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
  * 可读性判据门禁的构建期入口。
 *
 * <p>它跑 {@code scripts/check-readability.ps1} 并断言退出码为 0；失败时把脚本输出
 * 原样带进断言消息，使违规清单直接出现在构建日志里（否则排查还得手动重跑）。
 *
 * <p><b>为什么接在 surefire 而不是 exec-maven-plugin</b>：exec-maven-plugin 不在本地
 * Maven 仓库，接入需联网下载新依赖；surefire 已在用，用它执行同一个脚本**零新增依赖**，
 * 且随 {@code mvn test} 自动运行。
 *
 * <p><b>为什么门禁逻辑不写在这里</b>：脚本是判据的唯一实现。在 Java 里再写一份必然与
  * 脚本漂移（复用铁律：语义完全等价才允许并存，否则就是两份真相）。
 * 本类只负责"定位脚本 + 定位 PowerShell + 调用 + 断言 + 把输出搬进日志"。
 *
 * <p><b>已知局限</b>：找不到任何 PowerShell 的环境（如未装 PowerShell 的 Linux CI）会被
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue} **跳过而非失败**——那等于该环境
 * 没有门禁。若日后接入 Linux CI，需先确保镜像内有 pwsh，或把门禁移植为 Java 实现。
 */
class ReadabilityGateTest {

    /** 门禁脚本相对仓库根的位置。 */
    private static final String GATE_RELATIVE = "scripts/check-readability.ps1";

    /**
     * PowerShell 候选可执行文件，按优先级排列。
     *
     * <p>列多个是因为本机实测**只装了 Windows PowerShell 5.1**（无 PowerShell 7）：
     * 直接写死 {@code pwsh} 会得到 {@code CreateProcess error=2}——那会让门禁在所有
     * 未装 PS7 的机器上静默跳过。脚本本身已验证兼容 5.1 与 7。
     */
    private static final List<String> POWERSHELL_CANDIDATES = List.of(
            "pwsh",
            "powershell",
            "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");

    /**
     * 门禁脚本必须带 **UTF-8 BOM**。
     *
     * <p>为什么需要这条守卫：{@code check-readability.ps1} 含中文注释，而 Windows PowerShell 5.1 对
     * **无 BOM** 的 {@code .ps1} 会按系统 ANSI(GBK) 解读 ⇒ 中文乱码 ⇒ 语法解析失败
     * （2026-09-21 实测：报错是 {@code Unexpected token ')'}，行号指向毫不相干的 L53）。
     *
     * <p>必要性来自一个**真实反复**：本仓的编辑工具（以及多数编辑器）默认写 UTF-8 无 BOM，
     * 于是"改一次脚本 → 门禁崩一次"。有了这条断言，丢 BOM 会立即在此红，且消息直接给出修法。
     */
    @Test
    void gateScriptHasUtf8Bom() throws Exception {
        Path script = findGateScript();
        assumeTrue(script != null,
                "未找到 " + GATE_RELATIVE + "；已尝试的起点: " + candidateStartDirs());

        byte[] head = new byte[3];
        try (var in = Files.newInputStream(script)) {
            assertEquals(3, in.read(head), "脚本不足 3 字节：" + script);
        }
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, head,
                GATE_RELATIVE + " 缺 UTF-8 BOM —— Windows PowerShell 5.1 会按 GBK 读它，"
                        + "中文注释乱码会让脚本解析失败。修法：用 Python 的 utf-8-sig 写回，或补 BOM。");
    }

    @Test
    void readabilityGatePasses() throws Exception {
        Path script = findGateScript();
        assumeTrue(script != null,
                "未找到 " + GATE_RELATIVE + "；已尝试的起点: " + candidateStartDirs());

        String shell = resolvePowerShell();
        assumeTrue(shell != null,
                "未找到可用的 PowerShell（试过 " + POWERSHELL_CANDIDATES + "），跳过可读性门禁");

        Process process = new ProcessBuilder(shell, "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString())
                .redirectErrorStream(true)
                .start();
        // readAllBytes 读至流关闭（进程结束），故不会与随后的 waitFor 互相阻塞
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(60, TimeUnit.SECONDS);
        writeReport(output);
        assertEquals(0, process.exitValue(), "可读性门禁未通过：\n" + output);
    }

    /**
     * 把门禁输出落盘为 UTF-8 报告。
     *
     * <p>因为 Windows PowerShell 控制台默认 GBK，而 JVM（Java 18+，JEP 400）默认按 UTF-8
     * 输出 —— 违规清单里的中文在控制台上会显示成乱码。落盘后无论终端编码如何，
     * 打开 {@code target/readability-gate.txt} 都能正确阅读。
     */
    private static void writeReport(String output) throws IOException {
        Path targetDir = Path.of(System.getProperty("user.dir"), "target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("readability-gate.txt"), output,
                StandardCharsets.UTF_8);
    }

    /**
     * 自若干起点逐级向上查找仓库根下的门禁脚本。
     *
     * <p>需要多个起点：surefire 的 {@code user.dir} 不保证是模块目录（实测本项目里确实是
     * 模块目录，但不能依赖），故同时用测试类自身的 classpath 位置兜底。
     */
    private static Path findGateScript() {
        for (Path start : candidateStartDirs()) {
            Path cursor = start;
            while (cursor != null) {
                Path candidate = cursor.resolve(GATE_RELATIVE);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                cursor = cursor.getParent();
            }
        }
        return null;
    }

    /** 候选起点：surefire 的 {@code user.dir}，以及测试类所在目录（classpath 定位）。 */
    private static List<Path> candidateStartDirs() {
        List<Path> starts = new ArrayList<>();
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            starts.add(Path.of(userDir).toAbsolutePath());
        }
        try {
            Path codeSource = Path.of(
                    ReadabilityGateTest.class.getProtectionDomain().getCodeSource()
                            .getLocation().toURI());
            starts.add(codeSource.toAbsolutePath());
        } catch (Exception ignored) {
            // classpath 定位失败时，user.dir 那条路径仍然有效
        }
        return starts;
    }

    /** 返回第一个可用的 PowerShell；都不可用返回 null（调用方据此跳过）。 */
    private static String resolvePowerShell() {
        for (String candidate : POWERSHELL_CANDIDATES) {
            if (canRun(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 探测某个可执行文件能否启动并以 0 退出。 */
    private static boolean canRun(String executable) {
        try {
            Process probe = new ProcessBuilder(executable, "-NoProfile", "-Command", "exit 0")
                    .redirectErrorStream(true)
                    .start();
            return probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException e) {
            return false; // 文件不存在 / 不可执行
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
