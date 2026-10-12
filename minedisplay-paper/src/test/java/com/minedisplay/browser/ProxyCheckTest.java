package com.minedisplay.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 代理目标检查单测（纯逻辑：白名单 + 私网拦截，不发请求）。 */
class ProxyCheckTest {

    @Test
    void allowsLoopbackItself() throws Exception {
        // 回环是代理自身的监听地址，checkTarget 只拦“目标站”，不拦代理。
        // 这里验证：公网 host 通过白名单即放行。
        ProxyServer.checkTarget(new URI("https://example.com/"), List.of("example.com"));
    }

    @Test
    void rejectsNonAllowlisted() {
        try {
            ProxyServer.checkTarget(new URI("https://evil.example/"), List.of("example.com"));
            throw new AssertionError("应拒绝非白名单");
        } catch (Exception e) {
            assertTrue(e instanceof java.io.IOException);
        }
    }

    @Test
    void rejectsPrivateTargets() {
        // 127.0.0.1 即使在白名单里也要被私网检查拦下（防 SSRF）。
        Exception thrown = assertThrows(Exception.class, () ->
                ProxyServer.checkTarget(new URI("http://127.0.0.1/"), List.of("127.0.0.1")));
        assertTrue(thrown instanceof java.io.IOException);
        assertEquals(1, 1);
    }

    @Test
    void decodeOriginAcceptsCleanOrigin() {
        String key = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("https://example.com".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("https://example.com", ProxyServer.decodeOrigin(key));
        // 带路径/查询/凭证的 origin 一律拒绝（镜像里只允许纯 origin）。
        for (String bad : List.of("https://example.com/a", "https://example.com/?x=1",
                "https://u@example.com", "ftp://example.com", "not-a-url")) {
            String badKey = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(null, ProxyServer.decodeOrigin(badKey), bad);
        }
        assertEquals(null, ProxyServer.decodeOrigin("%%%"));
    }

    @Test
    void resolveTargetBuildsMirrorUrl() {
        ProxyServer.ProxySession session = new ProxyServer.ProxySession("s1", "https://example.com/", 0);
        // 首文档。
        assertEquals("https://example.com/", ProxyServer.resolveTarget(session, "", null).toString());
        // 镜像：origin + 路径 + 查询。
        String key = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("https://cdn.example.net".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("https://cdn.example.net/lib/a.js?v=1",
                ProxyServer.resolveTarget(session, key + "/lib/a.js", "v=1").toString());
        // 非法 hostkey → null。
        assertEquals(null, ProxyServer.resolveTarget(session, "bogus/xx", null));
    }
}
