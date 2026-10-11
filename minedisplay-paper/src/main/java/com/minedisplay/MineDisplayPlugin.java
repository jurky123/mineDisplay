package com.minedisplay;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MineDisplay 主插件：管理视觉素材、屏幕及播放状态。
 *
 * <p>M1 只实现静态地图墙。MineUI / MineAudio 均为可选集成（当前版本未接线，
 * 只保留包 {@code com.minedisplay.integration} 作后续扩展点）。
 */
public final class MineDisplayPlugin extends JavaPlugin implements Listener {

    private MediaStore mediaStore;
    private DisplayManager displayManager;
    private volatile boolean shuttingDown;

    public MediaStore media() {
        return mediaStore;
    }

    public DisplayManager displays() {
        return displayManager;
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
        getServer().getPluginManager().registerEvents(this, this);

        DisplayCommand command = new DisplayCommand(this);
        PluginCommand minedisplay = getCommand("minedisplay");
        if (minedisplay != null) {
            minedisplay.setExecutor(command);
            minedisplay.setTabCompleter(command);
        }

        getLogger().info("MineDisplay 已启用（" + displayManager.count() + " 个屏幕，"
                + mediaStore.all().size() + " 个素材）");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
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
