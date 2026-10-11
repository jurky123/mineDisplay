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
        ProxyUrl.requireProxy("http://127.0.0.1:8767/display/abc123", 8767);
        assertEquals("abc123", ProxyUrl.tokenOf("http://127.0.0.1:8767/display/abc123"));
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
    void hostAllowlistMatchesSubdomains() {
        assertTrue(ProxyUrl.hostAllowed("example.com", List.of("example.com")));
        assertTrue(ProxyUrl.hostAllowed("www.example.com", List.of("example.com")));
        assertFalse(ProxyUrl.hostAllowed("notexample.com", List.of("example.com")));
        assertFalse(ProxyUrl.hostAllowed("example.com.evil.com", List.of("example.com")));
        assertFalse(ProxyUrl.hostAllowed("example.com", List.of()));
    }
}
