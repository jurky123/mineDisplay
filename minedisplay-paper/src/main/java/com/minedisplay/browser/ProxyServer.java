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
        // 上游连接池：页面子资源很多，保持 keep-alive 与足够并发（默认池只有 5 条）。
        System.setProperty("http.maxConnections", "16");
        server = HttpServer.create(address, 0);
        server.createContext("/display/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8, runnable -> {
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
            // 路径形状：/display/<token>（首文档）
            //           /display/<token>/<hostkey>[/<path>]（镜像：hostkey = base64url(origin)）
            String rawPath = exchange.getRequestURI().getRawPath();
            if (rawPath == null || !rawPath.startsWith("/display/")) {
                sendStatus(exchange, 404);
                return;
            }
            String rest = rawPath.substring("/display/".length());
            String token;
            String tail;
            int slash = rest.indexOf('/');
            if (slash < 0) {
                token = rest;
                tail = "";
            } else {
                token = rest.substring(0, slash);
                tail = rest.substring(slash + 1);
            }
            if (token.isEmpty()) {
                sendStatus(exchange, 404);
                return;
            }
            ProxySession session = sessions.get(token);
            if (session == null || System.currentTimeMillis() > session.expiresAtMs()) {
                sessions.remove(token);
                sendStatus(exchange, 404);
                return;
            }
            URI target = resolveTarget(session, tail, exchange.getRequestURI().getRawQuery());
            if (target == null) {
                sendStatus(exchange, 404);
                return;
            }
            forward(exchange, session, target);
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

    /**
     * 解析请求目标：空 tail = 屏的首文档（会话 targetUrl）；
     * 否则 tail 形如 {@code <hostkey>[/<path>][?query]}，hostkey 必须解码成
     * 一个纯 origin（scheme://host[:port]，无路径/查询/凭证）。
     *
     * @return 目标 URL；非法返回 null
     */
    static URI resolveTarget(ProxySession session, String tail, String rawQuery) {
        if (tail.isEmpty()) {
            try {
                URI document = new URI(session.targetUrl());
                if (rawQuery == null || rawQuery.isEmpty()) {
                    return document;
                }
                return new URI(document.getScheme(), document.getAuthority(),
                        document.getPath(), rawQuery, null);
            } catch (Exception e) {
                return null;
            }
        }
        int slash = tail.indexOf('/');
        String hostKey = slash < 0 ? tail : tail.substring(0, slash);
        String path = slash < 0 ? "" : tail.substring(slash + 1);
        String origin = decodeOrigin(hostKey);
        if (origin == null) {
            return null;
        }
        try {
            return new URI(origin + "/" + path + (rawQuery == null || rawQuery.isEmpty()
                    ? "" : "?" + rawQuery));
        } catch (Exception e) {
            return null;
        }
    }

    /** hostkey = base64url(origin)；只接受干净的 http/https origin。 */
    static String decodeOrigin(String hostKey) {
        try {
            String value = new String(java.util.Base64.getUrlDecoder().decode(hostKey),
                    StandardCharsets.UTF_8);
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                return null;
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                return null;
            }
            return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + uri.getRawAuthority();
        } catch (Exception e) {
            return null;
        }
    }

    private void forward(HttpExchange exchange, ProxySession session, URI target) throws IOException {
        List<String> allowedHosts = plugin.getConfig().getStringList("browser.allowed-hosts");
        // 白名单只约束“导航目标”（文档/框架）；页面子资源（脚本/样式/图片/字体/XHR）
        // 允许任意公网 host——真实站点的 CDN 太多了，否则页面必缺资源。
        // 私网/回环/link-local 对每一跳都拦（防 SSRF），这条与 dest 无关。
        boolean navigation = isNavigationDest(exchange);
        boolean whitelistEnabled = plugin.getConfig().getBoolean("browser.whitelist-enabled", false);
        // 每跳重检：host 白名单（导航且启用时）+ DNS 私网拦截。
        URI current = target;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String host = current.getHost();
            if (host == null || (navigation && whitelistEnabled && !ProxyUrl.hostAllowed(host, allowedHosts))) {
                sendStatus(exchange, 403);
                plugin.getLogger().warning("代理拒绝非白名单导航 host（屏 " + session.screenId() + "）");
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
            // 真实站点兼容：伪装成正常浏览器 UA（部分站点会按 UA 拦非浏览器请求）。
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                            + " Chrome/151.0.0.0 Safari/537.36 MineDisplay/1.0");
            connection.setRequestProperty("Accept", "*/*");
            // 透传浏览器的 Accept-Encoding：上游压缩后的字节原样回给 CEF（它自己解压），
            // 跨境链路上 HTML/CSS/JS 能省 5~10 倍字节。
            String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            if (acceptEncoding != null && !acceptEncoding.isBlank()) {
                connection.setRequestProperty("Accept-Encoding", acceptEncoding);
            }
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
            if (status < 200 || status >= 300) {
                // 上游明确拒绝/异常：原样把状态码给客户端，附可读原因（不再一律 502，
                // 否则 Cloudflare 的 403 会伪装成网关错误，看不出真实原因）。
                connection.disconnect();
                plugin.getLogger().info("代理上游返回 " + status + "（屏 " + session.screenId()
                        + "，host " + current.getHost() + "）");
                byte[] body = upstreamErrorPage(status, current).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
                exchange.sendResponseHeaders(status, head ? -1 : body.length);
                if (!head) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
                return;
            }
            String contentType = connection.getHeaderField("Content-Type");
            String contentEncoding = connection.getHeaderField("Content-Encoding");
            try {
                try (InputStream in = connection.getInputStream()) {
                    byte[] body = readLimited(in, MAX_BODY_BYTES);
                    exchange.getResponseHeaders().set("Content-Type",
                            contentType == null ? "application/octet-stream" : contentType);
                    if (contentEncoding != null && !contentEncoding.isBlank()) {
                        exchange.getResponseHeaders().set("Content-Encoding", contentEncoding);
                    }
                    // 告诉客户端最终取回的 URL（重定向后的），用于页面相对路径解析。
                    exchange.getResponseHeaders().set("X-MineDisplay-Upstream", current.toASCIIString());
                    boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
                    exchange.sendResponseHeaders(200, head ? -1 : body.length);
                    if (!head) {
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    }
                }
            } catch (IOException e) {
                connection.disconnect();
                throw e;
            }
            // 注意：不要 disconnect——连接回 keep-alive 池，后续子资源省掉 TCP/TLS 握手。
            return;
        }
        sendStatus(exchange, 502);
    }

    /**
     * 是否“导航请求”：Chromium 用 Sec-Fetch-Dest 标注（document/iframe）。
     * 缺失时按导航处理（保守：要求白名单）。
     */
    static boolean isNavigationDest(HttpExchange exchange) {
        String dest = exchange.getRequestHeaders().getFirst("Sec-Fetch-Dest");
        if (dest == null || dest.isBlank()) {
            return true;
        }
        String value = dest.trim().toLowerCase(java.util.Locale.ROOT);
        return value.equals("document") || value.equals("iframe") || value.equals("frame");
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
        String reason = switch (status) {
            case 403 -> "目标不在导航白名单，或指向内网地址（被安全策略拒绝）。";
            case 404 -> "屏幕令牌无效/过期，或资源路径不合法。";
            case 405 -> "只允许 GET/HEAD。";
            case 502 -> "取回失败：目标站连不上或超时。";
            default -> "请求失败（" + status + "）。";
        };
        byte[] body = ("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<body style=\"font:14px/1.6 system-ui,sans-serif;padding:28px;color:#333\">"
                + "<h3 style=\"margin:0 0 8px\">无法显示此页面（" + status + "）</h3>"
                + "<p style=\"color:#666\">" + reason + "</p></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 上游返回非 2xx 时给浏览器看的说明页（区分常见拦截/异常原因）。 */
    static String upstreamErrorPage(int status, URI url) {
        String reason;
        if (status == 401 || status == 403) {
            reason = "目标站拦截了这次取回（常见：Cloudflare／反爬验证、需要登录）。"
                    + "这类站点把非浏览器请求当作爬虫，本代理取不回内容。";
        } else if (status == 404) {
            reason = "目标站没有这个页面（404）。";
        } else if (status == 429) {
            reason = "被目标站限流（429），稍后再试。";
        } else if (status >= 500) {
            reason = "目标站服务异常（" + status + "）。";
        } else {
            reason = "目标站返回 " + status + "。";
        }
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<body style=\"font:14px/1.6 system-ui,sans-serif;padding:28px;color:#333\">"
                + "<h3 style=\"margin:0 0 8px\">无法显示此页面（" + status + "）</h3>"
                + "<p style=\"color:#666\">" + reason + "</p>"
                + "<p style=\"color:#999;word-break:break-all\">" + escapeHtml(url.toASCIIString()) + "</p>"
                + "</body></html>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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
