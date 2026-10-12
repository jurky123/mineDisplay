package com.minedisplay.browser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.minedisplay.MineDisplayPlugin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 本地控制 API：给门户（mineAgent）的“显示屏”管理页做后端。
 *
 * <p>只绑 127.0.0.1；Bearer token 存 {@code plugins/MineDisplay/control-token.txt}
 * （0600，首次生成）。端点：
 * <ul>
 *   <li>{@code GET /status}：屏幕列表/白名单/客户端数</li>
 *   <li>{@code POST /open} {id,url,title?}、{@code POST /close} {id}</li>
 *   <li>{@code POST /whitelist} {action: add|remove|enabled, host?, enabled?}</li>
 * </ul>
 * 门户侧再包一层登录鉴权（写操作限管理员），见 mineAgent portal。
 */
public final class ControlServer {

    /** 常用站点预设（一键加白名单用；只影响导航白名单）。 */
    private static final List<String> PRESETS = List.of(
            "baidu.com", "bing.com", "google.com", "youtube.com", "wikipedia.org",
            "zhihu.com", "bilibili.com", "weibo.com", "github.com", "csdn.net",
            "juejin.cn", "stackoverflow.com", "minecraft.wiki");

    private final MineDisplayPlugin plugin;
    private final BrowserManager manager;
    private final Gson gson = new Gson();
    private HttpServer server;
    private String token;

    public ControlServer(MineDisplayPlugin plugin, BrowserManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() throws IOException {
        int port = plugin.getConfig().getInt("browser.control-port", 8769);
        this.token = loadOrCreateToken();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "MineDisplay-Control");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        plugin.getLogger().info("控制 API 监听 127.0.0.1:" + port
                + "（门户 /screens 用，token: plugins/MineDisplay/control-token.txt）");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String loadOrCreateToken() throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve("control-token.txt");
        if (Files.isRegularFile(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String generated = HexFormat.of().formatHex(bytes);
        Files.createDirectories(file.getParent());
        Files.writeString(file, generated + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统：尽力而为
        }
        return generated;
    }

    // ---------- 路由 ----------

    private void handle(HttpExchange exchange) {
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + token)) {
                sendJson(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            String key = exchange.getRequestMethod().toUpperCase(Locale.ROOT)
                    + " " + exchange.getRequestURI().getPath();
            switch (key) {
                case "GET /status" -> sendJson(exchange, 200, status());
                case "POST /open" -> sendJson(exchange, 200, open(readBody(exchange)));
                case "POST /close" -> sendJson(exchange, 200, close(readBody(exchange)));
                case "POST /whitelist" -> sendJson(exchange, 200, whitelist(readBody(exchange)));
                default -> sendJson(exchange, 404, Map.of("error", "not found"));
            }
        } catch (Exception e) {
            try {
                sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
            } catch (IOException ignored) {
                // 连接断开
            }
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> status() {
        List<Map<String, Object>> screens = new ArrayList<>();
        for (BrowserManager.BrowserScreen screen : manager.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", screen.id());
            item.put("url", screen.url());
            item.put("host", hostOf(screen.url()));
            item.put("title", screen.title());
            item.put("revision", screen.revision());
            item.put("placed", !screen.world().isBlank());
            item.put("world", screen.world());
            item.put("x", screen.x());
            item.put("y", screen.y());
            item.put("z", screen.z());
            item.put("facing", screen.facing());
            item.put("width", screen.width());
            item.put("height", screen.height());
            screens.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("proxyPort", manager.proxyPort());
        out.put("clients", manager.protocol().browserCapableCount());
        out.put("whitelistEnabled", manager.whitelistEnabled());
        out.put("hosts", plugin.getConfig().getStringList("browser.allowed-hosts"));
        out.put("presets", PRESETS);
        out.put("screens", screens);
        return out;
    }

    private Map<String, Object> open(JsonObject body) {
        String id = str(body, "id");
        String url = str(body, "url");
        String title = str(body, "title");
        if (id == null || id.isBlank() || url == null || url.isBlank()) {
            return Map.of("error", "缺少 id 或 URL");
        }
        String error = manager.open(id, url, title == null ? "" : title);
        return error == null ? Map.of("ok", true) : Map.of("error", error);
    }

    private Map<String, Object> close(JsonObject body) {
        String id = str(body, "id");
        if (id == null || id.isBlank()) {
            return Map.of("error", "缺少 id");
        }
        String error = manager.close(id);
        return error == null ? Map.of("ok", true) : Map.of("error", error);
    }

    private Map<String, Object> whitelist(JsonObject body) {
        String action = str(body, "action");
        switch (action == null ? "" : action) {
            case "add" -> {
                String host = str(body, "host");
                if (host == null || host.isBlank()) {
                    return Map.of("error", "缺少 host");
                }
                String error = manager.allow(host);
                if (error != null && !error.equals("已在白名单")) {
                    return Map.of("error", error);
                }
                return Map.of("ok", true);
            }
            case "remove" -> {
                String host = str(body, "host");
                if (host == null || host.isBlank()) {
                    return Map.of("error", "缺少 host");
                }
                String error = manager.unallow(host);
                if (error != null && !error.equals("不在白名单")) {
                    return Map.of("error", error);
                }
                return Map.of("ok", true);
            }
            case "enabled" -> {
                boolean enabled = body != null && body.has("enabled")
                        && body.get("enabled").getAsBoolean();
                String error = manager.setWhitelistEnabled(enabled);
                return error == null ? Map.of("ok", true) : Map.of("error", error);
            }
            case "presets" -> {
                // 一键加入常用站点（已存在的忽略）。
                int added = 0;
                for (String host : PRESETS) {
                    if (manager.allow(host) == null) {
                        added++;
                    }
                }
                return Map.of("ok", true, "added", added, "total", PRESETS.size());
            }
            default -> {
                return Map.of("error", "未知 action");
            }
        }
    }

    // ---------- 工具 ----------

    private JsonObject readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return new JsonObject();
            }
            return gson.fromJson(text, JsonObject.class);
        }
    }

    private static String str(JsonObject body, String key) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) {
            return null;
        }
        return body.get(key).getAsString();
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
