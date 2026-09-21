package com.gptr.engine.write;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 引用核验器（URL 规范化后比对授权来源）：报告中的引用 URL
 * 必须属于授权来源集合（canonical 化后比较）。
 *
 * <p>比较前对两侧 URL 做规范化（canonicalize）：scheme/host 小写、去默认端口、
 * 去 fragment、剥常见跟踪参数（utm 系列/gclid/fbclid/ref/source/spm）、路径尾斜杠
 * 归一。手写解析：不依赖 {@link java.net.URI}（其对非 ASCII 路径如维基中文 URL
 * 会抛异常导致漏判）。
 *
 * <p>{@code citedUrls}/{@code verify} 返回报告原文 URL（展示用），比较用 canonical。
 */
public class CitationVerifier {

    /** 中文句读标点：URL 后常紧跟（真实 URL 不含未编码全角标点）。 */
    private static final String CJK_PUNCT = "。，；：！？、」』》】）";

    /** 已知跟踪参数：剥离不改变页面内容，可安全归一。 */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "gclid", "fbclid", "ref", "source", "spm");

    private final Set<String> authorizedCanonical;

    public CitationVerifier(List<String> authorizedUrls) {
        Set<String> set = new LinkedHashSet<>();
        if (authorizedUrls != null) {
            for (String url : authorizedUrls) {
                String c = canonicalize(url);
                if (c != null) {
                    set.add(c);
                }
            }
        }
        this.authorizedCanonical = set;
    }

    /** 报告中的引用 URL（去重、保序、返回原文）。 */
    public List<String> citedUrls(String report) {
        return report == null || report.isBlank() ? List.of() : extractUrls(report);
    }

    /**
     * 报告引用统计（链路漏斗用，2026-09-13）：
     * <ul>
     *   <li>{@code total} —— <b>总引证次数</b>（同一 URL 被引 5 次即计 5 次）；</li>
     *   <li>{@code distinctRaw} —— 按原文去重的来源数；</li>
     *   <li>{@code distinctCanonical} —— 按 canonical 去重的来源数（合并尾斜杠/跟踪参数变体）。</li>
     * </ul>
     * 同时给次数与源数，是为了让"<b>信源单一的自引刷屏</b>"（次数高、源数低）与
     * "<b>多源交叉验证</b>"（次数与源数同量级）一眼可辨；两个源数不等则说明同一 URL
     * 存在多种写法（尾斜杠/跟踪参数）。
     */
    public static CitationStats citationStats(String report) {
        if (report == null || report.isBlank()) {
            return new CitationStats(0, 0, 0);
        }
        List<String> all = extractUrls(report, false);
        Set<String> raw = new LinkedHashSet<>(all);
        Set<String> canonical = new LinkedHashSet<>();
        for (String u : all) {
            String c = canonicalize(u);
            canonical.add(c == null ? u : c);
        }
        return new CitationStats(all.size(), raw.size(), canonical.size());
    }

    /** 引用统计：total=引证次数；两个源数见 {@link #citationStats}。 */
    public record CitationStats(int total, int distinctRaw, int distinctCanonical) {
    }

    /** 核验：返回授权来源 ∩ 报告引用（canonical 匹配；返回报告原文，保序）。 */
    public List<String> verify(String report) {
        List<String> out = new ArrayList<>();
        for (String url : citedUrls(report)) {
            String c = canonicalize(url);
            if (c != null && authorizedCanonical.contains(c)) {
                out.add(url);
            }
        }
        return out;
    }

    /** 报告是否含有任何未授权的引用 URL（幻觉引用检测，canonical 匹配）。 */
    public boolean hasUnauthorizedCitations(String report) {
        for (String url : citedUrls(report)) {
            String c = canonicalize(url);
            if (c == null || !authorizedCanonical.contains(c)) {
                return true;
            }
        }
        return false;
    }

    // 【2026-09-14 删除】此处原有一个 `public String authorizedOriginalFor(String reportUrl)`
    // （canonical → 授权原文）与配套可变字段 `canonicalToOriginal`：全仓 0 调用方
    // （main/test/benchmark 均无），故一并移除 —— 零调用 API 只增加读者负担。
    // 将来若做引用溯源 UI 需要它，请连同消费方与测试一起加回。

    /**
     * 提取 URL（手写扫描器）：URL 内 {@code (deep_learning)} 的 {@code )} 属于 URL
     * （括号配对），markdown 链接闭合的 {@code )}（open==0 时）是终止符；
     * 空白/[]&gt;"',;/中文句读标点/行尾终止。汉字正文因 markdown 结构被 {@code )}
     * 先终止，不会被吞入 URL。
     */
    private static List<String> extractUrls(String text) {
        return extractUrls(text, true);
    }

    /**
     * @param dedupe true=按原文去重（授权核验用，现有语义）；false=保留每次出现
     *               （引用次数统计用——同一 URL 被引 5 次即计 5 次）
     */
    private static List<String> extractUrls(String text, boolean dedupe) {
        List<String> urls = new ArrayList<>();
        if (text == null) {
            return urls;
        }
        int n = text.length();
        for (int i = 0; i < n; ) {
            int start;
            if (text.regionMatches(true, i, "https://", 0, 8)) {
                start = i;
            } else if (text.regionMatches(true, i, "http://", 0, 7)) {
                start = i;
            } else {
                i++;
                continue;
            }
            int j = start;
            int open = 0;
            while (j < n) {
                char c = text.charAt(j);
                if (c == '(') {
                    open++;
                } else if (c == ')') {
                    if (open == 0) {
                        break; // markdown/括号闭合
                    }
                    open--;
                } else if (c == ']' || c == '>' || c == '"' || c == '\'' || c == ','
                        || c == '`' || Character.isWhitespace(c)) {
                    break;
                    // 注：';' 是合法 path/query 字符（RFC 3986 pchar），不再作终止符
                } else if (CJK_PUNCT.indexOf(c) >= 0) {
                    break; // 中文句读标点终止
                }
                j++;
            }
            String url = stripTrailing(text.substring(start, j));
            url = balanceParens(url);
            if (!url.isEmpty() && (!dedupe || !urls.contains(url))) {
                urls.add(url);
            }
            i = Math.max(j, start + 8);
        }
        return urls;
    }

    /** authority 解析结果；{@code authEnd} 为 path/query 的起点。 */
    private record Authority(String host, int port, int authEnd) {
    }

    /** path 与 query 的归一结果。 */
    private record PathQuery(String path, String query) {
    }

    /**
     * URL 规范化：scheme/host 小写、去默认端口、去 fragment、剥跟踪参数、
     * 路径尾斜杠归一。手写解析（不依赖 {@link java.net.URI}——其对非 ASCII
     * 路径如维基中文 URL 会抛异常导致漏判）。解析失败或非 http(s) 返回 null。
     */
    public static String canonicalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        // 两侧同构：入口统一剥尾部标点（ASCII + 中文全角），避免"报告侧剥、授权侧不剥"
        // 的不对称误判
        String s = stripTrailing(url.trim());
        int schemeEnd = s.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        String scheme = s.substring(0, schemeEnd).toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }

        Authority authority = parseAuthority(s, schemeEnd + 3);
        if (authority == null) {
            return null;
        }
        String host = authority.host();
        int port = authority.port();
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            port = -1; // 去默认端口
        }

        PathQuery pq = normalizePathQuery(s.substring(authority.authEnd()));

        StringBuilder sb = new StringBuilder(scheme).append("://");
        // IPv6 host 回包方括号（裸拼产出非法 URL）
        if (host.contains(":")) {
            sb.append('[').append(host).append(']');
        } else {
            sb.append(host);
        }
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(pq.path());
        if (pq.query() != null && !pq.query().isEmpty()) {
            sb.append('?').append(pq.query());
        }
        return sb.toString();
    }

    /** 解析 {@code scheme://} 之后的 authority（host[:port]；剥 userinfo，IPv6 用 {@code [ ]}）。
     *  失败返回 null。原 {@code canonicalize} 内联段逐字搬入。 */
    private static Authority parseAuthority(String s, int restStart) {
        int authEnd = s.length();
        for (int i = restStart; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authEnd = i;
                break;
            }
        }
        String auth = s.substring(restStart, authEnd);
        int at = auth.lastIndexOf('@');
        if (at >= 0) {
            auth = auth.substring(at + 1);
        }
        if (auth.isEmpty()) {
            return null;
        }
        String host;
        int port = -1;
        if (auth.startsWith("[")) {
            int cb = auth.indexOf(']');
            if (cb < 0) {
                return null;
            }
            host = auth.substring(1, cb);
            if (cb + 1 < auth.length() && auth.charAt(cb + 1) == ':') {
                try {
                    port = Integer.parseInt(auth.substring(cb + 2));
                } catch (NumberFormatException ignored) {
                    // 保留 -1
                }
            }
        } else {
            int colon = auth.lastIndexOf(':');
            if (colon >= 0) {
                try {
                    port = Integer.parseInt(auth.substring(colon + 1));
                    host = auth.substring(0, colon);
                } catch (NumberFormatException e) {
                    host = auth; // 冒号非端口（罕见），整体当 host
                    port = -1;
                }
            } else {
                host = auth;
            }
        }
        if (host.isEmpty()) {
            return null;
        }
        return new Authority(host.toLowerCase(), port, authEnd);
    }

    /** path + query 归一：剥 fragment、percent 编码 unreserved 归一、根路径与尾斜杠归一。
     *  原 {@code canonicalize} 内联段逐字搬入（其中 {@code if (path.isEmpty()) path = "";}
     *  是空操作，已删）。 */
    private static PathQuery normalizePathQuery(String rawTail) {
        String tail = rawTail;
        int hash = tail.indexOf('#');
        if (hash >= 0) {
            tail = tail.substring(0, hash);
        }
        int qIdx = tail.indexOf('?');
        String path = qIdx >= 0 ? tail.substring(0, qIdx) : tail;
        String query = qIdx >= 0 ? stripTrackingParams(tail.substring(qIdx + 1)) : null;
        path = decodeUnreserved(path);
        query = query == null ? null : decodeUnreserved(query);
        if (path.equals("/")) {
            // 根路径：无 query 时归一为空（example.com/ == example.com）；带 query 时保留
            // "/"（example.com/?q=1 是更标准的形态，避免 example.com?q=1 怪异串）
            path = query == null || query.isEmpty() ? "" : "/";
        } else if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return new PathQuery(path, query);
    }

    /**
     * percent 编码归一：把 %XX 序列按 UTF-8 解码（含多字节中文），但解码结果若为
     * URL 保留字符（/?#[]@&amp;=;, 等）则保留原编码（防止 %2F 被误还原改变路径结构）。
     */
    private static String decodeUnreserved(String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length() && hex(text.charAt(i + 1)) >= 0
                    && hex(text.charAt(i + 2)) >= 0) {
                // 收集连续 %XX 字节
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int j = i;
                while (j + 2 < text.length() && text.charAt(j) == '%'
                        && hex(text.charAt(j + 1)) >= 0 && hex(text.charAt(j + 2)) >= 0) {
                    bytes.write(hex(text.charAt(j + 1)) * 16 + hex(text.charAt(j + 2)));
                    j += 3;
                }
                String decoded = tryDecodeUtf8(bytes.toByteArray());
                if (decoded != null && containsNoReserved(decoded)) {
                    sb.append(decoded);
                    i = j;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }

    private static String tryDecodeUtf8(byte[] bytes) {
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 注：本 catch 实际不可达——new String(bytes, UTF_8) 对非法字节序列用 U+FFFD
            // 替换而不抛异常。保留以防御未来改用 CharsetDecoder（REPORT 语义）时抛错。
            return null;
        }
    }

    /** URL 保留/分隔字符：解码出这些字符说明不应还原（保持原编码）。 */
    private static boolean containsNoReserved(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("/?#[]@&=;,+$!*'()%:".indexOf(c) >= 0 || c == '\\' || c == '"' || c == '<' || c == '>') {
                return false;
            }
        }
        return true;
    }

    /** 剥除已知跟踪参数；其余参数按名排序重组（参数顺序不敏感）。 */
    private static String stripTrackingParams(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : query.split("&")) {
            String name = pair;
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                name = pair.substring(0, eq);
            }
            if (TRACKING_PARAMS.contains(name.toLowerCase())) {
                continue;
            }
            kept.add(pair);
        }
        if (kept.isEmpty()) {
            return "";
        }
        kept.sort(Comparator.comparing(CitationVerifier::sortKey));
        return String.join("&", kept);
    }

    /** 排序键：参数名（去掉 {@code =值}）小写归一（原 comparator lambda 体逐字搬入）。 */
    private static String sortKey(String p) {
        String name = p;
        int eq = p.indexOf('=');
        if (eq >= 0) {
            name = p.substring(0, eq);
        }
        return decodeUnreserved(name.toLowerCase());
    }

    private static String stripTrailing(String url) {
        int end = url.length();
        // ASCII 标点 + 中文全角句读标点
        while (end > 0 && ".,;:!?".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        while (end > 0 && CJK_PUNCT.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * 括号平衡：极端情况下 URL 尾部仍带多余 {@code )} 时剥除
     * （扫描器已括号配对，此处兜底）。
     */
    static String balanceParens(String url) {
        int open = 0;
        int close = 0;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '(') {
                open++;
            } else if (c == ')') {
                close++;
            }
        }
        int extra = close - open;
        while (extra > 0 && url.endsWith(")")) {
            url = url.substring(0, url.length() - 1);
            extra--;
        }
        return url;
    }
}
