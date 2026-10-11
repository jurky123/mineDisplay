package com.minedisplay.protocol;

import java.util.List;

/** 所有协议消息 DTO（Gson 序列化，未知字段忽略）。 */
public final class Packets {

    private Packets() {
    }

    /** C→S 握手：能力含 browser_screen 表示 Rinku 可用。 */
    public record Hello(
            String modVersion,
            String minecraft,
            List<String> capabilities) {
    }

    /** S→C 握手回执：代理端口与心跳间隔。 */
    public record Welcome(
            String serverVersion,
            int proxyPort,
            int heartbeatIntervalMs) {
    }

    /** S→C 屏幕下发：url 必为回环代理 URL（见 ProxyUrl）。 */
    public record Upsert(
            String screenId,
            int revision,
            String url,
            String title,
            String world,
            double x,
            double y,
            double z,
            String facing,
            double width,
            double height) {
    }

    /** S→C 屏幕移除：越界/吊销/删屏。 */
    public record Remove(
            String screenId,
            String reason) {
    }

    /**
     * C→S 动作：只引用 screenId，不带 URL。
     * kind: ready / error / click / wheel / key / navigate(仅op管理员且服务端重校验)
     */
    public record Action(
            String screenId,
            String kind,
            String url,
            double x,
            double y,
            double delta,
            int keyCode,
            String message) {
    }

    /** S→C 输入转述：服务端确认后的输入（MVP 与 UPSERT 共用 revision 链）。 */
    public record Input(
            String screenId,
            String kind,
            double x,
            double y,
            double delta,
            int keyCode,
            long sequence) {
    }

    public record Ping(long t0) {
    }

    public record Pong(long t0, long t1) {
    }
}
