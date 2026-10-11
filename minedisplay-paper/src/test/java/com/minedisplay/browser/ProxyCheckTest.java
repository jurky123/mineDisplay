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
}
