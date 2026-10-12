package com.minedisplay.client.fabric;

import com.minedisplay.protocol.Packets;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import net.minecraft.resources.Identifier;

/**
 * 浏览器视图：Rinku 实例的薄封装（直接依赖 Rinku 类，编译期可见）。
 *
 * <p>Rinku 缺失/初始化失败时 open() 抛 {@link OpenFailure}（带细分原因），
 * 调用方保留屏状态（待建 + 错误文案），不再丢弃 UPSERT；F8 可重试。
 */
public final class BrowserView {

    /** 创建失败原因（给玩家看的文案）。 */
    public static final class OpenFailure extends Exception {
        public OpenFailure(String message) {
            super(message);
        }
    }

    private final RinkuBrowser browser;

    private BrowserView(RinkuBrowser browser) {
        this.browser = browser;
    }

    /**
     * 打开代理 URL，失败抛 OpenFailure。必须在渲染线程调用
     *（Rinku.createBrowser 内部非渲染线程会 submit 回主线程再 join，
     * 但调用方仍保证渲染线程，避免死锁）。
     *
     * @param pixelWidth  浏览器渲染分辨率（按屏幕宽高比，见 BrowserClient.desiredResolution）
     * @param pixelHeight 同上
     */
    public static BrowserView open(String url, int pixelWidth, int pixelHeight) throws OpenFailure {
        try {
            if (!Rinku.isInitialized() && !Rinku.initialize()) {
                throw new OpenFailure("Rinku/CEF 初始化失败（JCEF 内核未就绪：多为下载失败，"
                        + "检查网络/镜像配置后重启游戏）");
            }
            RinkuBrowser browser = Rinku.createBrowser(url, false,
                    Math.max(64, pixelWidth), Math.max(64, pixelHeight));
            if (browser == null) {
                throw new OpenFailure("创建浏览器失败（Rinku 返回空）");
            }
            return new BrowserView(browser);
        } catch (LinkageError e) {
            // Rinku 缺失：类链接失败（NoClassDefFoundError 等）
            throw new OpenFailure("未安装 Rinku mod（缺类 " + e.getClass().getSimpleName() + "）");
        } catch (OpenFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OpenFailure("创建浏览器异常：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "（" + e.getMessage() + "）"));
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

    /** 服务端确认后的输入转述 → CEF 事件（MVP：点击/滚轮/键盘/字符）。 */
    public void applyInput(Packets.Input input) {
        switch (input.kind()) {
            case "click" -> {
                mousePress((int) input.x(), (int) input.y(), 0);
                mouseRelease((int) input.x(), (int) input.y(), 0);
            }
            case "wheel" -> mouseWheel((int) input.x(), (int) input.y(), input.delta(), 0);
            case "key" -> keyPress(input.keyCode(), 0L, 0);
            case "char" -> keyTyped((char) input.keyCode(), 0);
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
