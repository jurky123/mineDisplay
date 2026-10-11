package com.minedisplay.protocol;

import com.google.gson.JsonObject;

/**
 * 协议信封：{@code protocol / type / screen / revision / data}。
 * 非屏幕包（HELLO / PING 等）screen 可为 null。
 */
public record Envelope(int protocol, PacketType type, String screen, int revision, JsonObject data) {

    public static Envelope of(PacketType type, JsonObject data) {
        return new Envelope(ProtocolVersion.PROTOCOL, type, null, 0, data);
    }

    public static Envelope screen(PacketType type, String screen, int revision, JsonObject data) {
        return new Envelope(ProtocolVersion.PROTOCOL, type, screen, revision, data);
    }

    /** 屏幕控制包是否过期（客户端用于丢弃旧 revision 的异步回调）。 */
    public boolean isStale(int currentRevision) {
        return revision <= currentRevision;
    }
}
