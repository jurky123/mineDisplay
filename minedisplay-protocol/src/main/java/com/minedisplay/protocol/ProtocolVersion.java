package com.minedisplay.protocol;

/** 协议版本与通道常量。 */
public final class ProtocolVersion {

    /** 插件消息通道。 */
    public static final String CHANNEL = "minedisplay:main";

    /** 协议版本（破坏性变更时递增）。 */
    public static final int PROTOCOL = 1;

    /** 要求客户端上报的能力位：Rinku/Chromium 可用。 */
    public static final String CAPABILITY_BROWSER = "browser_screen";

    private ProtocolVersion() {
    }
}
