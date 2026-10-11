package com.minedisplay.browser;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.minedisplay.MineDisplayPlugin;
import com.minedisplay.browser.BrowserManager.BrowserScreen;
import com.minedisplay.protocol.Envelope;
import com.minedisplay.protocol.PacketType;
import com.minedisplay.protocol.Packets;
import com.minedisplay.protocol.ProtocolCodec;
import com.minedisplay.protocol.ProtocolException;
import com.minedisplay.protocol.ProtocolVersion;

/**
 * 浏览器协议服务：通道注册、握手、UPSERT/REMOVE 下发、ACTION 接收。
 * 沿 MineAudio ClientProtocolService 风格：Bukkit 回调只做解码与转交。
 */
public final class BrowserProtocol {

    private final MineDisplayPlugin plugin;
    private final BrowserManager manager;
    private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final PluginMessageListener listener = this::onPluginMessageReceived;

    private record Connection(String modVersion, boolean browserCapable, long joinedAtMs) {
    }

    public BrowserProtocol(MineDisplayPlugin plugin, BrowserManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, ProtocolVersion.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, ProtocolVersion.CHANNEL, listener);
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                // 迟到玩家补发快照（定位当前时刻，不要求 READY 才开播）。
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (event.getPlayer().isOnline()) {
                        manager.sendSnapshot(event.getPlayer());
                    }
                }, 40L);
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                connections.remove(event.getPlayer().getUniqueId());
            }
        }, plugin);
    }

    public void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, ProtocolVersion.CHANNEL, listener);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, ProtocolVersion.CHANNEL);
        connections.clear();
    }

    void remove(Player player) {
        connections.remove(player.getUniqueId());
    }

    /** 在线且握手过 browser 能力的玩家数（status 用）。 */
    public int browserCapableCount() {
        int count = 0;
        for (Connection connection : connections.values()) {
            if (connection.browserCapable()) {
                count++;
            }
        }
        return count;
    }

    // ---------- 发送 ----------

    public void send(Player player, Envelope envelope) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            player.sendPluginMessage(plugin, ProtocolVersion.CHANNEL, ProtocolCodec.encode(envelope));
        } catch (Throwable t) {
            plugin.getLogger().warning("发送浏览器协议包失败（" + envelope.type() + "）：" + t);
        }
    }

    /** UPSERT 只发给握手过 browser 能力的玩家；其他人收 REMOVE（只显示地图封面）。 */
    public void broadcastUpsert(BrowserScreen screen, Packets.Upsert upsert) {
        Envelope envelope = Envelope.screen(PacketType.UPSERT, screen.id(), screen.revision(),
                ProtocolCodec.data(upsert));
        for (Player player : Bukkit.getOnlinePlayers()) {
            Connection connection = connections.get(player.getUniqueId());
            if (connection != null && connection.browserCapable()) {
                send(player, envelope);
            }
        }
    }

    public void broadcastRemove(String screenId, String reason) {
        Envelope envelope = Envelope.screen(PacketType.REMOVE, screenId, 0,
                ProtocolCodec.data(new Packets.Remove(screenId, reason)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player, envelope);
        }
    }

    public void sendUpsert(Player player, BrowserScreen screen, Packets.Upsert upsert) {
        Connection connection = connections.get(player.getUniqueId());
        if (connection == null || !connection.browserCapable()) {
            return;
        }
        try {
            ProtocolCodec.validateUpsert(upsert);
        } catch (ProtocolException e) {
            return;
        }
        send(player, Envelope.screen(PacketType.UPSERT, screen.id(), screen.revision(),
                ProtocolCodec.data(upsert)));
    }

    public void sendInput(Player player, BrowserScreen screen, Packets.Input input) {
        send(player, Envelope.screen(PacketType.INPUT, screen.id(), screen.revision(),
                ProtocolCodec.data(input)));
    }

    // ---------- 接收 ----------

    private void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ProtocolVersion.CHANNEL.equals(channel)) {
            return;
        }
        Envelope envelope;
        try {
            envelope = ProtocolCodec.decode(message);
        } catch (ProtocolException e) {
            return;
        }
        switch (envelope.type()) {
            case HELLO -> onHello(player, envelope);
            case ACTION -> onAction(player, envelope);
            case PING -> {
                try {
                    Packets.Ping ping = ProtocolCodec.data(envelope, Packets.Ping.class);
                    send(player, Envelope.of(PacketType.PONG,
                            ProtocolCodec.data(new Packets.Pong(ping.t0(), System.nanoTime() / 1_000_000))));
                } catch (ProtocolException ignored) {
                    // 坏包忽略
                }
            }
            default -> {
                // 未知包忽略
            }
        }
    }

    private void onHello(Player player, Envelope envelope) {
        Packets.Hello hello;
        try {
            hello = ProtocolCodec.data(envelope, Packets.Hello.class);
        } catch (ProtocolException e) {
            return;
        }
        boolean capable = hello.capabilities() != null
                && hello.capabilities().contains(ProtocolVersion.CAPABILITY_BROWSER);
        connections.put(player.getUniqueId(),
                new Connection(hello.modVersion(), capable, System.currentTimeMillis()));
        Packets.Welcome welcome = new Packets.Welcome(plugin.getDescription().getVersion(),
                manager.proxyPort(), 30_000);
        send(player, Envelope.of(PacketType.WELCOME, ProtocolCodec.data(welcome)));
        if (capable) {
            manager.sendSnapshot(player);
        }
    }

    private void onAction(Player player, Envelope envelope) {
        Packets.Action action;
        try {
            action = ProtocolCodec.data(envelope, Packets.Action.class);
            ProtocolCodec.validateAction(action);
        } catch (ProtocolException e) {
            return;
        }
        manager.onAction(player, action);
    }
}
