package com.minedisplay.client.fabric;

import com.minedisplay.protocol.Packets;

/**
 * 浏览器视图：Rinku 实例的薄封装。
 *
 * <p>MVP 只做“打开代理 URL → 拿到纹理 ID → 关闭释放”。
 * 世界 quad 渲染与输入注入在骨架验收后补（见 docs/BROWSER.md §7）。
 * Rinku 缺失时返回 null，调用方降级为地图封面。
 */
public final class BrowserView {

    private final Object browser;
    private final String textureId;

    private BrowserView(Object browser, String textureId) {
        this.browser = browser;
        this.textureId = textureId;
    }

    /** 打开代理 URL，失败返回 null。Rinku 调用必须在渲染线程。 */
    public static BrowserView open(Packets.Upsert upsert) {
        try {
            Class<?> rinku = Class.forName("de.keksuccino.rinku.Rinku");
            if (!(Boolean) rinku.getMethod("isInitialized").invoke(null)) {
                if (!(Boolean) rinku.getMethod("initialize").invoke(null)) {
                    return null;
                }
            }
            Object browser = rinku.getMethod("createBrowser", String.class, boolean.class)
                    .invoke(null, upsert.url(), false);
            if (browser == null) {
                return null;
            }
            Object textureId = browser.getClass().getMethod("getTextureIdentifier").invoke(browser);
            return new BrowserView(browser, String.valueOf(textureId));
        } catch (Throwable t) {
            return null;
        }
    }

    public String textureId() {
        return textureId;
    }

    /** 服务端确认后的输入转述 → CEF 事件（MVP：点击/滚轮/键盘）。 */
    public void applyInput(Packets.Input input) {
        if (browser == null) {
            return;
        }
        try {
            switch (input.kind()) {
                case "click" -> browser.getClass()
                        .getMethod("sendMousePress", int.class, int.class, int.class)
                        .invoke(browser, (int) input.x(), (int) input.y(), 0);
                case "wheel" -> browser.getClass()
                        .getMethod("sendMouseWheel", int.class, int.class, double.class, int.class)
                        .invoke(browser, (int) input.x(), (int) input.y(), input.delta(), 0);
                case "key" -> browser.getClass()
                        .getMethod("sendKeyPress", int.class, long.class, int.class)
                        .invoke(browser, input.keyCode(), 0L, 0);
                default -> {
                    // 未知输入忽略
                }
            }
        } catch (Throwable ignored) {
            // 注入失败不扩散
        }
    }

    public void close() {
        if (browser == null) {
            return;
        }
        try {
            browser.getClass().getMethod("close").invoke(browser);
        } catch (Throwable ignored) {
            // 释放失败不扩散
        }
    }
}
