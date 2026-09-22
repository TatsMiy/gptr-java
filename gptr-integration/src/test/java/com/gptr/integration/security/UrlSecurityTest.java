package com.gptr.integration.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UrlSecurity 单测：webhook callbackUrl / 出站请求的 SSRF 防护。
 */
class UrlSecurityTest {

    @Test
    void rejectsNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("ftp://example.com/a", false));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("file:///etc/passwd", false));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("", false));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("not a url", false));
    }

    @Test
    void rejectsPrivateAndLoopbackByDefault() {
        // 回环/私网字面 IP：默认拒绝
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://127.0.0.1:8080/hook", false));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://localhost:8080/hook", false));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://192.168.1.10/hook", false));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://10.0.0.5/hook", false));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://169.254.169.254/latest/meta-data", false),
                "云 metadata 端点必须被拒");
    }

    @Test
    void allowPrivateUrlsExemptsLoopback() {
        // 本机回调（测试/内网部署）：显式豁免后放行
        UrlSecurity.assertSafeHttpUrl("http://127.0.0.1:8080/hook", true);
        UrlSecurity.assertSafeHttpUrl("http://localhost:8080/hook", true);
    }

    @Test
    void acceptsPublicHost() {
        // 公共域名：DNS 解析为公网地址 → 放行
        UrlSecurity.assertSafeHttpUrl("https://example.com/webhook", false);
    }

    @Test
    void isPrivateAddressDetection() {
        assertTrue(UrlSecurity.isPrivateHost("127.0.0.1"));
        assertTrue(UrlSecurity.isPrivateHost("localhost"));
        assertTrue(UrlSecurity.isPrivateHost("10.1.2.3"));
        assertTrue(UrlSecurity.isPrivateHost("192.168.0.1"));
        assertTrue(UrlSecurity.isPrivateHost("169.254.169.254"));
        assertTrue(UrlSecurity.isPrivateHost("::1"));
        assertFalse(UrlSecurity.isPrivateHost("example.com"));
    }

    @Test
    void ipv6UlaIsPrivate() {
        // JDK isSiteLocalAddress 不含 ULA fc00::/7——必须显式拦截
        assertTrue(UrlSecurity.isPrivateHost("fc00::1"), "ULA fc00::/7 应判私网");
        assertTrue(UrlSecurity.isPrivateHost("fd12:3456::1"), "ULA fd00::/8 应判私网");
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://[fd00::1]:8080/hook", false),
                "ULA 内网主机默认必须被拒");
        // 显式豁免后放行
        UrlSecurity.assertSafeHttpUrl("http://[fd00::1]:8080/hook", true);
    }

    @Test
    void unresolvableHostFailsClosed() {
        // fail-closed：DNS 解析失败视为不安全（.invalid TLD 永不解析）
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://definitely-not-a-real-host.invalid/hook", false),
                "解析失败的域名必须 fail-closed 拒绝");
    }

    // ------------------------------------------------------------------
    // 4：手工构造的 IPv4-mapped / IPv4-compatible Inet6Address 必须解包判定；
    // 公网 mapped 地址（::ffff:8.8.8.8）不得被一刀切误杀
    // ------------------------------------------------------------------

    private static java.net.InetAddress ipv6WithTail(byte[] tail16, int a, int b, int c, int d) {
        byte[] raw = new byte[16];
        System.arraycopy(tail16, 0, raw, 0, tail16.length);
        raw[12] = (byte) a;
        raw[13] = (byte) b;
        raw[14] = (byte) c;
        raw[15] = (byte) d;
        try {
            return java.net.InetAddress.getByAddress(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ipv4MappedTailMustBePrivateChecked() {
        byte[] mappedPrefix = new byte[16];
        mappedPrefix[10] = (byte) 0xff;
        mappedPrefix[11] = (byte) 0xff;
        // ::ffff:127.0.0.1 / ::ffff:10.0.0.1 / ::ffff:169.254.169.254 / ::ffff:192.168.0.1
        assertTrue(UrlSecurity.isPrivateAddress(
                        ipv6WithTail(mappedPrefix, 127, 0, 0, 1)),
                "mapped 环回必须拦截");
        assertTrue(UrlSecurity.isPrivateAddress(ipv6WithTail(mappedPrefix, 10, 0, 0, 1)),
                "mapped 私网 10/8 必须拦截");
        assertTrue(UrlSecurity.isPrivateAddress(
                        ipv6WithTail(mappedPrefix, 169, 254, 169, 254)),
                "mapped 云 metadata 必须拦截");
        assertTrue(UrlSecurity.isPrivateAddress(ipv6WithTail(mappedPrefix, 192, 168, 0, 1)),
                "mapped 192.168 必须拦截");
        // 公网 mapped 反向用例：不得一刀切全拦
        assertFalse(UrlSecurity.isPrivateAddress(ipv6WithTail(mappedPrefix, 8, 8, 8, 8)),
                "::ffff:8.8.8.8（公网）必须放行");
    }

    @Test
    void ipv4CompatibleTailMustBePrivateChecked() {
        byte[] compatPrefix = new byte[16]; // 前 12 字节全 0
        assertTrue(UrlSecurity.isPrivateAddress(ipv6WithTail(compatPrefix, 127, 0, 0, 1)),
                "::127.0.0.1（IPv4-compatible）环回必须拦截（实证 JDK 不解包该形态）");
        assertTrue(UrlSecurity.isPrivateAddress(ipv6WithTail(compatPrefix, 10, 1, 2, 3)),
                "::10.1.2.3 私网必须拦截");
        assertFalse(UrlSecurity.isPrivateAddress(ipv6WithTail(compatPrefix, 8, 8, 8, 8)),
                "::8.8.8.8（公网 compatible）放行");
    }

    @Test
    void normalPublicIpv6StillAllowed() {
        try {
            java.net.InetAddress pub = java.net.InetAddress.getByName("2606:4700:4700::1111");
            assertFalse(UrlSecurity.isPrivateAddress(pub), "公网 IPv6 放行");
        } catch (Exception ignored) {
            // 无 DNS/系统限制环境跳过
        }
    }

    @Test
    void ipv6LiteralUrlsBracketHandling() {
        // URI.getHost() 返回带括号 IPv6——剥括号后：私网/mapped 拦、公网放行
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://[::ffff:127.0.0.1]:8080/hook", false),
                "mapped 环回 URL 必须拦截");
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://[::7f00:1]:8080/hook", false),
                "IPv4-compatible 环回 URL 必须拦截（JDK 不解包该形态，靠补判）");
        assertThrows(IllegalArgumentException.class,
                () -> UrlSecurity.assertSafeHttpUrl("http://[fd00::1]:8080/hook", false),
                "ULA URL 必须拦截");
        // 公网 IPv6 字面 URL：放行（修 bracket 误拦后）
        UrlSecurity.assertSafeHttpUrl("http://[2606:4700:4700::1111]:8080/hook", false);
    }
}
