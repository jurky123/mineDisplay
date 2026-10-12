package com.minedisplay.client.fabric;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;

/**
 * JCEF 请求拦截：把页面发出的一切 http/https 请求改写到服务端代理的镜像路径，
 * 让真实站点（子资源/外链/跳转）都走服务端出口、且能被白名单约束。
 *
 * <p>镜像 URL 形状：{@code <代理基址>/<token>/<hostkey>/<path>[?query]}，
 * hostkey = base64url(origin)。只编码 origin、路径原样跟随，这样浏览器自己的
 * 路径归一化（{@code ../}）与服务端路径语义一致（经典 URL 前缀代理做法）。
 *
 * <p>upstream 跟踪：主文档响应头 {@code X-MineDisplay-Upstream} 给出重定向后的
 * 最终 URL；根相对路径按它的 origin、相对路径按它的目录解析。
 */
public final class BrowserRequestInterceptor {

    /** 服务端在代理响应上回的“最终取回 URL”头。 */
    public static final String UPSTREAM_HEADER = "X-MineDisplay-Upstream";

    private static final Map<CefBrowser, Context> CONTEXTS = new ConcurrentHashMap<>();
    private static boolean installed;

    private BrowserRequestInterceptor() {
    }

    /** 在 Rinku 初始化成功后调用一次（请求处理器是单槽位，Rinku 自己没用）。 */
    public static void install() {
        if (installed) {
            return;
        }
        try {
            var client = Rinku.getClient();
            if (client == null) {
                return;
            }
            client.getHandle().addRequestHandler(new CefRequestHandlerAdapter() {
                @Override
                public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame,
                        CefRequest request, boolean isNavigation, boolean isDownload,
                        String requestInitiator, BoolRef disableDefaultHandling) {
                    return CONTEXTS.containsKey(browser) ? ResourceHandler.INSTANCE : null;
                }
            });
            installed = true;
        } catch (Throwable t) {
            MineDisplayFabricClient.LOGGER.warn("[MineDisplay] 请求拦截器安装失败（站点资源将直连）：" + t);
        }
    }

    /** 浏览器创建后登记（解析出基址/token）。 */
    public static void register(RinkuBrowser browser, String proxyUrl) {
        Context ctx = Context.of(proxyUrl);
        if (ctx != null) {
            CONTEXTS.put(browser, ctx);
        }
    }

    public static void unregister(RinkuBrowser browser) {
        CONTEXTS.remove(browser);
    }

    // ---------- 拦截实现 ----------

    private static final class ResourceHandler extends CefResourceRequestHandlerAdapter {

        static final ResourceHandler INSTANCE = new ResourceHandler();

        @Override
        public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
            try {
                Context ctx = CONTEXTS.get(browser);
                if (ctx != null) {
                    String url = request.getURL();
                    String rewritten = rewrite(ctx, frame, url);
                    if (rewritten != null && !rewritten.equals(url)) {
                        request.setURL(rewritten);
                    }
                }
            } catch (Throwable ignored) {
                // 改写失败不改（最多直连/404），不打断加载
            }
            return false; // 继续加载
        }

        @Override
        public boolean onResourceResponse(CefBrowser browser, CefFrame frame, CefRequest request,
                                          CefResponse response) {
            try {
                Context ctx = CONTEXTS.get(browser);
                if (ctx != null && frame != null && frame.isMain()) {
                    String upstream = response.getHeaderByName(UPSTREAM_HEADER);
                    if (upstream != null && !upstream.isBlank()) {
                        ctx.setUpstream(upstream);
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            return false;
        }
    }

    /**
     * 改写规则：
     * 1. 代理前缀下的“首文档 / 镜像形态”→ 放行（主框架时从镜像还原 upstream）；
     * 2. 代理前缀下的相对路径 → 解析到主文档目录后再镜像；
     * 3. 代理 origin 的根相对路径 → 解析到主文档 origin 后镜像；
     * 4. 其他绝对 URL → 直接镜像；
     * 5. 非 http/https（data/blob/about…）→ 放行。
     *
     * @return 改写后的 URL；null 表示不改
     */
    static String rewrite(Context ctx, CefFrame frame, String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        boolean main = frame != null && frame.isMain();

        // 1/2. 代理前缀下
        if (url.startsWith(ctx.prefix)) {
            String rest = url.substring(ctx.prefix.length());
            if (rest.isEmpty() || rest.equals("/")) {
                return null; // 首文档
            }
            if (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?') {
                    end = i;
                    break;
                }
            }
            String key = rest.substring(0, end);
            String remainder = rest.substring(end);
            String origin = decodeOrigin(key);
            if (origin != null) {
                // 已是镜像形态：放行；主框架时还原出真实 URL
                if (main) {
                    ctx.setUpstream(origin + (remainder.isEmpty() ? "/" : remainder));
                }
                return null;
            }
            // 相对路径（文档在 <prefix>/ 下）：解析到主文档目录
            if (ctx.upstreamDir != null) {
                try {
                    URI resolved = new URI(ctx.upstreamDir).resolve(rest);
                    if (main) {
                        ctx.setUpstream(resolved.toString());
                    }
                    return ctx.mirror(resolved);
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        // 3. 代理 origin 的根相对路径：解析到主文档 origin
        if (ctx.sameAuthority(uri)) {
            if (ctx.upstream == null) {
                return null;
            }
            String absolute = ctx.originOf(ctx.upstream)
                    + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            if (main) {
                ctx.setUpstream(absolute);
            }
            try {
                return ctx.mirror(new URI(absolute));
            } catch (Exception e) {
                return null;
            }
        }

        // 4. 其他绝对 URL
        if (main) {
            ctx.setUpstream(uri.toString());
        }
        return ctx.mirror(uri);
    }

    // ---------- 每浏览器上下文 ----------

    static final class Context {

        final String prefix;        // <base>/<token>（无尾斜杠）
        final String baseAuthority; // scheme://authority
        final String baseScheme;    // http / https（小写）
        volatile String upstream;   // 主文档当前真实 URL
        volatile String upstreamDir;// 主文档所在目录（以 / 结尾）

        private Context(String prefix, String baseScheme, String baseAuthority) {
            this.prefix = prefix;
            this.baseScheme = baseScheme;
            this.baseAuthority = baseAuthority;
        }

        static Context of(String proxyUrl) {
            try {
                URI uri = new URI(proxyUrl);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (uri.getRawAuthority() == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                    return null;
                }
                String prefix = proxyUrl.endsWith("/")
                        ? proxyUrl.substring(0, proxyUrl.length() - 1)
                        : proxyUrl;
                return new Context(prefix, scheme, scheme + "://" + uri.getRawAuthority());
            } catch (Exception e) {
                return null;
            }
        }

        boolean sameAuthority(URI uri) {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals(baseScheme) && uri.getRawAuthority() != null
                    && (baseScheme + "://" + uri.getRawAuthority()).equals(baseAuthority);
        }

        String originOf(String url) {
            try {
                URI uri = new URI(url);
                return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
            } catch (Exception e) {
                return baseAuthority;
            }
        }

        /** 绝对 URL → 镜像 URL：<prefix>/<b64(origin)>/<rawPath>[?query]。 */
        String mirror(URI absolute) {
            String scheme = absolute.getScheme() == null ? "" : absolute.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            String authority = absolute.getRawAuthority();
            if (authority == null) {
                return null;
            }
            String origin = scheme + "://" + authority;
            String key = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(origin.getBytes(StandardCharsets.UTF_8));
            String path = absolute.getRawPath() == null ? "" : absolute.getRawPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            String query = absolute.getRawQuery();
            return prefix + "/" + key + "/" + path + (query == null ? "" : "?" + query);
        }

        void setUpstream(String url) {
            try {
                URI uri = new URI(url);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (uri.getRawAuthority() == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                    return;
                }
                this.upstream = uri.toString();
                String path = uri.getRawPath();
                int lastSlash = path == null ? -1 : path.lastIndexOf('/');
                String dirPath = lastSlash < 0 ? "/" : path.substring(0, lastSlash + 1);
                this.upstreamDir = scheme + "://" + uri.getRawAuthority() + dirPath;
            } catch (Exception ignored) {
                // 保持旧值
            }
        }
    }

    /** hostkey = base64url(origin)；只接受干净的 http/https origin。 */
    static String decodeOrigin(String key) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8);
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
            return scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
        } catch (Exception e) {
            return null;
        }
    }
}
