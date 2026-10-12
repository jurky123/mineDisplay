package com.minedisplay.client.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Rinku 内核下载镜像引导：在游戏启动（Rinku 下载线程读配置）之前，把
 * {@code config/rinku/rinku.properties} 的 download-mirror 指到 zkun.art 镜像。
 *
 * <p>背景：Rinku 默认从 GitHub 下载 JCEF 内核，国内网络常超时；镜像由
 * mineAgent 托管（{@code /rinku-jcef}，与上游 release asset 同路径形状）。
 * 策略 PREFER_CONFIGURED：先走镜像，镜像失败仍回退官方。
 *
 * <p>只接管「未配置 / 仍是官方 GitHub 地址」的情况；玩家自定义镜像不动。
 * 镜像可用系统属性 {@code -Dminedisplay.rinku-mirror=...} 覆盖（运维用）。
 */
public class MineDisplayPreLaunch implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("MineDisplay");

    /** 默认镜像：mineAgent 托管的 JCEF 内核（写死本服地址；运维可覆盖）。 */
    private static final String DEFAULT_MIRROR = "https://zkun.art/rinku-jcef";

    @Override
    public void onPreLaunch() {
        try {
            bootstrapRinkuMirror();
        } catch (Throwable t) {
            LOGGER.warn("[MineDisplay] Rinku 镜像预配置失败（不影响启动）：" + t);
        }
    }

    static void bootstrapRinkuMirror() throws IOException {
        String mirror = System.getProperty("minedisplay.rinku-mirror", DEFAULT_MIRROR);
        if (mirror == null || mirror.isBlank()) {
            return;
        }
        Path configDir = FabricLoader.getInstance().getGameDir().resolve("config").resolve("rinku");
        Path file = configDir.resolve("rinku.properties");
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }
        String current = properties.getProperty("download-mirror", "").trim();
        boolean officialOrDefault = current.isEmpty()
                || current.toLowerCase(Locale.ROOT).contains("github.com");
        if (!officialOrDefault) {
            return; // 玩家自定义镜像，不接管
        }
        String policy = properties.getProperty("download-mirror-policy", "").trim();
        if (current.equals(mirror) && "PREFER_CONFIGURED".equalsIgnoreCase(policy)) {
            return; // 已经就是我们的镜像
        }
        properties.setProperty("download-mirror", mirror);
        properties.setProperty("download-mirror-policy", "PREFER_CONFIGURED");
        Files.createDirectories(configDir);
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "written by minedisplay-client rinku mirror bootstrap");
        }
        LOGGER.info("[MineDisplay] Rinku JCEF 下载镜像已配置为 " + mirror);
    }
}
