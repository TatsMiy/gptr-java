package com.gptr.engine.epoc;

import com.gptr.engine.search.SourceMirror;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 抓取配额调度：从检索结果里选出"本轮该抓哪些 URL"，是 {@code action} 的唯一取名入口。
 *
 * <p>两档策略（由调用方按 {@code scrapeQuotaMode}/{@code coverMode} 决定）：
 * <ul>
 *   <li><b>linked 档</b> —— {@link #pickRoundRobin}：按查询组轮转，每组保底名额；某组 URL
 *       全被 visited/已选则自动跳过并把名额让给其他组（不浪费配额）。组内保持 search 原序，
 *       跨组同 URL 只抓一次。</li>
 *   <li><b>flat 档</b> —— {@link #pickSequential}：扁平候选列表按序取前 N。</li>
 * </ul>
 * 两档共用<b>同源镜像去重</b>（见 {@link #mirrorKeys}）：同一篇文章的多个域名/路径入口只占
 * 一个名额，跨层同理（visited 里任一镜像即视为已抓）。
 *
 * <p><b>本类分块地图</b>（按方法名定位——行号会腐烂，故本注释刻意不写行号）：
 * <ol>
 *   <li><b>取名</b> —— {@code pickRoundRobin}（组轮转）/ {@code pickSequential}（扁平）。</li>
 *   <li><b>条目解析</b> —— {@code urlOfItem}（条目文本 → URL）/ {@code mirrorKeys}（URL 列表 → 镜像键集）。</li>
 *   <li><b>观测</b> —— {@code groupDistribution}（"0:3 1:2 …" 组分布，轮转公平性的验收观测值）。</li>
 * </ol>
 *
 * <p>纯函数、无状态：不读配置、不碰 state、不调 LLM。
 */
final class ScrapeQuotaScheduler {

    private ScrapeQuotaScheduler() {
    }

    /** 抓取名单的组分布（"0:3 1:2 …"；flat 档为 "flat:N"）——轮转公平性的验收观测值。 */
    static String groupDistribution(List<Map.Entry<String, Integer>> picked) {
        if (picked.isEmpty()) {
            return "-";
        }
        if (picked.get(0).getValue() < 0) {
            return "flat:" + picked.size();
        }
        TreeMap<Integer, Integer> counts = new TreeMap<>();
        for (Map.Entry<String, Integer> e : picked) {
            counts.merge(e.getValue(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        counts.forEach((g, n) -> sb.append(g).append(':').append(n).append(' '));
        return sb.toString().trim();
    }

    /** 现状档取名（扁平候选列表按序取前 N，URL 去重、visited 过滤）。
     *  去重键用**同源镜像键**（同一篇文章的多域名/多路径入口只占一个名额，
     *  见 {@link com.gptr.engine.search.SourceMirror}）——q08 实证三个 163 镜像占满组内名额、
     *  该文号产出 40 条重复 note。跨层防重同理：visited 里任一镜像即视为已抓。 */
    static List<Map.Entry<String, Integer>> pickSequential(List<String> candidates,
                                                                     List<String> visited, int max) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        Set<String> visitedKeys = mirrorKeys(visited);
        for (String u : candidates) {
            if (u == null || u.isBlank()) {
                continue;
            }
            String key = SourceMirror.mirrorKey(u);
            if (visited.contains(u) || visitedKeys.contains(key)
                    || seen.contains(u) || seenKeys.contains(key)) {
                continue;
            }
            seen.add(u);
            seenKeys.add(key);
            out.add(Map.entry(u, -1)); // -1 = 扁平档无组归属
            if (max > 0 && out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /** visited URL 列表 → 镜像键集合（跨层防止同一文章的另一个入口被重复抓取）。 */
    private static Set<String> mirrorKeys(List<String> urls) {
        Set<String> keys = new HashSet<>();
        if (urls != null) {
            for (String u : urls) {
                keys.add(SourceMirror.mirrorKey(u));
            }
        }
        return keys;
    }

    /** 收集一组里未抓过（含同源镜像去重）的 URL，保持原序。 */
    private static void collectGroupUrls(List<String> group, List<String> visited,
                                         Set<String> visitedKeys, Set<String> into) {
        Set<String> groupKeys = new LinkedHashSet<>();
        for (String item : group) {
            String u = urlOfItem(item);
            if (u == null || visited.contains(u)) {
                continue;
            }
            String key = SourceMirror.mirrorKey(u);
            if (visitedKeys.contains(key) || !groupKeys.add(key)) {
                continue; // 该文章在本组已入选（或前层已抓过其镜像）
            }
            into.add(u);
        }
    }

    /** 从该组游标起取第一个未被占用的 URL；取到则推进游标并返回 {@code (url, 组号)}。 */
    private static Map.Entry<String, Integer> takeGroupUrl(List<String> us, int[] cursor, int g,
                                                           Set<String> taken) {
        while (cursor[g] < us.size()) {
            String u = us.get(cursor[g]++);
            if (taken.add(u)) {
                return Map.entry(u, g);
            }
        }
        return null;
    }

    /** 组轮转取名——每轮每组贡献 1 个"尚未选中"的新 URL，直到配额满或所有组耗尽。
     *  某组 URL 全被 visited/已选 → 自动跳过并把名额让给其他组（不浪费配额）。
     *  组内保持 search 原序（相关性序不破坏）；跨组同 URL 只抓一次。
     *  同源镜像去重——组内同一篇文章的多个入口只留首个，省下的名额由该组
     *  后续真实来源补上（q08 组0 三条 163 镜像曾占掉全部组内名额）。 */
    static List<Map.Entry<String, Integer>> pickRoundRobin(List<List<String>> groups,
                                                                    List<String> visited, int quota) {
        List<List<String>> perGroup = new ArrayList<>();
        Set<String> visitedKeys = mirrorKeys(visited);
        for (List<String> g : groups) {
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            if (g != null) {
                collectGroupUrls(g, visited, visitedKeys, urls);
            }
            perGroup.add(new ArrayList<>(urls));
        }
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        int[] cursor = new int[perGroup.size()];
        boolean progress = true;
        while (out.size() < quota && progress) {
            progress = false;
            for (int g = 0; g < perGroup.size(); g++) {
                Map.Entry<String, Integer> picked = takeGroupUrl(perGroup.get(g), cursor, g, taken);
                // ⚠️ 这里**不能** break：原来那个 break 跳的是 `takeGroupUrl` 内部的
                // 「找该组下一个未被占用 URL」循环，跳出后 for 还要继续，让后续组各贡献 1 个
                // —— 那正是组轮转的全部意义。写成 break 会让每轮只处理组 0，轮转静默失效。
                if (picked != null) {
                    out.add(picked);
                    progress = true;
                }
                if (out.size() >= quota) {
                    break;
                }
            }
        }
        return out;
    }

    /** 诊断用：从条目块的 "URL: " 行取 URL（无则 null）。 */
    static String urlOfItem(String item) {
        if (item == null) {
            return null;
        }
        int u = item.indexOf("URL: ");
        if (u < 0) {
            return null;
        }
        int e = item.indexOf('\n', u);
        String url = item.substring(u + 5, e < 0 ? item.length() : e).trim();
        return url.isBlank() ? null : url;
    }
}
