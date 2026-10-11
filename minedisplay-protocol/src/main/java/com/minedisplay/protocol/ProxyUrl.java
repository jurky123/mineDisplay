package com.minedisplay.protocol;

import java.net.URI;
import java.util.List;

/**
 * 回环代理 URL 校验（纯逻辑，便于单测，Paper/Client 共用）。
 *
 * <p>客户端只接受 {@code http://127.0.0.1:<port>/display/<token>} 形状，
 * 其他一律拒绝——客户端永不直连外网，永不信任服务端下发的任意 URL。
 */
public final class ProxyUrl {

    private ProxyUrl() {
    }

    /**
     * 校验代理 URL 形状。
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

    /** 拼代理 URL（服务端用）。 */
    public static String build(int proxyPort, String token) {
        return "http://127.0.0.1:" + proxyPort + "/display/" + token;
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
}
