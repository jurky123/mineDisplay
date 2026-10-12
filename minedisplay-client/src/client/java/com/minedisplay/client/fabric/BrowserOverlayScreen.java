package com.minedisplay.client.fabric;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * 浏览器覆盖层：看（Rinku 纹理全屏/窗口化）/ 点 / 滚 / 输 / 关。
 *
 * <p>MVP：打开即全屏覆盖显示当前屏的 Rinku 纹理；顶部地址栏只读显示标题，
 * 输入框可输入 URL（回车后走 ACTION navigate，服务端重校验白名单后才真正跳转）；
 * Esc 关闭（只关本地覆盖层，不删服务端屏幕；服务端 REMOVE 才真正关）。
 */
public final class BrowserOverlayScreen extends Screen {

    private static final int NAV_HEIGHT = 28;
    private static final int STATUS_HEIGHT = 18;

    private final String screenId;
    private EditBox urlBox;
    private Button closeButton;
    private Button backButton;
    private Button forwardButton;
    private Button reloadButton;
    private String statusText = "";

    public BrowserOverlayScreen(String screenId, String title) {
        super(Component.literal(title == null || title.isBlank() ? "浏览器" : title));
        this.screenId = screenId;
    }

    public String screenId() {
        return screenId;
    }

    public void setStatus(String status) {
        this.statusText = status == null ? "" : status;
    }

    @Override
    protected void init() {
        BrowserClient.ScreenState state = BrowserClient.get().screens().get(screenId);
        String url = state == null ? "" : state.proxyUrl();
        urlBox = new EditBox(font, 34, 4, Math.max(100, width - 320), 20, Component.literal("地址"));
        urlBox.setValue(url);
        urlBox.setResponder(value -> {
            // 输入即本地过滤：只允许回环代理形状，回车才发送
        });
        addRenderableWidget(urlBox);

        backButton = Button.builder(Component.literal("◀"), button -> navigateBack())
                .bounds(4, 4, 24, 20).build();
        addRenderableWidget(backButton);
        reloadButton = Button.builder(Component.literal("⟳"), button -> navigateReload())
                .bounds(width - 280, 4, 24, 20).build();
        addRenderableWidget(reloadButton);
        forwardButton = Button.builder(Component.literal("▶"), button -> navigateForward())
                .bounds(width - 252, 4, 24, 20).build();
        addRenderableWidget(forwardButton);
        closeButton = Button.builder(Component.literal("关闭(Esc)"), button -> onClose())
                .bounds(width - 224, 4, 90, 20).build();
        addRenderableWidget(closeButton);

        resizeBrowserToView();
    }

    /** 视图区（导航栏下方、状态栏上方）。 */
    private int viewX() {
        return 0;
    }

    private int viewY() {
        return NAV_HEIGHT;
    }

    private int viewW() {
        return width;
    }

    private int viewH() {
        return Math.max(1, height - NAV_HEIGHT - STATUS_HEIGHT);
    }

