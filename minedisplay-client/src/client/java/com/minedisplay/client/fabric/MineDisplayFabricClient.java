package com.minedisplay.client.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MineDisplay Client 入口：通道注册、握手、HUD 胶囊、F8 覆盖层。 */
public class MineDisplayFabricClient implements ClientModInitializer {

    public static final String MOD_ID = "minedisplay-client";
    public static final String MINECRAFT_VERSION = "26.2";
    public static final Logger LOGGER = LoggerFactory.getLogger("MineDisplay");

    private static KeyMapping openBrowserKey;

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

        BrowserOverlayScreens.initHud();
        BrowserWorldRenderer.init();

        // F8：打开/关闭浏览器覆盖层（原版按键设置可改键）
        openBrowserKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.minedisplay.browser", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openBrowserKey.consumeClick()) {
                BrowserOverlayScreens.toggle();
            }
        });
        // 右键瞄准世界里的屏幕：打开该屏的操作覆盖层（别的时候不拦截，不影响放方块）。
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null || client.gui.screen() != null) {
                return;
            }
            String aimed = BrowserWorldRenderer.aimedScreenId(client, 8.0);
            if (aimed != null && client.options.keyUse.consumeClick()) {
                BrowserOverlayScreens.open(aimed);
            }
        });
        // HUD 胶囊点击：无界面且点中胶囊时打开覆盖层（屏蔽后续传递）。
        // 沿 MineUI：HudElementRegistry 只画胶囊；点击走 handleInput 轮询太重，
        // MVP 用“F8 打开”，胶囊点击在 HUD 层用 ScreenMouseEvents 兜底（有界面时不拦截）。
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
                ScreenMouseEvents.allowMouseClick(screen).register((ignored, event) -> true));

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
