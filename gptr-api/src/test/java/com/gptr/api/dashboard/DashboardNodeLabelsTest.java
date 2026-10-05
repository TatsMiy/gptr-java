package com.gptr.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 图节点 id 的**跨语言对账**：界面的"人话节点名"必须与引擎的节点常量逐个对上。
 *
 * <p>与 {@link DashboardCodeLabelsTest} 同一族（都是"镜像 vs 主人"的机械守卫），
 * 防的还是同一类静默漂移：引擎**新加一个节点**而界面没跟上时，界面会**退化为显示节点 id**
 * —— 不空白、不报错、也不会有任何东西变红，于是没人知道要补。
 *
 * <p>另一头也拦：界面侧多出一个引擎里不存在的节点名（拼错或残留），那是永不会出现的死词条。
 *
 * <p>读静态文件即可，**不需要浏览器、不需要 Spring context、不需要跑图**。
 */
class DashboardNodeLabelsTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();
    private static final Path I18N = Path.of("src", "main", "resources", "static", "dashboard", "i18n.js");
    private static final Path GRAPH =
            Path.of("..", "gptr-engine", "src", "main", "java", "com", "gptr", "engine", "epoc",
                    "DeepResearchGraph.java");

    /** 引擎里的节点常量：`public static final String NODE_X = "node_id";`。 */
    private static final Pattern NODE_CONST = Pattern.compile("NODE_\\w+\\s*=\\s*\"([^\"]+)\"");

    @Test
    void nodeLabelTableMatchesTheEngineConstantsExactly() throws IOException {
        Set<String> owned = nodeIdsFromEngine();
        Set<String> mirrored = nodeKeysInI18n("zh");

        assertTrue(owned.size() >= 5, "引擎节点解析疑似失败（只有 " + owned.size() + " 个，假绿保护）");
        assertTrue(mirrored.size() >= 5, "界面节点名解析疑似失败（只有 " + mirrored.size() + " 个）");

        Set<String> missing = new TreeSet<>(owned);
        missing.removeAll(mirrored);
        Set<String> extra = new TreeSet<>(mirrored);
        extra.removeAll(owned);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            fail("界面节点名与引擎节点常量不一致（两集合必须相等）：\n"
                    + "  引擎有、界面缺（新加节点未补人话名，界面会显示原始 id）: " + missing + "\n"
                    + "  界面有、引擎无（拼错或残留的死词条）: " + extra);
        }
        assertEquals(owned, mirrored);
    }

    @Test
    void bothDictionariesCarryTheSameNodes() throws IOException {
        List<String> src = Files.readAllLines(I18N, StandardCharsets.UTF_8);
        assertEquals(nodeKeysInI18n(src, "zh"), nodeKeysInI18n(src, "en"),
                "zh / en 的 node.* 词条必须成对 —— 只译一侧会让界面在另一种语言下显示 key");
    }

    private static Set<String> nodeIdsFromEngine() throws IOException {
        Path path = MODULE.resolve(GRAPH).normalize();
        assertTrue(Files.exists(path), "找不到引擎图定义：" + path);
        Matcher m = NODE_CONST.matcher(Files.readString(path, StandardCharsets.UTF_8));
        Set<String> ids = new LinkedHashSet<>();
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /** 指定语言词典块里的 `node.*` key（去掉 `node.` 前缀）。 */
    private static Set<String> nodeKeysInI18n(String lang) throws IOException {
        return nodeKeysInI18n(Files.readAllLines(I18N, StandardCharsets.UTF_8), lang);
    }

    private static Set<String> nodeKeysInI18n(List<String> src, String lang) {
        Set<String> keys = new TreeSet<>();
        boolean in = false;
        Pattern keyLine = Pattern.compile("^\\s*\"node\\.(\\w+)\"\\s*:");
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
                keys.add(m.group(1));
            }
        }
        return keys;
    }
}
