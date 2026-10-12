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
 * <p>安全铁律：只接受 WELCOME 下发基址（如 {@code http://zkun.art:8768/display}
 * 或回环模式）拼接的 {@code <基址>/<token>} 形状 URL，其他一律拒绝并上报
 * （防服务端被攻破后下发恶意外链）。
 *
 * <p>Rinku 不可用时屏不丢弃：保留待建状态（error 文案），F8 可重试创建。
 * 摆屏（world 非空）后由 {@link BrowserWorldRenderer} 在世界里渲染。
 */
public final class BrowserClient {

    /**
     * 屏幕状态（内存，不持久化）。
     *
     * <p>view 为空时 error 说明原因（待建，可重试）；world 为空表示还没摆到世界里。
     */
    public record ScreenState(String screenId, int revision, String proxyUrl, String title,
                              String world, double x, double y, double z, String facing,
                              double width, double height, BrowserView view, String error) {
        public boolean ready() {
            return view != null;
        }

        public boolean placed() {
            return world != null && !world.isBlank() && width > 0 && height > 0;
        }
    }

    private static final BrowserClient INSTANCE = new BrowserClient();

    public static BrowserClient get() {
        return INSTANCE;
    }

    private final Map<String, ScreenState> screens = new ConcurrentHashMap<>();
    private volatile int proxyPort;
    private volatile String proxyBaseUrl = "";
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
        BrowserOverlayScreens.closeAll();
        for (ScreenState state : screens.values()) {
            closeView(state);
        }
        screens.clear();
        proxyPort = 0;
        proxyBaseUrl = "";
    }

    private static boolean detectRinku() {
        try {
            Class.forName("de.keksuccino.rinku.Rinku");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前代理基址：优先 WELCOME 下发值，缺省回退回环（老服务端兼容）。 */
    private String base() {
        String base = proxyBaseUrl;
        if (base != null && !base.isBlank()) {
            return base;
        }
        int port = proxyPort;
        return port > 0 ? "http://127.0.0.1:" + port + "/display" : "";
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
                    this.proxyBaseUrl = welcome.proxyBaseUrl() == null ? "" : welcome.proxyBaseUrl();
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
        // 双重校验：形状 + 基址（必须等于 WELCOME 下发的代理基址）。
        String base = base();
        if (base.isBlank()) {
            action(upsert.screenId(), "error", null, 0, 0, 0, 0, "缺少代理基址（未握手）");
            return;
        }
        try {
            ProxyUrl.requireProxyUrl(upsert.url(), base);
        } catch (ProtocolException e) {
            action(upsert.screenId(), "error", null, 0, 0, 0, 0, "拒绝非代理 URL");
            return;
        }
        ScreenState old = screens.get(upsert.screenId());
        if (old != null && envelope.revision() <= old.revision()) {
            return; // 过期丢弃
        }
        // URL 没变（例如只是摆屏改几何）时保留现有浏览器实例，不重载页面。
        boolean sameUrl = old != null && upsert.url().equals(old.proxyUrl());
        BrowserView view = sameUrl ? old.view() : null;
        if (old != null && old.view() != null && !sameUrl) {
            closeView(old);
        }
        String error = null;
        if (view == null) {
            if (!rinkuAvailable) {
                error = "未安装 Rinku mod（浏览器屏需要 Rinku；安装后重启游戏）";
            } else {
                try {
                    int[] size = desiredResolution(upsert.width(), upsert.height());
                    view = BrowserView.open(upsert.url(), size[0], size[1]);
                } catch (BrowserView.OpenFailure failure) {
                    error = failure.getMessage();
                }
            }
        } else {
            // 复用实例：几何变了就把浏览器分辨率同步到新宽高比。
            int[] size = desiredResolution(upsert.width(), upsert.height());
            view.resize(size[0], size[1]);
        }
        screens.put(upsert.screenId(), new ScreenState(upsert.screenId(), envelope.revision(),
                upsert.url(), upsert.title(), upsert.world(), upsert.x(), upsert.y(), upsert.z(),
                upsert.facing(), upsert.width(), upsert.height(), view, error));
        BrowserOverlayScreens.noteScreen(upsert.screenId(), old == null);
        action(upsert.screenId(), view != null ? "ready" : "error", null, 0, 0, 0, 0, error);
    }

    /**
     * F8 重试：为待建屏重新尝试创建浏览器视图。
     *
     * @return 失败文案；成功返回 null（状态更新为 ready）
     */
    public String retry(String screenId) {
        ScreenState old = screens.get(screenId);
        if (old == null) {
            return "屏幕不存在";
        }
        if (old.view() != null) {
            return null;
        }
        if (!rinkuAvailable) {
            // 重探测：换版本/补装后无需重启探测也无害
            rinkuAvailable = detectRinku();
            if (!rinkuAvailable) {
                return "未安装 Rinku mod（浏览器屏需要 Rinku；安装后重启游戏）";
            }
        }
        BrowserView view;
        try {
            int[] size = desiredResolution(old.width(), old.height());
            view = BrowserView.open(old.proxyUrl(), size[0], size[1]);
        } catch (BrowserView.OpenFailure failure) {
            String error = failure.getMessage();
            screens.put(screenId, new ScreenState(old.screenId(), old.revision(), old.proxyUrl(),
                    old.title(), old.world(), old.x(), old.y(), old.z(), old.facing(),
                    old.width(), old.height(), null, error));
            return error;
        }
        screens.put(screenId, new ScreenState(old.screenId(), old.revision(), old.proxyUrl(),
                old.title(), old.world(), old.x(), old.y(), old.z(), old.facing(),
                old.width(), old.height(), view, null));
        action(screenId, "ready", null, 0, 0, 0, 0, null);
        return null;
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
            BrowserOverlayScreens.closeScreen(remove.screenId());
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

    /** 世界渲染用的期望分辨率：按屏幕宽高比给，无几何时 1280×720。 */
    static int[] desiredResolution(double width, double height) {
        if (width <= 0 || height <= 0) {
            return new int[]{1280, 720};
        }
        double scale = Math.min(256.0, Math.min(1920.0 / width, 1080.0 / height));
        int px = (int) Math.round(width * scale);
        int py = (int) Math.round(height * scale);
        return new int[]{Math.max(64, px), Math.max(64, py)};
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
