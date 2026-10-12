package com.minedisplay.browser;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.minedisplay.MediaStore;
import com.minedisplay.MineDisplayPlugin;
import com.minedisplay.protocol.Packets;
import com.minedisplay.protocol.ProtocolException;
import com.minedisplay.protocol.ProxyUrl;

/**
 * 浏览器屏幕管理：会话/白名单/令牌，Paper 是唯一出口。
 *
 * <p>MVP 只做“管理员开屏 → 客户端 Rinku 渲染代理 URL”：
 * 白名单校验 → 短期令牌 → 代理转发 → UPSERT 下发。
 * 输入回传（click/wheel/key）先收齐校验，MVP 转述为 INPUT（客户端再注入 CEF）。
 */
public final class BrowserManager {

    /** 屏幕会话（内存 + displays.yml 持久化定义，不持久化令牌）。 */
    public record BrowserScreen(UUID uuid, String id, String url, String title,
                                String world, double x, double y, double z,
                                String facing, double width, double height,
                                int revision, String token) {
    }

    private final MineDisplayPlugin plugin;
    private final BrowserProtocol protocol;
    private ProxyServer proxy;
    private ControlServer control;
    private final Map<String, BrowserScreen> screens = new HashMap<>();
    private long inputSequence;

    public BrowserManager(MineDisplayPlugin plugin) {
        this.plugin = plugin;
        this.protocol = new BrowserProtocol(plugin, this);
    }

    public void start() throws IOException {
        this.proxy = new ProxyServer(plugin);
        proxy.start();
        protocol.register();
        load();
        try {
            this.control = new ControlServer(plugin, this);
            control.start();
        } catch (IOException e) {
            plugin.getLogger().warning("控制 API 启动失败（门户管理页不可用）: " + e.getMessage());
            control = null;
        }
    }

    public void stop() {
        protocol.unregister();
        save();
        if (control != null) {
            control.stop();
            control = null;
        }
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        screens.clear();
    }

    public int proxyPort() {
        return proxy == null ? -1 : proxy.port();
    }

    /**
     * 下发给客户端的代理基址：配置 {@code browser.public-url} 优先（公网玩家场景），
     * 否则回环 {@code http://127.0.0.1:<端口>/display}（单机/开发）。
     * 客户端只接受「基址 + /令牌」形状的屏 URL。
     */
    public String baseUrl() {
        String configured = plugin.getConfig().getString("browser.public-url", "");
        if (configured != null && !configured.isBlank()) {
            try {
                return ProxyUrl.normalizeBase(configured);
            } catch (ProtocolException e) {
                plugin.getLogger().warning("browser.public-url 非法，回退回环代理：" + e.getMessage());
            }
        }
        return "http://127.0.0.1:" + proxyPort() + "/display";
    }

    public int screenCount() {
        return screens.size();
    }

    public int sessionCount() {
        return proxy == null ? 0 : proxy.sessionCount();
    }

    public BrowserProtocol protocol() {
        return protocol;
    }

