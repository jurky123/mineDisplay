package com.minedisplay.protocol;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 代理 URL 校验（纯逻辑，便于单测，Paper/Client 共用）。
 *
 * <p>服务端在 WELCOME 里下发代理基址（形如 {@code http://zkun.art:8768/display}，
 * 回环模式为 {@code http://127.0.0.1:8768/display}），屏 URL 必须是
 * {@code <基址>/<token>}。客户端只认服务端下发的这个基址，其他一律拒绝——
 * 客户端永不直连任意外链（防服务端被攻破后下发恶意外链）。
 */
public final class ProxyUrl {

    /** 令牌字符集：URL 安全字符，不含路径分隔符；长度 8..128。 */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~-]{8,128}");

    private ProxyUrl() {
    }

    /**
     * 校验屏 URL 与基址完全匹配：scheme/host/port 相同、路径为
     * {@code <基址路径>/<单段令牌>}，且不带 userinfo/query/fragment。
     *
     * @throws ProtocolException 不匹配
     */
    public static void requireProxyUrl(String url, String baseUrl) throws ProtocolException {
        URI uri = parse(url);
        URI base = parse(baseUrl);
        if (!uri.getScheme().equalsIgnoreCase(base.getScheme())) {
            throw new ProtocolException("proxy url scheme mismatch");
        }
        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(base.getHost())) {
            throw new ProtocolException("proxy url host mismatch");
        }
        if (effectivePort(uri) != effectivePort(base)) {
            throw new ProtocolException("proxy url port mismatch");
        }
        String basePath = base.getPath() == null ? "" : stripTrailingSlash(base.getPath());
        String path = uri.getPath() == null ? "" : uri.getPath();
        String prefix = basePath + "/";
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            throw new ProtocolException("proxy url must be <base>/<token>");
        }
        if (!isToken(path.substring(prefix.length()))) {
            throw new ProtocolException("proxy url bad token");
        }
    }

    /** 只查结构形状：http/https、有 host、末段是合法令牌；不知道基址时用。 */
    public static void validateShape(String url) throws ProtocolException {
        URI uri = parse(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1 || !isToken(path.substring(slash + 1))) {
            throw new ProtocolException("proxy url must end with /<token>");
        }
    }

    /**
     * 校验回环代理 URL 形状（旧形状，保留给单测与回退路径）。
     *
     * @param proxyPort 服务端代理端口（0 表示只检形状，不检端口）
     * @throws ProtocolException 形状不符
     */
    public static void requireProxy(String url, int proxyPort) throws ProtocolException {
        if (url == null || url.isBlank()) {
            throw new ProtocolException("missing url");
        }
        if (url.length() > ProtocolLimits.MAX_URL_LENGTH) {
            throw new ProtocolException("url too long");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new ProtocolException("bad url", e);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new ProtocolException("proxy url must be http loopback");
        }
        String host = uri.getHost();
        if (!"127.0.0.1".equals(host)) {
            throw new ProtocolException("proxy url must target 127.0.0.1");
        }
        if (proxyPort > 0 && uri.getPort() != proxyPort) {
            throw new ProtocolException("proxy url port mismatch");
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/display/") || path.length() <= "/display/".length()) {
            throw new ProtocolException("proxy url must be /display/<token>");
        }
        if (uri.getUserInfo() != null) {
            throw new ProtocolException("proxy url must not carry userinfo");
        }
    }

    /** 从代理 URL 提取令牌（已校验形状后调用）。 */
    public static String tokenOf(String url) throws ProtocolException {
        requireProxy(url, 0);
        try {
            String path = new URI(url).getPath();
            return path.substring("/display/".length());
        } catch (Exception e) {
            throw new ProtocolException("bad url", e);
        }
    }

    /** 拼回环代理 URL（服务端回环模式用）。 */
    public static String build(int proxyPort, String token) {
        return "http://127.0.0.1:" + proxyPort + "/display/" + token;
    }

    /** 用基址拼屏 URL（服务端配置了 public-url 时用）。 */
    public static String buildFromBase(String baseUrl, String token) {
        return baseUrl + "/" + token;
    }

    /**
     * 归一化代理基址：补默认 {@code /display} 路径、去尾斜杠、host 小写。
     *
     * @throws ProtocolException 非法基址（非 http/https、缺 host、带 userinfo/query 等）
     */
    public static String normalizeBase(String rawBase) throws ProtocolException {
        URI uri = parse(rawBase);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6
        }
        int port = uri.getPort();
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "/display";
        } else {
            path = stripTrailingSlash(path);
        }
        return scheme + "://" + host + (port > 0 ? ":" + port : "") + path;
    }

    /** 白名单 host 匹配：精确或子域。 */
    public static boolean hostAllowed(String host, List<String> allowedHosts) {
        if (host == null || allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        for (String rule : allowedHosts) {
            String entry = rule.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals(entry) || lower.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 内部工具 ----------

    private static URI parse(String value) throws ProtocolException {
        if (value == null || value.isBlank()) {
            throw new ProtocolException("missing url");
        }
        if (value.length() > ProtocolLimits.MAX_URL_LENGTH) {
            throw new ProtocolException("url too long");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            throw new ProtocolException("bad url", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new ProtocolException("url must be http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ProtocolException("url missing host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ProtocolException("url must not carry userinfo");
        }
        if (uri.getRawQuery() != null) {
            throw new ProtocolException("url must not carry query");
        }
        if (uri.getRawFragment() != null) {
            throw new ProtocolException("url must not carry fragment");
        }
        if (hasMalformedPort(uri)) {
            throw new ProtocolException("url bad port");
        }
        return uri;
    }

    private static boolean hasMalformedPort(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return true;
        }
        int closingBracket = authority.lastIndexOf(']');
        int colon = authority.lastIndexOf(':');
        boolean hasPortDelimiter = colon >= 0 && colon > closingBracket;
        return hasPortDelimiter && uri.getPort() < 0;
    }

    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isToken(String token) {
        if (token.equals(".") || token.equals("..")) {
            return false;
        }
        return TOKEN.matcher(token).matches();
    }

    private static String stripTrailingSlash(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
