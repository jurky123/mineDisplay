package com.minedisplay;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.minedisplay.browser.BrowserManager;

/**
 * MineDisplay 主插件：管理视觉素材、屏幕及播放状态。
 *
 * <p>M1 静态地图墙 + M3 真浏览器屏幕（Paper 是唯一网络出口）。
 * MineUI / MineAudio 均为可选集成（当前版本未接线，
 * 只保留包 {@code com.minedisplay.integration} 作后续扩展点）。
 */
public final class MineDisplayPlugin extends JavaPlugin implements Listener {

    private MediaStore mediaStore;
    private DisplayManager displayManager;
    private BrowserManager browserManager;
    private volatile boolean shuttingDown;

    public MediaStore media() {
        return mediaStore;
    }

    public DisplayManager displays() {
        return displayManager;
    }

    public BrowserManager browser() {
        return browserManager;
    }

    /** 插件正在停用：异步导入/渲染回调据此丢弃过期结果。 */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mediaStore = new MediaStore(this);
        displayManager = new DisplayManager(this);
        displayManager.start();
        browserManager = new BrowserManager(this);
        try {
            browserManager.start();
        } catch (Exception e) {
            getLogger().warning("浏览器出口代理启动失败（浏览器屏不可用，地图墙不受影响）: " + e.getMessage());
            browserManager = null;
        }
        getServer().getPluginManager().registerEvents(this, this);

        DisplayCommand command = new DisplayCommand(this);
        PluginCommand minedisplay = getCommand("minedisplay");
        if (minedisplay != null) {
            minedisplay.setExecutor(command);
            minedisplay.setTabCompleter(command);
        }

        getLogger().info("MineDisplay 已启用（" + displayManager.count() + " 个屏幕，"
                + mediaStore.all().size() + " 个素材，"
                + (browserManager == null ? "浏览器代理未启动" : browserManager.screenCount() + " 个浏览器屏") + "）");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (browserManager != null) {
            browserManager.stop();
            browserManager = null;
        }
        if (displayManager != null) {
            displayManager.stop();
        }
    }

    /** reload 校验并替换配置，保留合法运行状态（DESIGN §OPERATIONS）。 */
    public void reloadDisplayConfig() {
        reloadConfig();
    }

    /** 区块载入后按 PDC 与记录幂等对账（DESIGN §6：不强制加载世界）。 */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (displayManager != null && !shuttingDown) {
            displayManager.reconcileChunk(event.getChunk());
        }
    }
}
