package com.minedisplay.protocol;

/** 应用层硬限制。 */
public final class ProtocolLimits {

    /** 单包上限（沿 MineAudio）。 */
    public static final int MAX_PACKET_BYTES = 24 * 1024;
    /** URL 长度上限。 */
    public static final int MAX_URL_LENGTH = 8 * 1024;
    /** ID 长度上限。 */
    public static final int MAX_ID_LENGTH = 256;

    private ProtocolLimits() {
    }
}
