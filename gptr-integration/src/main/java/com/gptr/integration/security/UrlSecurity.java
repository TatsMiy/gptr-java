package com.gptr.integration.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * 出站 URL 安全校验（对应 Python 原版 utils/url_security.py 的轻量 Java 版）。
 *
 * <p>用途：webhook callbackUrl / 未来 Java 直连抓取等出站请求的 SSRF 防护——
 * 只允许 http/https，解析 host 后拒绝私网/环回/链路本地/组播/未指定地址
 * （防服务端被诱导请求内网资源、云 metadata 端点等）。
 *
 * <p>已知缺口（与原版一致）：DNS 解析与真实请求之间存在 TOCTOU 窗口（域名可能
 * 在两次解析间切换指向）；无域名黑名单/端口校验。安全默认拒绝私网，
 * 测试/本机回调场景经 {@code allowPrivateUrls=true} 显式豁免。
 */
public final class UrlSecurity {

    private UrlSecurity() {
    }

    /**
     * 校验出站 URL 安全；不安全时抛 {@link IllegalArgumentException}。
     *
     * @param url              待校验 URL
     * @param allowPrivateUrls 是否放行解析到私网/环回地址的 host（本机回调测试用）
     */
    public static void assertSafeHttpUrl(String url, boolean allowPrivateUrls) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is blank");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("malformed url: " + url, e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("url must be http(s): " + url);
        }
        if (allowPrivateUrls) {
            return;
        }
        if (isPrivateHost(host)) {
            throw new IllegalArgumentException("url resolves to private/loopback address (SSRF guard): " + url);
        }
    }

    /** host 字面或任一解析地址为私网类地址（含 localhost 字面）。 */
    public static boolean isPrivateHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.toLowerCase();
        // java.net.URI.getHost() 对 IPv6 字面返回带方括号形式（"[::1]"）——
        // 先剥括号再做字面解析（否则一切 IPv6 URL 都 fail-closed 误拦）
        if (h.length() >= 2 && h.charAt(0) == '[' && h.charAt(h.length() - 1) == ']') {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost") || h.endsWith(".localhost")) {
            return true;
        }
        // 字面 IP 先查（避免域名解析开销与失败）
        InetAddress literal = parseLiteral(h);
        if (literal != null) {
            return isPrivateAddress(literal);
        }
        try {
            int resolved = 0;
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                resolved++;
                if (isPrivateAddress(addr)) {
                    return true;
                }
            }
            // 解析到至少一个地址且全为公网 → 放行；解析 0 地址（罕见）→ fail-closed
            return resolved == 0;
        } catch (UnknownHostException e) {
            // C3-S10 fail-closed：解析失败视为不安全（死域/DNS 状态不可知），拒绝而非放行
            return true;
        }
    }

    /** 解析字面 IP（IPv4/IPv6）；非字面返回 null。 */
    private static InetAddress parseLiteral(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** 私网/环回/链路本地/组播/未指定/站点本地地址判定（IPv4+IPv6）。
     *  4：IPv4-mapped（::ffff:a.b.c.d）与 IPv4-compatible（::a.b.c.d，RFC 4291
     *  已废弃）前缀显式解包后按 IPv4 私有谓词判定——防御 JDK 返回 Inet6Address 形态
     * （getByAddress 手工构造/未来 JDK 行为）时被当作公网放行。公网 mapped 地址
     * （::ffff:8.8.8.8）解包后自然放行，不一刀切。 */
    public static boolean isPrivateAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 16) {
            boolean mapped = true;
            boolean compat = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mapped = false;
                    compat = false;
                    break;
                }
            }
            if (mapped && (b[10] != (byte) 0xff || b[11] != (byte) 0xff)) {
                mapped = false;
            }
            if (compat && (b[10] != 0 || b[11] != 0)) {
                compat = false;
            }
            if (mapped || compat) {
                byte[] v4 = {b[12], b[13], b[14], b[15]};
                try {
                    return isPrivateAddress(InetAddress.getByAddress(v4)); // Inet4 递归，不再回 16B 分支
                } catch (UnknownHostException e) {
                    return true; // fail-closed
                }
            }
            // C3-S10：JDK isSiteLocalAddress 对 IPv6 只认废弃的 fec0::/10，
            // 不含 ULA fc00::/7（fd00::/8 常用内网前缀）——显式补判
            return (b[0] & 0xfe) == 0xfc;
        }
        return false;
    }
}
