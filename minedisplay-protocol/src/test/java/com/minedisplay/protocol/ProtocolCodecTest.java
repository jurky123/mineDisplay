package com.minedisplay.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/** 协议编解码与校验单测（纯 Java，无 Bukkit/Fabric）。 */
class ProtocolCodecTest {

    @Test
    void helloRoundTrip() throws Exception {
        Packets.Hello hello = new Packets.Hello("0.2.0", "26.2", List.of("browser_screen"));
        Envelope out = Envelope.of(PacketType.HELLO, ProtocolCodec.data(hello));
        Envelope back = ProtocolCodec.decode(ProtocolCodec.encode(out));
        assertEquals(PacketType.HELLO, back.type());
        Packets.Hello decoded = ProtocolCodec.data(back, Packets.Hello.class);
        assertEquals("0.2.0", decoded.modVersion());
        assertTrue(decoded.capabilities().contains("browser_screen"));
    }

    @Test
    void upsertRequiresProxyUrl() {
        // 外网 URL 直接拒绝：客户端永不直连外网。
        Packets.Upsert bad = new Packets.Upsert("s1", 1, "https://example.com/", "t",
                "world", 0, 64, 0, "NORTH", 2, 1);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.validateUpsert(bad));
        // 非 127.0.0.1 也拒绝。
        Packets.Upsert lan = new Packets.Upsert("s1", 1, "http://192.168.1.1/display/abc", "t",
                "world", 0, 64, 0, "NORTH", 2, 1);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.validateUpsert(lan));
        // 回环代理形状通过。
        Packets.Upsert good = new Packets.Upsert("s1", 1, "http://127.0.0.1:8767/display/abc123", "t",
                "world", 0, 64, 0, "NORTH", 2, 1);
        try {
            ProtocolCodec.validateUpsert(good);
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void actionMustNotCarryUrl() {
        Packets.Action sneaky = new Packets.Action("s1", "click", "https://evil.example/", 0, 0, 0, 0, null);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.validateAction(sneaky));
        Packets.Action good = new Packets.Action("s1", "click", null, 10, 20, 0, 0, null);
        try {
            ProtocolCodec.validateAction(good);
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void rejectsBadEnvelopes() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(new byte[0]));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode("{}".getBytes()));
        assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decode("{\"protocol\":999,\"type\":\"HELLO\"}".getBytes()));
        assertThrows(ProtocolException.class,
                () -> ProtocolCodec.decode("{\"protocol\":1,\"type\":\"NOPE\"}".getBytes()));
    }

    @Test
    void staleRevisionDropped() {
        Envelope envelope = Envelope.screen(PacketType.UPSERT, "s1", 3, new JsonObject());
        assertTrue(envelope.isStale(3));
        assertTrue(envelope.isStale(5));
    }
}