    public List<BrowserScreen> list() {
        List<BrowserScreen> out = new ArrayList<>(screens.values());
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    public BrowserScreen find(String id) {
        return id == null ? null : screens.get(id.toLowerCase(Locale.ROOT));
    }

    // ---------- 开屏 / 关屏 ----------

    /**
     * 管理员开屏：校验白名单 → 建会话 → 下发 UPSERT。
     *
     * @return 失败原因，成功返回 null
     */
    public String open(String id, String url, String title) {
        String idError = MediaStore.checkId(id);
        if (idError != null) {
            return idError;
        }
        List<String> allowedHosts = plugin.getConfig().getStringList("browser.allowed-hosts");
        URI target;
        try {
            target = new URI(url);
        } catch (Exception e) {
            return "URL 非法";
        }
        if (!"https".equalsIgnoreCase(target.getScheme()) && !"http".equalsIgnoreCase(target.getScheme())) {
            return "只允许 http/https";
        }
        try {
            if (whitelistEnabled()) {
                ProxyServer.checkTarget(target, allowedHosts);
            } else {
                // 白名单关闭：仍拦私网/回环（防 SSRF）。
                ProxyServer.requirePublicAddress(target.getHost());
            }
        } catch (IOException e) {
            return "目标被拒绝（白名单/私网检查）: " + e.getMessage();
        }
        // 吊销旧令牌，签新令牌（单屏单令牌）。
        BrowserScreen old = screens.get(id.toLowerCase(Locale.ROOT));
        if (old != null) {
            proxy.revokeScreen(old.id());
        }
        long ttlMs = plugin.getConfig().getLong("browser.session-ttl-minutes", 30) * 60 * 1000;
        String token = proxy.issue(id, url, ttlMs);
        String proxyUrl = ProxyUrl.buildFromBase(baseUrl(), token);
        BrowserScreen screen = new BrowserScreen(
                old == null ? UUID.randomUUID() : old.uuid(),
                id, url, title == null ? "" : title,
                old == null ? "" : old.world(),
                old == null ? 0 : old.x(), old == null ? 0 : old.y(), old == null ? 0 : old.z(),
                old == null ? "NORTH" : old.facing(),
                old == null ? 3 : old.width(), old == null ? 2 : old.height(),
                old == null ? 1 : old.revision() + 1, token);
        screens.put(id.toLowerCase(Locale.ROOT), screen);
        save();
        // 审计：只记 host，不记 token。
        plugin.getLogger().info("浏览器开屏 " + id + " -> " + target.getHost()
                + "（revision " + screen.revision() + "）");
        broadcastUpsert(screen, proxyUrl);
        return null;
    }

    /** 关屏：吊销令牌 → 发 REMOVE → 删记录。 */
    public String close(String id) {
        BrowserScreen screen = screens.remove(id.toLowerCase(Locale.ROOT));
        if (screen == null) {
            return "没有该浏览器屏";
        }
        proxy.revokeScreen(screen.id());
        save();
        plugin.getLogger().info("浏览器关屏 " + id);
        broadcastRemove(screen.id(), "closed");
        return null;
    }

    /**
     * 管理员摆屏：把屏贴到瞄准的墙面（八格内），世界里随之渲染。
     *
     * <p>贴面位置取准星命中点，朝外偏 1cm 防 z-fighting；朝向取命中面的法线方向
     *（屏幕正面朝向玩家）。宽高不填则沿用原值（默认 3×2）。
     *
     * @return 失败原因，成功返回 null
     */
    public String place(Player player, String id, Double width, Double height) {
        BrowserScreen screen = find(id);
        if (screen == null) {
            return "没有该浏览器屏";
        }
        var hit = player.rayTraceBlocks(8, org.bukkit.FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlockFace() == null || hit.getHitPosition() == null) {
            return "请瞄准 8 格内的墙面（能看到方块侧面）";
        }
        org.bukkit.block.BlockFace face = hit.getHitBlockFace();
        if (face == org.bukkit.block.BlockFace.UP || face == org.bukkit.block.BlockFace.DOWN
                || face == org.bukkit.block.BlockFace.SELF) {
            return "请瞄准墙面（侧面），不要对着地板/天花板";
        }
        double w = clampSize(width != null ? width : screen.width());
        double h = clampSize(height != null ? height : screen.height());
        org.bukkit.util.Vector normal = face.getDirection();
        org.bukkit.util.Vector pos = hit.getHitPosition().add(normal.multiply(0.01));
        BrowserScreen placed = new BrowserScreen(screen.uuid(), screen.id(), screen.url(), screen.title(),
                player.getWorld().getKey().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                face.name(), w, h, screen.revision() + 1, screen.token());
        screens.put(screen.id().toLowerCase(Locale.ROOT), placed);
        save();
        plugin.getLogger().info("浏览器摆屏 " + screen.id() + " " + w + "x" + h + " " + face.name()
                + " @ " + placed.world() + " " + fmt(pos.getX()) + "," + fmt(pos.getY()) + "," + fmt(pos.getZ())
                + "（revision " + placed.revision() + "）");
        broadcastUpsert(placed, ProxyUrl.buildFromBase(baseUrl(), placed.token()));
        return null;
    }

    private static double clampSize(double v) {
        return Math.max(0.5, Math.min(16.0, v));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** 导航白名单开关（页面上也可切）。关闭=任意公网站点可打开/跳转（仍拦私网）。 */
    public boolean whitelistEnabled() {
        return plugin.getConfig().getBoolean("browser.whitelist-enabled", false);
    }

    public String setWhitelistEnabled(boolean enabled) {
        plugin.getConfig().set("browser.whitelist-enabled", enabled);
        plugin.saveConfig();
        plugin.getLogger().info("浏览器导航白名单" + (enabled ? "已启用" : "已关闭"));
        return null;
    }

    /** 白名单管理。 */
    public String allow(String host) {
        if (host == null || host.isBlank() || host.contains("/") || host.contains(":")) {
            return "host 非法（只要域名，如 example.com）";
        }
        List<String> hosts = new ArrayList<>(plugin.getConfig().getStringList("browser.allowed-hosts"));
        String lower = host.toLowerCase(Locale.ROOT);
        if (hosts.contains(lower)) {
            return "已在白名单";
        }
        hosts.add(lower);
        plugin.getConfig().set("browser.allowed-hosts", hosts);
        plugin.saveConfig();
        return null;
    }

    public String unallow(String host) {
        List<String> hosts = new ArrayList<>(plugin.getConfig().getStringList("browser.allowed-hosts"));
        if (!hosts.remove(host.toLowerCase(Locale.ROOT))) {
            return "不在白名单";
        }
        plugin.getConfig().set("browser.allowed-hosts", hosts);
        plugin.saveConfig();
        return null;
    }

    // ---------- 下发 ----------

    private void broadcastUpsert(BrowserScreen screen, String proxyUrl) {
        Packets.Upsert upsert = new Packets.Upsert(screen.id(), screen.revision(), proxyUrl,
                screen.title(), screen.world(), screen.x(), screen.y(), screen.z(),
                screen.facing(), screen.width(), screen.height());
        // 形状与基址校验在 BrowserProtocol（单一收口）。
        protocol.broadcastUpsert(screen, upsert);
    }

    private void broadcastRemove(String screenId, String reason) {
        protocol.broadcastRemove(screenId, reason);
    }

    /** 玩家进入/重登：补发其可见屏幕快照（MVP 全量，后续按权限/范围过滤）。 */
    public void sendSnapshot(Player player) {
        // 先补发 WELCOME：不依赖 HELLO 应答那一次（可能因进服时序被丢），
        // 保证每个快照前面都有基址下发。
        protocol.sendWelcome(player);
        long ttlMs = plugin.getConfig().getLong("browser.session-ttl-minutes", 30) * 60 * 1000;
        for (BrowserScreen screen : screens.values()) {
            // 每次快照重签新令牌：避免服务端长时间运行后旧令牌过期（客户端拿到就 404）。
            String token = proxy.issue(screen.id(), screen.url(), ttlMs);
            BrowserScreen refreshed = new BrowserScreen(screen.uuid(), screen.id(), screen.url(),
                    screen.title(), screen.world(), screen.x(), screen.y(), screen.z(),
                    screen.facing(), screen.width(), screen.height(), screen.revision(), token);
            screens.put(screen.id().toLowerCase(Locale.ROOT), refreshed);
            String proxyUrl = ProxyUrl.buildFromBase(baseUrl(), token);
            protocol.sendUpsert(player, refreshed,
                    new Packets.Upsert(refreshed.id(), refreshed.revision(), proxyUrl,
                            refreshed.title(), refreshed.world(), refreshed.x(), refreshed.y(),
                            refreshed.z(), refreshed.facing(), refreshed.width(), refreshed.height()));
        }
    }

    // ---------- 输入回传 ----------

    /** 客户端 ACTION：只引用 screenId，服务端重校验后转述为 INPUT。 */
    public void onAction(Player player, Packets.Action action) {
        BrowserScreen screen = screens.get(action.screenId().toLowerCase(Locale.ROOT));
        if (screen == null) {
            return;
        }
        switch (action.kind()) {
            case "ready", "error" -> {
                plugin.getLogger().info("浏览器屏 " + screen.id() + " 客户端" + action.kind()
                        + (action.message() == null ? "" : "：" + action.message())
                        + "（玩家 " + player.getName() + "）");
            }
            case "click", "wheel", "key", "char" -> {
                // MVP：距离/世界校验从简——只要求玩家在线且屏存在；
                // 真射线/朝向校验在世界 quad 落定后补（Client 骨架验收项）。
                long sequence = ++inputSequence;
                protocol.sendInput(player, screen,
                        new Packets.Input(screen.id(), action.kind(), action.x(), action.y(),
                                action.delta(), action.keyCode(), sequence));
            }
            default -> {
                // 未知 kind 忽略
            }
        }
    }

    // ---------- 持久化（只存定义，不存令牌；重启后重签） ----------

    private void save() {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("schema-version", 1);
        for (BrowserScreen screen : screens.values()) {
            String base = "browser-screens." + screen.id().toLowerCase(Locale.ROOT);
            config.set(base + ".uuid", screen.uuid().toString());
            config.set(base + ".id", screen.id());
            config.set(base + ".url", screen.url());
            config.set(base + ".title", screen.title());
            // 世界几何（摆屏）：空 world 表示还没摆到世界里。
            config.set(base + ".world", screen.world());
            config.set(base + ".x", screen.x());
            config.set(base + ".y", screen.y());
            config.set(base + ".z", screen.z());
            config.set(base + ".facing", screen.facing());
            config.set(base + ".width", screen.width());
            config.set(base + ".height", screen.height());
        }
        try {
            File file = new File(plugin.getDataFolder(), "browser.yml");
            File tmp = new File(plugin.getDataFolder(), "browser.yml.tmp");
            config.save(tmp);
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "保存浏览器屏定义失败", e);
        }
    }

    private void load() {
        File file = new File(plugin.getDataFolder(), "browser.yml");
        if (!file.isFile()) {
            return;
        }
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        var section = config.getConfigurationSection("browser-screens");
        if (section == null) {
            return;
        }
        // 重启后重签令牌：定义恢复，UPSERT 等玩家进入时补发。
        for (String idKey : section.getKeys(false)) {
            var entry = section.getConfigurationSection(idKey);
            if (entry == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(entry.getString("uuid", ""));
                String url = entry.getString("url", "");
                List<String> allowedHosts = plugin.getConfig().getStringList("browser.allowed-hosts");
                try {
                    ProxyServer.checkTarget(new URI(url), allowedHosts);
                } catch (Exception e) {
                    if (whitelistEnabled()) {
                        plugin.getLogger().warning("跳过白名单外的浏览器屏: " + idKey);
                        continue;
                    }
                    // 白名单关闭：非白名单也恢复，只要目标不是私网地址。
                    try {
                        ProxyServer.requirePublicAddress(new URI(url).getHost());
                    } catch (Exception private_) {
                        plugin.getLogger().warning("跳过私网目标的浏览器屏: " + idKey);
                        continue;
                    }
                }
                long ttlMs = plugin.getConfig().getLong("browser.session-ttl-minutes", 30) * 60 * 1000;
                String token = proxy.issue(idKey, url, ttlMs);
                String facing = entry.getString("facing", "NORTH").toUpperCase(Locale.ROOT);
                if (!List.of("NORTH", "SOUTH", "EAST", "WEST").contains(facing)) {
                    facing = "NORTH";
                }
                screens.put(idKey.toLowerCase(Locale.ROOT), new BrowserScreen(uuid, entry.getString("id", idKey),
                        url, entry.getString("title", ""), entry.getString("world", ""),
                        entry.getDouble("x", 0), entry.getDouble("y", 0), entry.getDouble("z", 0),
                        facing, clampSize(entry.getDouble("width", 3)), clampSize(entry.getDouble("height", 2)),
                        1, token));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "跳过损坏的浏览器屏记录: " + idKey, e);
            }
        }
        if (!screens.isEmpty()) {
            plugin.getLogger().info("恢复 " + screens.size() + " 个浏览器屏定义（令牌已重签）");
        }
    }

    /** 退出时清注册表（保留方法供插件层统一调用）。 */
    void onQuit(Player player) {
        protocol.remove(player);
    }
}
