package com.minedisplay.protocol;

/** 包类型。未知字符串解码为 null，由调用方拒绝。 */
public enum PacketType {
    HELLO,
    WELCOME,
    UPSERT,
    REMOVE,
    ACTION,
    INPUT,
    PING,
    PONG;

    public static PacketType from(String name) {
        if (name == null) {
            return null;
        }
        try {
            return PacketType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
