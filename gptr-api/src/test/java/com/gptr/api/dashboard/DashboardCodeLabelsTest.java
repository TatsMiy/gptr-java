package com.gptr.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 取值域译名表的**跨语言对账**：界面的译名表必须与爬虫的词表逐个对上。
 *
 * <p>为什么需要它：界面把爬虫返回的码（{@code network_error} 等）译成人话，于是同一条知识
 * （"这个码是什么意思"）在两个地方各存了一份 —— 一份在 Python 的词表里（**所有者**），
 * 一份在 {@code i18n.js} 的 {@code code.*} 词条里（**镜像**）。两处都不会读对方，
 * 所以"漂没漂"只能靠机械检查，不能靠记性。
 *
 * <p>它拦的是三类漂移：
 * <ol>
 *   <li>爬虫新增一个原因，界面没跟上（译名缺失 ⇒ 界面会显示原码，属可接受的退化，
 *       但**必须被看见**，否则没人知道该补）；</li>
 *   <li>爬虫删掉/改名一个原因，界面的词条成了**永不会出现的死词条**；</li>
 *   <li>有人手滑在界面侧**发明**一个码 —— 那等于界面开始反向定义取值域。</li>
 * </ol>
 *
 * <p>读静态文件即可，**不需要浏览器、不需要 Spring context、不需要爬虫跑起来**。
 * 两个词表都不含 2xx–5xx 那类 HTTP 状态类（它们不译，见设计文档）。
 */
class DashboardCodeLabelsTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();
    private static final Path I18N = Path.of("src", "main", "resources", "static", "dashboard", "i18n.js");
    private static final Path OUTCOME_PY = Path.of("..", "crawler", "pycrawler", "scraper", "outcome.py");
    private static final Path STATS_PY = Path.of("..", "crawler", "pycrawler", "stats.py");

    /**
     * 爬虫侧**拥有**取值域的地方 —— 不止一处，这是本测试第一版漏掉的：
     * <ul>
     *   <li>{@code outcome.py}：{@code REASONS}（抓取失败原因）+ {@code PAGE_KINDS}（页型）；</li>
     *   <li>{@code stats.py}：{@code STATUS_CLASSES}（传输类别）+ {@code RESULT_CLASSES}（检索结果类别）。</li>
     * </ul>
     * 只盯 {@code outcome.py} 会把界面上那四个码（{@code empty}/{@code error}/{@code network}/{@code none}）
     * 误判成"界面自行发明"。
     */
    private static final List<String> OWNED_TUPLES = List.of("REASONS", "PAGE_KINDS");
    private static final List<String> OWNED_TUPLES_IN_STATS = List.of("STATUS_CLASSES", "RESULT_CLASSES");

    /**
     * **跨词表重名**且**同名同义**的码 —— 这些可以共用一条译名。
     *
     * <p>不在这张表里的重名码会被下面那条判据拦下：它必须有一个**按词表区分**的独立词条
     * （实例：{@code unknown} 在"失败原因"里是"没有交代原因"、在"页型"里是"判不出是什么页"，
     * 共用一条会印出"页面类型分布 原因未知×100"这种错话）。
     */
    private static final Set<String> SAME_MEANING_SHARED = Set.of("ok", "timeout");

    /** `stats.py` 里还有个**标量**取值域：未知检索源/无法解析的主机名都归并到它。 */
    private static final String OWNED_SCALAR_IN_STATS = "OTHER_DOMAIN";

    /** HTTP 状态类（`2xx`…`5xx`）不译 —— 按**形状**排除，不硬编码那四个值。 */
    private static final Pattern HTTP_CLASS = Pattern.compile("\\dxx");

    /** Python 里的元组字面量：`REASONS = ( "a", "b", )`。 */
    private static final Pattern TUPLE = Pattern.compile("(?s)%s\\s*=\\s*\\((.*?)\\)");
    /** Python 里的标量字面量：`OTHER_DOMAIN = "other"`。 */
    private static final Pattern SCALAR = Pattern.compile("(?m)^%s\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Test
    void labelTableMatchesTheCrawlerVocabularyExactly() throws IOException {
        Set<String> owned = new TreeSet<>();
        collect(owned, OUTCOME_PY, OWNED_TUPLES);
        collect(owned, STATS_PY, OWNED_TUPLES_IN_STATS);
        owned.add(scalar(STATS_PY, OWNED_SCALAR_IN_STATS));
        owned.removeIf(code -> HTTP_CLASS.matcher(code).matches());
        assertTrue(owned.size() > 15, "词表解析疑似失败（只有 " + owned.size() + " 个码，假绿保护）");

        Set<String> mirrored = codeKeys();
        assertTrue(mirrored.size() > 15, "译名表解析疑似失败（只有 " + mirrored.size() + " 个 key）");

        Set<String> missing = new TreeSet<>(owned);
        missing.removeAll(mirrored);
        Set<String> extra = new TreeSet<>(mirrored);
        extra.removeAll(owned);

        if (!missing.isEmpty() || !extra.isEmpty()) {
            fail("界面译名表与爬虫词表不一致（两集合必须相等）：\n"
                    + "  爬虫有、界面缺（新增原因未补译名）: " + missing + "\n"
                    + "  界面有、爬虫无（死词条或界面自行发明）: " + extra);
        }
        assertEquals(owned, mirrored);
    }

    private static void collect(Set<String> into, Path path, List<String> tupleNames) throws IOException {
        Path resolved = resolve(path);
        assertTrue(Files.exists(resolved), "找不到爬虫词表：" + resolved);
        for (String name : tupleNames) {
            into.addAll(tupleValues(resolved, name));
        }
    }

    /** 相对本模块解析到仓库内的文件路径（测试的工作目录是模块目录）。 */
    private static Path resolve(Path relative) {
        return MODULE.resolve(relative).normalize();
    }

    /** 取 Python 里某个标量字符串常量的值。 */
    private static String scalar(Path path, String name) throws IOException {
        Path resolved = MODULE.resolve(path).normalize();
        assertTrue(Files.exists(resolved), "找不到爬虫词表：" + resolved);
        Matcher m = Pattern.compile(String.format(SCALAR.pattern(), name))
                .matcher(Files.readString(resolved, StandardCharsets.UTF_8));
        if (!m.find()) {
            fail("在 " + resolved.getFileName() + " 里找不到标量 " + name);
        }
        return m.group(1);
    }

    @Test
    void bothDictionariesCoverTheSameCodes() throws IOException {
        List<String> src = Files.readAllLines(I18N, StandardCharsets.UTF_8);
        assertEquals(codesIn(src, "zh"), codesIn(src, "en"),
                "zh / en 的 code.* 词条必须成对出现 —— 只译一侧会让界面在另一种语言下显示 key");
    }

    /**
     * **同名不同义**是最容易悄悄说错的一类：同一个码出现在两个词表里，含义却不同
     * （实例：{@code unknown} 在失败原因里是"没有交代原因"，在页型里是"判不出是什么页"）。
     * 界面上若共用一条译名，就会印出"页面类型分布 原因未知×100"这种**看着像结论的错话**。
     *
     * <p>本判据不比较语义（那是人的判断），而是**逼人做一次判断**：
     * 跨词表重名的码，要么显式声明"同名同义"，要么就必须有一个按词表区分的独立词条。
     * 新增一个撞名的码时它会红，而不是等到界面上被人看见。
     */
    @Test
    void sharedCodesAreEitherSameMeaningOrDisambiguated() throws IOException {
        Map<String, Set<String>> byVocabulary = new LinkedHashMap<>();
        byVocabulary.put("REASONS", tupleValues(resolve(OUTCOME_PY), "REASONS"));
        byVocabulary.put("PAGE_KINDS", tupleValues(resolve(OUTCOME_PY), "PAGE_KINDS"));
        byVocabulary.put("STATUS_CLASSES", tupleValues(resolve(STATS_PY), "STATUS_CLASSES"));
        byVocabulary.put("RESULT_CLASSES", tupleValues(resolve(STATS_PY), "RESULT_CLASSES"));

        Map<String, Set<String>> vocabulariesOf = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : byVocabulary.entrySet()) {
            for (String code : e.getValue()) {
                vocabulariesOf.computeIfAbsent(code, k -> new TreeSet<>()).add(e.getKey());
            }
        }

        Set<String> ambiguous = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : vocabulariesOf.entrySet()) {
            if (e.getValue().size() > 1 && !SAME_MEANING_SHARED.contains(e.getKey())) {
                ambiguous.add(e.getKey());
            }
        }

        List<String> src = Files.readAllLines(I18N, StandardCharsets.UTF_8);
        Set<String> pageKindLabels = keysWithPrefix(src, "pagekind.");
        Set<String> unexplained = new TreeSet<>();
        for (String code : ambiguous) {
            if (!pageKindLabels.contains(code)) {
                unexplained.add(code + "（出现在 " + vocabulariesOf.get(code) + "，但没有独立词条）");
            }
        }
        if (!unexplained.isEmpty()) {
            fail("这些码跨词表重名、含义却可能不同 —— 必须二选一：\n"
                    + "  ① 确认同名同义 ⇒ 加进 SAME_MEANING_SHARED；\n"
                    + "  ② 含义不同 ⇒ 给它一个按词表区分的独立词条（如 `pagekind.<码>`）。\n"
                    + "  待处置： " + unexplained);
        }
    }

    /** 指定前缀（含点）的词条 key，去掉前缀（实例：`pagekind.unknown` → `unknown`）。 */
    private static Set<String> keysWithPrefix(List<String> src, String prefix) {
        Set<String> keys = new TreeSet<>();
        boolean in = false;
        Pattern keyLine = Pattern.compile("^\\s*\"" + Pattern.quote(prefix) + "([\\w-]+)\"\\s*:");
        for (String line : src) {
            if (!in) {
                if (line.trim().startsWith("zh: {")) {
                    in = true;
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
        return keys;
    }

    /** 取 Python 里某个元组字面量的全部字符串值。 */
    private static Set<String> tupleValues(Path path, String name) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(String.format(TUPLE.pattern(), name)).matcher(text);
        if (!m.find()) {
            fail("在 " + path.getFileName() + " 里找不到元组 " + name);
        }
        Set<String> values = new LinkedHashSet<>();
        Matcher q = QUOTED.matcher(m.group(1));
        while (q.find()) {
            values.add(q.group(1));
        }
        return values;
    }

    /** `i18n.js` 里 `code.*` 词条的**码**部分（去掉 `code.` 前缀）。 */
    private static Set<String> codeKeys() throws IOException {
        return codesIn(Files.readAllLines(I18N, StandardCharsets.UTF_8), "zh");
    }

    /** 指定语言词典块里的 `code.*` key 集合。 */
    private static Set<String> codesIn(List<String> src, String lang) {
        Set<String> codes = new TreeSet<>();
        boolean in = false;
        Pattern keyLine = Pattern.compile("^\\s*\"code\\.([\\w-]+)\"\\s*:");
        for (String line : src) {
            if (!in) {
                if (line.trim().startsWith(lang + ": {")) {
                    in = true;
                }
                continue;
            }
            if (line.trim().startsWith("}")) {
                break;
            }
            Matcher m = keyLine.matcher(line);
            if (m.find()) {
                codes.add(m.group(1));
            }
        }
        return codes;
    }
}
