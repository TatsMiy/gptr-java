package com.gptr.engine.search;

import java.net.URI;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 同源镜像判定。
 *
 * <p>问题：同一篇文章常有多个入口域名/路径，检索会同时返回多条，例如 q08 实测：
 * <pre>
 *   https://c.m.163.com/news/a/KN6E4VRB0511AQHO.html
 *   https://www.163.com/dy/article/KN6E4VRB0511AQHO.html
 *   https://m.163.com/dy/article/KN6E4VRB0511AQHO.html
 * </pre>
 * 三者共享文号 {@code KN6E4VRB0511AQHO}，实为同一内容。抓取配额此前只做 URL 精确去重，
 * 三个镜像各占一个名额；后果有两个（q08 实证）：
 * <ul>
 *   <li>配额浪费：该组 5 条命中里 3 条是同一篇，真实可选来源被挤掉；</li>
 *   <li>素材重复：该文号在 evidenceBank 中产出 <b>40 条 note</b>，报告内同一 URL 被引 29 次、
 *       同一组证据跨三节复现 —— 即判官所说的"重复引用多"的机制来源。</li>
 * </ul>
 *
 * <p>判定规则（保守，宁可漏合并也不误合并）：
 * <ol>
 *   <li><b>同主体</b>：host 去掉右端通用后缀词后的主体相同——
 *       {@code c.m.163.com} 与 {@code www.163.com} → {@code 163.com}；
 *       {@code hub.baai.ac.cn} → {@code baai.ac.cn}（{@code ac}/{@code cn} 都属通用词）；</li>
 *   <li><b>共享长 ID</b>：路径与查询串里存在同一个长度 ≥9 的字母数字 token
 *       （163 的 {@code KN6E4VRB0511AQHO}、CSDN 的 9 位 article id、juejin 的 19 位 note id）。</li>
 * </ol>
 * 两条同时满足才判为镜像。任一不满足 → 返回 URL 自身，等价于原有的精确去重，
 * 因此本类<b>不会</b>比现状更激进地合并来源。
 *
 * <p>注意（范围声明）：本机制解决"同源重复占位"，<b>不解决</b>"组内按检索序取前 N 导致
 * 高价值来源落选"——后者属配额选择策略问题，需另行处理。
 */
public final class SourceMirror {

    /** 通用域名后缀词：从右往左跳过这些段，第一个非通用词起算为主体。 */
    private static final Set<String> GENERIC = Set.of(
            "com", "net", "org", "gov", "edu", "ac", "co", "cn", "uk", "jp", "hk", "tw",
            "io", "ai", "me", "info", "biz", "dev", "app", "site", "online", "tech");

    /** 长 ID token：连续字母数字 ≥9 位，且**必须含数字**——纯字母串多为路径里的普通单词
     *  （如 {@code /ai-models/llm-benchmark-tests/35} 里的 {@code benchmark} 正好 9 个字母），
     *  若不排除会把不同文章误判为同一篇。 */
    private static final Pattern LONG_ID = Pattern.compile("[A-Za-z0-9]{9,}");

    private SourceMirror() {
    }

    /**
     * 镜像键：同主体且共享长 ID 的 URL 得到同一键；无法判定时返回 URL 自身。
     * 调用方按此键去重即可（保持首次出现顺序）。
     */
    public static String mirrorKey(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String body = bodyOf(url);
        String id = longestId(url);
        if (body == null || id == null) {
            return url;
        }
        return body + "|" + id;
    }


    /** 域名主体：从右往左去掉通用后缀词，返回第一个非通用段及其右侧全部段；全通用则返回 host。 */
    static String bodyOf(String url) {
        String host = hostOf(url);
        if (host == null || host.isBlank()) {
            return null;
        }
        host = host.toLowerCase();
        if (host.startsWith("[")) { // IPv6 字面量：不判镜像
            return null;
        }
        String[] parts = host.split("\\.");
        if (parts.length <= 1) {
            return host;
        }
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!GENERIC.contains(parts[i])) {
                // 从首个非泛化标签起拼回：用「先放首段、后续一律前置 '.'」避免
                // 逐次判断"是否首段"——那会多一层控制嵌套。
                StringBuilder sb = new StringBuilder(parts[i]);
                for (int j = i + 1; j < parts.length; j++) {
                    sb.append('.').append(parts[j]);
                }
                return sb.toString();
            }
        }
        return host;
    }

    /** 路径 + 查询串中最长且含数字的字母数字 token（≥9 位）；不含 host，避免域名数字被当 ID。 */
    static String longestId(String url) {
        String rest = pathAndQuery(url);
        if (rest == null || rest.isBlank()) {
            return null;
        }
        Matcher m = LONG_ID.matcher(rest);
        String best = null;
        while (m.find()) {
            String cand = m.group();
            if (!hasDigit(cand)) {
                continue; // 纯字母串：多为普通单词，不作 ID
            }
            if (best == null || cand.length() > best.length()) {
                best = cand;
            }
        }
        return best;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            URI u = new URI(url.trim());
            if (u.getHost() != null) {
                return u.getHost();
            }
        } catch (Exception ignored) {
            // 落到正则回退
        }
        Matcher m = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)").matcher(url.trim());
        if (m.find()) {
            String hp = m.group(1);
            int at = hp.indexOf('@');
            if (at >= 0) {
                hp = hp.substring(at + 1);
            }
            int colon = hp.lastIndexOf(':');
            if (colon > 0 && hp.indexOf(']') < colon) {
                hp = hp.substring(0, colon);
            }
            return hp;
        }
        return null;
    }

    private static String pathAndQuery(String url) {
        try {
            URI u = new URI(url.trim());
            String p = u.getRawPath() == null ? "" : u.getRawPath();
            String q = u.getRawQuery() == null ? "" : u.getRawQuery();
            return p + " " + q;
        } catch (Exception ignored) {
            // 有意忽略异常：走到这里说明 url 不是合法 URI（相对路径/裸主机名等），
            // 本身就该走下面的正则兜底分支；异常信息对定位调用方问题没有增量价值。
            Matcher m = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]+([^#]*)").matcher(url.trim());
            return m.find() ? m.group(1) : url;
        }
    }
}
