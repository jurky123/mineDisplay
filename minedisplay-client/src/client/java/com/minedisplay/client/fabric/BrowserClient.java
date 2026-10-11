package com.minedisplay.client.fabric;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.minedisplay.protocol.Envelope;
import com.minedisplay.protocol.PacketType;
import com.minedisplay.protocol.Packets;
import com.minedisplay.protocol.ProtocolCodec;
import com.minedisplay.protocol.ProtocolException;
import com.minedisplay.protocol.ProtocolVersion;
import com.minedisplay.protocol.ProxyUrl;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * 浏览器协议客户端：握手、UPSERT/REMOVE 接收、ACTION 回传。
 *
 * <p>安全铁律：只接受 {@code http://127.0.0.1:<port>/display/<token>} 形状的 URL，
 * 其他一律拒绝并上报（防服务端被攻破后下发恶意外链）。
 */
public final class BrowserClient {

    /** 屏幕状态（内存，不持久化）。 */
    public record ScreenState(String screenId, int revision, String proxyUrl,
                              String title, BrowserView view) {
    }

    private static final BrowserClient INSTANCE = new BrowserClient();

    public static BrowserClient get() {
        return INSTANCE;
    }

    private final Map<String, ScreenState> screens = new ConcurrentHashMap<>();
    private volatile int proxyPort;
    private volatile boolean rinkuAvailable;

    private BrowserClient() {
    }

    public boolean rinkuAvailable() {
        return rinkuAvailable;
    }

    public Map<String, ScreenState> screens() {
        return Map.copyOf(screens);
    }

    /** 进服握手：上报能力，Rinku 缺失时降级（只收地图封面）。 */
    public void onJoin(String modVersion) {
        boolean hasRinku = detectRinku();
        this.rinkuAvailable = hasRinku;
        Packets.Hello hello = new Packets.Hello(modVersion, "26.2",
                hasRinku ? java.util.List.of(ProtocolVersion.CAPABILITY_BROWSER) : java.util.List.of());
        send(Envelope.of(PacketType.HELLO, ProtocolCodec.data(hello)));
    }

    public void onDisconnect() {
        for (ScreenState state : screens.values()) {
            closeView(state);
        }
        screens.clear();
        proxyPort = 0;
    }

    private static boolean detectRinku() {
        try {
            Class.forName("de.keksuccino.rinku.Rinku");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 接收 ----------

    public void handle(byte[] data) {
        Envelope envelope;
        try {
            envelope = ProtocolCodec.decode(data);
        } catch (ProtocolException e) {
            return;
        }
        switch (envelope.type()) {
            case WELCOME -> {
                try {
                    Packets.Welcome welcome = ProtocolCodec.data(envelope, Packets.Welcome.class);
                    this.proxyPort = welcome.proxyPort();
                } catch (ProtocolException ignored) {
                    // 坏包忽略
                }
            }
            case UPSERT -> onUpsert(envelope);
            case REMOVE -> onRemove(envelope);
            case INPUT -> onInput(envelope);
            case PONG -> {
                // 校时后续用（MVP 先忽略）
            }
            default -> {
                // 未知包忽略
            }
        }
    }

    private void onUpsert(Envelope envelope) {
        Packets.Upsert upsert;
        try {
            upsert = ProtocolCodec.data(envelope, Packets.Upsert.class);
            ProtocolCodec.validateUpsert(upsert);
        } catch (ProtocolException e) {
            return;
        }
        // 双重校验：形状 + 端口（必须等于 WELCOME 下发的代理端口）。
        try {
            ProxyUrl.requireProxy(upsert.url(), proxyPort > 0 ? proxyPort : 0);
        } catch (ProtocolException e) {
            action(upsert.screenId(), "error", null, 0, 0, 0, 0, "拒绝非代理 URL");
            return;
        }
        ScreenState old = screens.get(upsert.screenId());
        if (old != null && envelope.revision() <= old.revision()) {
            return; // 过期丢弃
        }
        if (old != null) {
            closeView(old);
        }
        BrowserView view = BrowserView.open(upsert);
        if (view == null) {
            action(upsert.screenId(), "error", null, 0, 0, 0, 0, "Rinku 不可用");
            return;
        }
        screens.put(upsert.screenId(), new ScreenState(upsert.screenId(), envelope.revision(),
                upsert.url(), upsert.title(), view));
        action(upsert.screenId(), "ready", null, 0, 0, 0, 0, null);
    }

    private void onRemove(Envelope envelope) {
        Packets.Remove remove;
        try {
            remove = ProtocolCodec.data(envelope, Packets.Remove.class);
        } catch (ProtocolException e) {
            return;
        }
        ScreenState old = screens.remove(remove.screenId());
        if (old != null) {
            closeView(old);
        }
    }

    private void onInput(Envelope envelope) {
        Packets.Input input;
        try {
            input = ProtocolCodec.data(envelope, Packets.Input.class);
        } catch (ProtocolException e) {
            return;
        }
        ScreenState state = screens.get(input.screenId());
        if (state == null || state.view() == null) {
            return;
        }
        state.view().applyInput(input);
    }

    private static void closeView(ScreenState state) {
        try {
            if (state.view() != null) {
                state.view().close();
            }
        } catch (Throwable ignored) {
            // 释放失败不扩散
        }
    }

    // ---------- 发送 ----------

    /** 动作回传：只引用 screenId，不带 URL。 */
    public void action(String screenId, String kind, String url,
                       double x, double y, double delta, int keyCode, String message) {
        Packets.Action action = new Packets.Action(screenId, kind, url, x, y, delta, keyCode, message);
        try {
            ProtocolCodec.validateAction(action);
        } catch (ProtocolException e) {
            return;
        }
        send(Envelope.screen(PacketType.ACTION, screenId, 0, ProtocolCodec.data(action)));
    }

    private static void send(Envelope envelope) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() != null) {
                ClientPlayNetworking.send(new MineDisplayPayload(ProtocolCodec.encode(envelope)));
            }
        } catch (Throwable ignored) {
            // 未进服时丢弃
        }
    }
}
