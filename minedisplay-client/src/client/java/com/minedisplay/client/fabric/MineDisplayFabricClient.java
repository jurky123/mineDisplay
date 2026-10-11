package com.minedisplay.client.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MineDisplay Client 入口：通道注册、握手。世界 quad 渲染在骨架验收后补。 */
public class MineDisplayFabricClient implements ClientModInitializer {

    public static final String MOD_ID = "minedisplay-client";
    public static final String MINECRAFT_VERSION = "26.2";
    public static final Logger LOGGER = LoggerFactory.getLogger("MineDisplay");

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(MineDisplayPayload.TYPE, MineDisplayPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MineDisplayPayload.TYPE, MineDisplayPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(MineDisplayPayload.TYPE, (payload, context) ->
                context.client().execute(() -> BrowserClient.get().handle(payload.data())));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                BrowserClient.get().onJoin(version()));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(BrowserClient.get()::onDisconnect));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 心跳/PING 后续用（MVP 先空转，保证无异常）。
        });

        LOGGER.info("MineDisplay Client {} 初始化完成（26.2，Rinku {}）",
                version(), rinkuPresent() ? "已检测到" : "未安装，走地图封面降级");
    }

    private static boolean rinkuPresent() {
        try {
            Class.forName("de.keksuccino.rinku.Rinku");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }
}