    private void resizeBrowserToView() {
        BrowserClient.ScreenState state = BrowserClient.get().screens().get(screenId);
        if (state == null || state.view() == null) {
            return;
        }
        // Rinku 按像素压缩：MVP 用窗口像素 1:1（后续按 DPR/缩放优化）。
        state.view().resize(Math.max(64, viewW()), Math.max(64, viewH()));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, NAV_HEIGHT, 0xFF181818);
        graphics.fill(0, height - STATUS_HEIGHT, width, height, 0xFF181818);
        graphics.fill(0, NAV_HEIGHT, width, height - STATUS_HEIGHT, 0xFF000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        BrowserClient.ScreenState state = BrowserClient.get().screens().get(screenId);
        if (state == null || state.view() == null) {
            graphics.text(font, "屏幕已关闭", width / 2 - 30, height / 2, 0xFFFF6666, true);
            return;
        }
        if (state.view().textureReady()) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, state.view().textureId(),
                    viewX(), viewY(), 0f, 0f, viewW(), viewH(), viewW(), viewH());
        } else {
            graphics.text(font, "加载中…", width / 2 - 20, height / 2, 0xFFAAAAAA, true);
        }
        if (!statusText.isBlank()) {
            graphics.text(font, statusText, 6, height - STATUS_HEIGHT + 5, 0xFF909090, false);
        }
    }

    // ---------- 输入：本地坐标 → 浏览器坐标 → ACTION 回传 ----------

    private int[] toBrowser(int mouseX, int mouseY) {
        return new int[]{mouseX - viewX(), mouseY - viewY()};
    }

    private boolean inView(int mouseX, int mouseY) {
        return mouseX >= viewX() && mouseX < viewX() + viewW()
                && mouseY >= viewY() && mouseY < viewY() + viewH();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // 输入框/按钮先吃
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        if (!inView((int) event.x(), (int) event.y())) {
            return false;
        }
        int[] browser = toBrowser((int) event.x(), (int) event.y());
        BrowserClient.get().action(screenId, "click", null, browser[0], browser[1], 0, event.button(), null);
        // 本地即时注入，保证手感；服务端 INPUT 确认后幂等（MVP 直接注入一次）。
        BrowserClient.ScreenState state = BrowserClient.get().screens().get(screenId);
        if (state != null && state.view() != null) {
            state.view().mousePress(browser[0], browser[1], event.button());
            state.view().mouseRelease(browser[0], browser[1], event.button());
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (!inView((int) mouseX, (int) mouseY)) {
            return false;
        }
        int[] browser = toBrowser((int) mouseX, (int) mouseY);
        BrowserClient.get().action(screenId, "wheel", null, browser[0], browser[1], scrollY, 0, null);
        BrowserClient.ScreenState state = BrowserClient.get().screens().get(screenId);
        if (state != null && state.view() != null) {
            state.view().mouseWheel(browser[0], browser[1], scrollY, 0);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        // 输入框聚焦时走原版（URL 输入），不注入浏览器。
        if (urlBox != null && urlBox.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                submitUrl();
                return true;
            }
            return super.keyPressed(event);
        }
        BrowserClient.get().action(screenId, "key", null, 0, 0, 0, event.key(), null);
        BrowserClient.ScreenState state = BrowserClient.get().screens().get(screenId);
        if (state != null && state.view() != null) {
            state.view().keyPress(event.key(), event.scancode(), event.modifiers());
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (urlBox != null && urlBox.isFocused()) {
            return super.charTyped(event);
        }
        if (Character.isISOControl(event.codepoint())) {
            return false;
        }
        BrowserClient.ScreenState state = BrowserClient.get().screens().get(screenId);
        if (state != null && state.view() != null) {
            state.view().keyTyped((char) event.codepoint(), 0);
        }
        return true;
    }

    // ---------- 导航（经服务端重校验） ----------

    private void submitUrl() {
        String value = urlBox == null ? "" : urlBox.getValue().trim();
        if (value.isBlank()) {
            return;
        }
        // 客户端只做形状预检：真正白名单/DNS 由服务端重校验。
        BrowserClient.get().action(screenId, "navigate", value, 0, 0, 0, 0, null);
        setStatus("已请求跳转（等服务端确认）…");
    }

    private void navigateBack() {
        BrowserClient.get().action(screenId, "nav", null, 0, 0, 0, 0, "back");
    }

    private void navigateForward() {
        BrowserClient.get().action(screenId, "nav", null, 0, 0, 0, 0, "forward");
    }

    private void navigateReload() {
        BrowserClient.get().action(screenId, "nav", null, 0, 0, 0, 0, "reload");
    }

    @Override
    public void onClose() {
        BrowserOverlayScreens.closed(this);
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    /** 服务端 REMOVE/断线时从外部关闭（不回调 closed，避免重复清理）。 */
    void closeFromServer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() == this) {
            minecraft.gui.setScreen(null);
        }
    }
}
