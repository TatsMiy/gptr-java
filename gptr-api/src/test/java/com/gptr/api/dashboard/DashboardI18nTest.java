package com.gptr.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 观测台双语文案的机械守卫。
 *
 * <p>读静态文件即可，**不需要浏览器、不需要 Spring context**。
 *
 * <p>它拦的是"漏翻一处"——这类错误会随每次改界面反复发生，靠肉眼必然漏。四条判据：
 * <ol>
 *   <li>词典 {@code zh} / {@code en} 的 key 集合完全相同；</li>
 *   <li>词典与「HTML 引用 + JS 引用」三方互相覆盖（无孤儿、无缺失）；</li>
 *   <li>{@code index.html} 里含中文的行必须带 {@code data-i18n}（默认文本只作 JS 未加载时的兜底）；</li>
 *   <li>{@code dashboard.js} 剥离注释后不得有含中文的字符串字面量（白名单见 {@link #JS_ALLOW}）。</li>
 * </ol>
 */
class DashboardI18nTest {

    private static final Path DIR = Path.of("src", "main", "resources", "static", "dashboard");
    private static final Path HTML = DIR.resolve("index.html");
    private static final Path JS = DIR.resolve("dashboard.js");
    private static final Path I18N = DIR.resolve("i18n.js");

    /** 含 CJK 的字符类。 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    /** HTML 里绑定了文案的元素（开标签）。 */
    private static final Pattern HTML_BOUND = Pattern.compile("data-i18n(?:-html|-attr)?\\s*=");
    /** `data-i18n="k"` / `data-i18n-html="k"` 的单 key 形式。 */
    private static final Pattern ATTR_ONE = Pattern.compile("data-i18n(?:-html)?=\"([^\"]+)\"");
    /** `data-i18n-attr="title:k1,placeholder:k2"`。 */
    private static final Pattern ATTR_LIST = Pattern.compile("data-i18n-attr=\"([^\"]+)\"");
    /** JS 里 `t("key")` / `t("key", …)`。 */
    private static final Pattern JS_T = Pattern.compile("\\bt\\(\"([a-zA-Z][\\w.]*)\"");
    /** JS 里 `I18N.configDescription` 的映射表键（形如 "llm.provider": "cfg.…"）。 */
    private static final Pattern CFG_MAP = Pattern.compile("\"[\\w.-]+\"\\s*:\\s*\"(cfg\\.[\\w.]+)\"");

    /**
     * JS 里允许保留的中文字面量。
     *
     * <p>目前只有一处：提交表单 {@code #fLanguage} 的默认值 {@code "中文"} —— 它是**发给引擎的
     * 协议字符串**，不是 UI 文案 —— 禁止进词典。
     */
    private static final List<String> JS_ALLOW = List.of("\"中文\"");

    private static List<String> lines(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    // ── 判据 1 ────────────────────────────────────────────────────────────────
    @Test
    void dictKeysAreIdenticalOnBothSides() throws IOException {
        List<String> src = lines(I18N);
        Set<String> zh = keysOf(src, "zh");
        Set<String> en = keysOf(src, "en");

        assertTrue(zh.size() > 50, "zh 词典只有 " + zh.size() + " 个 key，疑似解析失败（假绿保护）");
        assertEquals(zh, en, "zh / en 词典的 key 集合必须完全相同 —— 差集见消息上方断言");
    }

    // ── 判据 2 ────────────────────────────────────────────────────────────────
    @Test
    void dictAndReferencesCoverEachOther() throws IOException {
        Set<String> dict = keysOf(lines(I18N), "zh");
        Set<String> used = new LinkedHashSet<>();
        used.addAll(htmlRefs());
        used.addAll(jsRefs());

        Set<String> missing = new LinkedHashSet<>(used);
        missing.removeAll(dict);
        assertTrue(missing.isEmpty(), "引用了词典里没有的 key（漏定义）: " + missing);

        Set<String> orphan = new LinkedHashSet<>(dict);
        // 配置面板映射表的 target 是"被 configDescription 引用"的另一种形式
        for (String l : lines(I18N)) {
            Matcher m = CFG_MAP.matcher(l);
            while (m.find()) {
                orphan.remove(m.group(1));
            }
        }
        orphan.removeAll(used);
        assertTrue(orphan.isEmpty(), "词典里有没人引用的孤儿 key: " + orphan);
    }

    // ── 判据 3 ────────────────────────────────────────────────────────────────
    @Test
    void htmlHasNoUnboundChinese() throws IOException {
        List<String> bad = new ArrayList<>();
        int inComment = 0;
        int n = 0;
        for (String raw : lines(HTML)) {
            n++;
            String line = raw;
            if (inComment > 0 || line.contains("<!--")) {
                if (line.contains("-->")) {
                    inComment = 0;
                } else if (line.contains("<!--")) {
                    inComment = 1;
                }
                continue;   // 注释不计
            }
            if (!CJK.matcher(line).find()) {
                continue;
            }
            if (line.contains("data-i18n-ignore")) {
                continue;   // 显式豁免（当前只有 #fLanguage —— 它的 option 是「值」不是文案）
            }
            if (!HTML_BOUND.matcher(line).find()) {
                bad.add("  index.html:" + n + "  " + line.trim());
            }
        }
        if (!bad.isEmpty()) {
            fail("index.html 里有含中文但未绑定 data-i18n 的行（应把文案绑到 key，默认文本作兜底）:\n"
                    + String.join("\n", bad));
        }
    }

    // ── 判据 4 ────────────────────────────────────────────────────────────────
    @Test
    void jsHasNoBareChineseStringLiteral() throws IOException {
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (String line : stripComments(lines(JS))) {
            n++;
            if (!CJK.matcher(line).find()) {
                continue;
            }
            boolean allowed = JS_ALLOW.stream().anyMatch(line::contains);
            if (!allowed) {
                bad.add("  dashboard.js:" + n + "  " + line.trim());
            }
        }
        if (!bad.isEmpty()) {
            fail("dashboard.js 剥离注释后仍有含中文的字符串字面量（应改用 t(key)）:\n"
                    + String.join("\n", bad));
        }
    }

    /** 剥离行首与行尾的 {@code //}、跨行块注释；字符串里的 {@code //} 不处理（够用）。 */
    private static List<String> stripComments(List<String> src) {
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        for (String raw : src) {
            String line = raw;
            if (inBlock) {
                int end = line.indexOf("*/");
                if (end < 0) {
                    out.add("");
                    continue;
                }
                line = line.substring(end + 2);
                inBlock = false;
            }
            // 同行闭合的块注释：整段删除（可能一行里有多段）
            line = line.replaceAll("/\\*.*?\\*/", "");
            int block = line.indexOf("/*");
            if (block >= 0) {                 // 仍未闭合 ⇒ 从这里到后续行都算注释
                line = line.substring(0, block);
                inBlock = true;
            }
            int slash = line.indexOf("//");
            if (slash >= 0) {
                line = line.substring(0, slash);
            }
            out.add(line);
        }
        return out;
    }

    /** 从 i18n.js 里取 `zh: {` / `en: {` 到对应 `},` 之间的 `"key": …` 行。 */
    private static Set<String> keysOf(List<String> src, String lang) {
        Set<String> keys = new LinkedHashSet<>();
        boolean in = false;
        int depth = 0;
        Pattern keyLine = Pattern.compile("^\\s*\"([\\w.]+)\"\\s*:");
        for (String line : src) {
            if (!in) {
                if (line.trim().startsWith(lang + ": {")) {
                    in = true;
                    depth = 1;
                }
                continue;
            }
            if (line.trim().startsWith("}")) {
                break;
            }
            Matcher m = keyLine.matcher(line);
            if (m.find()) {
                keys.add(m.group(1));
            }
        }
        if (depth == 0) {
            throw new IllegalStateException("i18n.js 里找不到 " + lang + " 词典块");
        }
        return keys;
    }

    private static Set<String> htmlRefs() throws IOException {
        Set<String> refs = new LinkedHashSet<>();
        for (String line : lines(HTML)) {
            Matcher one = ATTR_ONE.matcher(line);
            while (one.find()) {
                refs.add(one.group(1));
            }
            Matcher list = ATTR_LIST.matcher(line);
            while (list.find()) {
                for (String pair : list.group(1).split(",")) {
                    int i = pair.indexOf(':');
                    if (i > 0) {
                        refs.add(pair.substring(i + 1).trim());
                    }
                }
            }
        }
        return refs;
    }

    /** dashboard.js 的 t("…") + i18n.js 里 DICT **之外**的 t("…")（跳过词典自身，否则自证）。 */
    private static Set<String> jsRefs() throws IOException {
        Set<String> refs = new LinkedHashSet<>();
        for (String line : stripComments(lines(JS))) {
            Matcher m = JS_T.matcher(line);
            while (m.find()) {
                refs.add(m.group(1));
            }
        }
        boolean inDict = false;
        for (String line : stripComments(lines(I18N))) {
            String s = line.trim();
            if (s.startsWith("const DICT = {")) { inDict = true; continue; }
            if (inDict) {
                if (s.equals("};")) { inDict = false; }
                continue;
            }
            Matcher m = JS_T.matcher(line);
            while (m.find()) {
                refs.add(m.group(1));
            }
        }
        return refs;
    }
}
