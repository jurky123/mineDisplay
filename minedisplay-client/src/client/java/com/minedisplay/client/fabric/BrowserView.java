package com.minedisplay.client.fabric;

import com.minedisplay.protocol.Packets;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import net.minecraft.resources.Identifier;

/**
 * 浏览器视图：Rinku 实例的薄封装（直接依赖 Rinku 类，编译期可见）。
 *
 * <p>MVP 只做“打开代理 URL → 拿到纹理 ID → 关闭释放”。
 * Rinku 缺失（ClassNotFound）时 open() 返回 null，调用方降级为地图封面。
 */
public final class BrowserView {

    private final RinkuBrowser browser;

    private BrowserView(RinkuBrowser browser) {
        this.browser = browser;
    }

    /**
     * 打开代理 URL，失败返回 null。必须在渲染线程调用
     *（Rinku.createBrowser 内部非渲染线程会 submit 回主线程再 join，
     * 但调用方仍保证渲染线程，避免死锁）。
     */
    public static BrowserView open(Packets.Upsert upsert) {
        try {
            if (!Rinku.isInitialized()) {
                if (!Rinku.initialize()) {
                    return null;
                }
            }
            // 浏览器分辨率：MVP 固定 1280x720，后续按屏幕宽高比与服务端 width/height 派生。
            RinkuBrowser browser = Rinku.createBrowser(upsert.url(), false, 1280, 720);
            if (browser == null) {
                return null;
            }
            return new BrowserView(browser);
        } catch (LinkageError | RuntimeException e) {
            // Rinku 缺失或初始化失败：降级
            return null;
        }
    }

    public Identifier textureId() {
        return browser.getTextureIdentifier();
    }

    public boolean textureReady() {
        try {
            return browser.isTextureReady();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void resize(int width, int height) {
        try {
            browser.resize(width, height);
        } catch (RuntimeException ignored) {
            // 注入失败不扩散
        }
    }

    // ---------- 输入注入（服务端 INPUT 确认后调用） ----------

    public void mouseMove(int x, int y) {
        try {
            browser.sendMouseMove(x, y);
        } catch (RuntimeException ignored) {
            // 注入失败不扩散
        }
    }

    public void mousePress(int x, int y, int button) {
        try {
            browser.sendMousePress(x, y, button);
        } catch (RuntimeException ignored) {
            // 注入失败不扩散
        }
    }

    public void mouseRelease(int x, int y, int button) {
        try {
            browser.sendMouseRelease(x, y, button);
        } catch (RuntimeException ignored) {
            // 注入失败不扩散
        }
    }

    public void mouseWheel(int x, int y, double amount, int modifiers) {
        try {
            browser.sendMouseWheel(x, y, amount, modifiers);
        } catch (RuntimeException ignored) {
            // 注入失败不扩散
        }
    }

    public void keyPress(int keyCode, long scanCode, int modifiers) {
        try {
            browser.sendKeyPress(keyCode, scanCode, modifiers);
        } catch (RuntimeException ignored) {
            // 注入失败不扩散
        }
    }

    public void keyRelease(int keyCode, long scanCode, int modifiers) {
        try {
            browser.sendKeyRelease(keyCode, scanCode, modifiers);
        } catch (RuntimeException ignored) {
            // 注入失败不扩散
        }
    }

    public void keyTyped(char c, int modifiers) {
        try {
            browser.sendKeyTyped(c, modifiers);
        } catch (RuntimeException ignored) {
            // 注入失败不扩散
        }
    }

    /** 服务端确认后的输入转述 → CEF 事件（MVP：点击/滚轮/键盘）。 */
    public void applyInput(Packets.Input input) {
        switch (input.kind()) {
            case "click" -> {
                mousePress((int) input.x(), (int) input.y(), 0);
                mouseRelease((int) input.x(), (int) input.y(), 0);
            }
            case "wheel" -> mouseWheel((int) input.x(), (int) input.y(), input.delta(), 0);
            case "key" -> keyPress(input.keyCode(), 0L, 0);
            default -> {
                // 未知输入忽略
            }
        }
    }

    public void close() {
        try {
            browser.close();
        } catch (RuntimeException ignored) {
            // 释放失败不扩散
        }
    }
}
