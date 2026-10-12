package com.minedisplay.browser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import com.minedisplay.MineDisplayPlugin;
import com.minedisplay.protocol.ProxyUrl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 服务端出口代理 MVP：唯一外网出口。
 *
 * <p>监听地址由 {@code browser.bind-address} 配置：默认 {@code 127.0.0.1}
 * （单机/回环模式）；公网玩家要在 {@code browser.public-url} 配公网基址并把
 * 这里改成 {@code 0.0.0.0}（记得放行对应端口）。代理按令牌找到目标 host，
 * 校验白名单后转发。只允许 GET/HEAD，响应上限 8 MiB，超时 10 秒。
 *
 * <p>令牌随机 128bit、内存保存、TTL 过期即废；换 URL/关屏即吊销。
 * 审计只记 host，不记 token。
 */
public final class ProxyServer {

    /** 响应体上限。 */
    static final long MAX_BODY_BYTES = 8L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_REDIRECTS = 3;

    private final MineDisplayPlugin plugin;
    private HttpServer server;
    private int port;

    /** token -> 会话（内存，不持久化）。 */
    private final Map<String, ProxySession> sessions = new ConcurrentHashMap<>();

    public record ProxySession(String screenId, String targetUrl, long expiresAtMs) {
    }

    public ProxyServer(MineDisplayPlugin plugin) {
        this.plugin = plugin;
    }

    public int port() {
        return port;
    }

    public void start() throws IOException {
        int configured = plugin.getConfig().getInt("browser.proxy-port", 8768);
        String bind = plugin.getConfig().getString("browser.bind-address", "127.0.0.1");
        if (bind == null || bind.isBlank()) {
            bind = "127.0.0.1";
        }
        java.net.InetSocketAddress address = bind.equals("0.0.0.0") || bind.equals("::")
                ? new java.net.InetSocketAddress(configured)
                : new java.net.InetSocketAddress(bind, configured);
        server = HttpServer.create(address, 0);
        server.createContext("/display/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "MineDisplay-Proxy");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        this.port = server.getAddress().getPort();
        plugin.getLogger().info("浏览器出口代理监听 " + bind + ":" + port);
    }

    public void stop() {
        sessions.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 注册会话并返回令牌。 */
    public String issue(String screenId, String targetUrl, long ttlMs) {
        byte[] tokenBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        sessions.put(token, new ProxySession(screenId, targetUrl, System.currentTimeMillis() + ttlMs));
        return token;
    }

    /** 吊销某屏的全部令牌（换 URL/关屏/删屏时调用）。 */
    public void revokeScreen(String screenId) {
        sessions.entrySet().removeIf(entry -> entry.getValue().screenId().equals(screenId));
    }

    /** 存活会话数（status 用）。 */
    public int sessionCount() {
        return sessions.size();
    }

    // ---------- 请求处理 ----------

    private void handle(HttpExchange exchange) {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendStatus(exchange, 405);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String token = path.startsWith("/display/") ? path.substring("/display/".length()) : "";
            if (token.isEmpty() || token.contains("/")) {
                sendStatus(exchange, 404);
                return;
            }
            ProxySession session = sessions.get(token);
            if (session == null || System.currentTimeMillis() > session.expiresAtMs()) {
                sessions.remove(token);
                sendStatus(exchange, 404);
                return;
            }
            forward(exchange, session);
        } catch (Throwable t) {
            try {
                sendStatus(exchange, 502);
            } catch (IOException ignored) {
                // 连接已断开
            }
        } finally {
            exchange.close();
        }
    }

    private void forward(HttpExchange exchange, ProxySession session) throws IOException {
        List<String> allowedHosts = plugin.getConfig().getStringList("browser.allowed-hosts");
        URI target;
        try {
            target = new URI(session.targetUrl());
        } catch (Exception e) {
            sendStatus(exchange, 502);
            return;
        }
        // 每跳重检：host 白名单 + DNS 私网拦截。
        URI current = target;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String host = current.getHost();
            if (host == null || !ProxyUrl.hostAllowed(host, allowedHosts)) {
                sendStatus(exchange, 403);
                plugin.getLogger().warning("代理拒绝非白名单 host（屏 " + session.screenId() + "）");
                return;
            }
            try {
                requirePublicAddress(host);
            } catch (IOException e) {
                sendStatus(exchange, 403);
                plugin.getLogger().warning("代理拒绝受限地址 " + host + "（屏 " + session.screenId() + "）");
                return;
            }
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(exchange.getRequestMethod());
            connection.setRequestProperty("User-Agent", "MineDisplay/1.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    sendStatus(exchange, 502);
                    return;
                }
                current = current.resolve(location);
                continue;
            }
            if (status != 200) {
                connection.disconnect();
                sendStatus(exchange, 502);
                return;
            }
            String contentType = connection.getHeaderField("Content-Type");
            try (InputStream in = connection.getInputStream()) {
                byte[] body = readLimited(in, MAX_BODY_BYTES);
                exchange.getResponseHeaders().set("Content-Type",
                        contentType == null ? "application/octet-stream" : contentType);
                boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
                exchange.sendResponseHeaders(200, head ? -1 : body.length);
                if (!head) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            } finally {
                connection.disconnect();
            }
            return;
        }
        sendStatus(exchange, 502);
    }

    /** DNS 每条 A/AAAA 过私网/回环/link-local/保留地址。 */
    static void requirePublicAddress(String host) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IOException("受限地址: " + address.getHostAddress());
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int first = bytes[0] & 0xFF;
                int second = bytes[1] & 0xFF;
                // 运营商内网 100.64.0.0/10
                if (first == 100 && second >= 64 && second <= 127) {
                    throw new IOException("受限地址: " + address.getHostAddress());
                }
            } else if ((bytes[0] & 0xFE) == 0xFC) {
                // IPv6 fc00::/7 唯一本地地址
                throw new IOException("受限地址: " + address.getHostAddress());
            }
        }
    }

    private static byte[] readLimited(InputStream in, long maxBytes) throws IOException {
        try (in; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("响应超过上限 " + maxBytes + "B");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        byte[] body = ("minedisplay proxy: " + status).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 供单测：白名单 + 私网检查（不发请求）。 */
    static void checkTarget(URI target, List<String> allowedHosts) throws IOException {
        String host = target.getHost();
        if (host == null || !ProxyUrl.hostAllowed(host, allowedHosts)) {
            throw new IOException("非白名单 host");
        }
        requirePublicAddress(host);
    }
}
