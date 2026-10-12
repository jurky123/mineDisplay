package com.minedisplay.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** ProxyUrl 形状与白名单单测。 */
class ProxyUrlTest {

    @Test
    void acceptsLoopbackProxy() throws Exception {
        ProxyUrl.requireProxy("http://127.0.0.1:8767/display/abcdef1234", 8767);
        assertEquals("abcdef1234", ProxyUrl.tokenOf("http://127.0.0.1:8767/display/abcdef1234"));
    }

    @Test
    void rejectsNonLoopback() {
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxy("https://example.com/", 8767));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxy("http://192.168.1.1/display/x", 8767));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxy("http://localhost:8767/display/x", 8767));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxy("http://127.0.0.1:9999/display/x", 8767));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxy("http://127.0.0.1:8767/other/x", 8767));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxy("http://user@127.0.0.1:8767/display/x", 8767));
    }

    @Test
    void acceptsConfiguredBase() throws Exception {
        // 公网基址（腾讯云放行端口模式）。
        ProxyUrl.requireProxyUrl("http://zkun.art:8768/display/abcdef1234", "http://zkun.art:8768/display");
        // 反代路径基址（zkun.art 443 模式）。
        ProxyUrl.requireProxyUrl("https://zkun.art/display-proxy/abcdef1234", "https://zkun.art/display-proxy");
        // 默认端口等价：443 显式写与省略一致。
        ProxyUrl.requireProxyUrl("https://zkun.art:443/display/abcdef1234", "https://zkun.art/display");
    }

    @Test
    void rejectsBaseMismatch() {
        String base = "http://zkun.art:8768/display";
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://evil.com:8768/display/abcdef1234", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://zkun.art:9999/display/abcdef1234", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://zkun.art:8768/other/abcdef1234", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://zkun.art:8768/display/abcdef1234/x", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://zkun.art:8768/display/short", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://zkun.art:8768/display/abcdef1234?q=1", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("https://zkun.art:8768/display/abcdef1234", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://user@zkun.art:8768/display/abcdef1234", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://zkun.art:8768/display/..", base));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.requireProxyUrl("http://zkun.art:8768/display/%2e%2e/abcdef1234", base));
    }

    @Test
    void normalizesBase() throws Exception {
        assertEquals("http://zkun.art:8768/display", ProxyUrl.normalizeBase("http://zkun.art:8768"));
        assertEquals("http://zkun.art:8768/display", ProxyUrl.normalizeBase("http://zkun.art:8768/display/"));
        assertEquals("https://zkun.art/display-proxy", ProxyUrl.normalizeBase("https://zkun.art/display-proxy"));
        assertEquals("http://127.0.0.1:8768/display", ProxyUrl.normalizeBase("http://127.0.0.1:8768/"));
        assertThrows(ProtocolException.class, () -> ProxyUrl.normalizeBase("ftp://zkun.art/"));
        assertThrows(ProtocolException.class, () -> ProxyUrl.normalizeBase(""));
    }

    @Test
    void buildFromBaseJoinsToken() {
        assertEquals("http://zkun.art:8768/display/abcdef1234",
                ProxyUrl.buildFromBase("http://zkun.art:8768/display", "abcdef1234"));
    }

    @Test
    void shapeOnlyNeedsTokenTail() throws Exception {
        ProxyUrl.validateShape("http://zkun.art:8768/display/abcdef1234");
        ProxyUrl.validateShape("https://zkun.art/display-proxy/abcdef1234");
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.validateShape("https://example.com/"));
        assertThrows(ProtocolException.class,
                () -> ProxyUrl.validateShape("https://example.com/display/"));
    }

    @Test
    void hostAllowlistMatchesSubdomains() {
        assertTrue(ProxyUrl.hostAllowed("example.com", List.of("example.com")));
        assertTrue(ProxyUrl.hostAllowed("www.example.com", List.of("example.com")));
        assertFalse(ProxyUrl.hostAllowed("notexample.com", List.of("example.com")));
        assertFalse(ProxyUrl.hostAllowed("example.com.evil.com", List.of("example.com")));
        assertFalse(ProxyUrl.hostAllowed("example.com", List.of()));
    }
}
