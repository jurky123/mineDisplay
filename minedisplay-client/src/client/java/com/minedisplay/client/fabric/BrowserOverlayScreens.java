package com.minedisplay.client.fabric;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * 浏览器覆盖层管理：打开/关闭/列表 + HUD 胶囊。
 *
 * <p>HUD 只画一个可点击胶囊（“浏览器（n)”），点击打开选择器；
 * 选择器是聊天行（无 mod 也能看的降级路径，保持一致）。
 */
public final class BrowserOverlayScreens {

    private static BrowserOverlayScreen current;
    private static String pendingOpen;

    private BrowserOverlayScreens() {
    }

    public static void initHud() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minedisplay", "browser"),
                (graphics, deltaTracker) -> renderCapsule(graphics, Minecraft.getInstance().font));
    }

    /** 服务端 UPSERT 后调用：有屏即记住，HUD 胶囊可点开。 */
    public static void noteScreen(String screenId) {
        if (current == null && pendingOpen == null && BrowserClient.get().screens().size() == 1) {
            // 唯一屏：HUD 提示一次即可，不自动弹屏（避免打断游戏）。
            chat("§6[浏览器] §f收到浏览器屏 §e" + screenId + " §7（按 §eF8 §7查看）");
        }
    }

    /** F8：有屏则打开/切换覆盖层，无屏则提示。 */
    public static void toggle() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() instanceof BrowserOverlayScreen overlay) {
            overlay.onClose();
            return;
        }
        List<String> ids = new ArrayList<>(BrowserClient.get().screens().keySet());
        ids.sort(String::compareTo);
        if (ids.isEmpty()) {
            chat("§7[浏览器] 当前没有可看的屏幕");
            return;
        }
        open(ids.get(0));
    }

    public static void open(String screenId) {
        BrowserClient.ScreenState state = BrowserClient.get().screens().get(screenId);
        if (state == null) {
            chat("§c[浏览器] 屏幕不存在: " + screenId);
            return;
        }
        if (!BrowserClient.get().rinkuAvailable()) {
            chat("§c[浏览器] 未安装 Rinku，只显示地图封面");
            return;
        }
        if (current != null) {
            BrowserOverlayScreen overlay = current;
            current = null;
            overlay.closeFromServer();
        }
        current = new BrowserOverlayScreen(screenId, state.title());
        Minecraft.getInstance().gui.setScreen(current);
    }

    /** 覆盖层 Esc/关闭按钮回调：只清理引用。 */
    static void closed(BrowserOverlayScreen screen) {
        if (current == screen) {
            current = null;
        }
    }

    /** 服务端 REMOVE/断线：关掉对应覆盖层。 */
    public static void closeScreen(String screenId) {
        if (current != null && current.screenId().equals(screenId)) {
            BrowserOverlayScreen overlay = current;
            current = null;
            overlay.closeFromServer();
        }
    }

    public static void closeAll() {
        if (current != null) {
            BrowserOverlayScreen overlay = current;
            current = null;
            overlay.closeFromServer();
        }
    }

    public static boolean isOpen() {
        return current != null;
    }

    // ---------- HUD 胶囊 ----------

    private static void renderCapsule(GuiGraphicsExtractor graphics, Font font) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gui.screen() != null) {
            return;
        }
        int count = BrowserClient.get().screens().size();
        if (count == 0) {
            return;
        }
        String text = "▣ 浏览器(" + count + ") F8";
        int w = font.width(text) + 16;
        int x = minecraft.getWindow().getScreenWidth() - w - 10;
        int y = 10;
        graphics.fill(x, y, x + w, y + 18, 0xB0101010);
        graphics.text(font, text, x + 8, y + 5, 0xFFFFFFFF, false);
    }

    /** HUD 胶囊点击（F8 同路，鼠标点胶囊区域即打开）。 */
    public static boolean mouseClicked(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gui.screen() != null) {
            return false;
        }
        int count = BrowserClient.get().screens().size();
        if (count == 0) {
            return false;
        }
        Font font = minecraft.font;
        String text = "▣ 浏览器(" + count + ") F8";
        int w = font.width(text) + 16;
        int x = minecraft.getWindow().getScreenWidth() - w - 10;
        return mouseX >= x && mouseX < x + w && mouseY >= 10 && mouseY < 28;
    }

    private static void chat(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
