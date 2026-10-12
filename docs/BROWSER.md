# MineDisplay 真浏览器屏幕（方向A）

> 状态：实施中（2026-10-11）。目标：世界里的真 Chromium 屏幕，
> 网络出口全部走服务端，玩家客户端只连服务端下发的地址。

## 1. 一句话方案

- Paper 是唯一出口：白名单校验 → 短期令牌 → HTTP 代理转发。
- Fabric + Rinku（Chromium）只渲染服务端下发代理基址拼接的屏 URL
  （`<基址>/令牌`；默认回环 `http://127.0.0.1:端口/display`，公网玩家用
  `browser.public-url` 下发如 `http://zkun.art:8768/display`）；
  点击/滚动/键盘回传服务端校验后转成 CEF 事件。
- 地图墙（M1）不动：无 mod 玩家继续看地图，原样降级。

## 2. 为什么是这个形状

- Rinku 26.2 现成（Chromium 151，Fabric，`Rinku.createBrowser(url, transparent)`，
  `getTextureIdentifier()` 直接给 `GuiGraphics.blit` 用，`sendMouse*/sendKey*` 输入齐全）。
- Rinku 原生只会直连外网。它的下载器有 `MirrorPolicy` + `download-mirror` 可配，
  但那是给 Chromium 二进制下载用的，不是给浏览器页面走代理的——页面代理必须我们自己做。
- 服务端渲染整页再推视频流（WebDisplaysTogether 路线）在本机只有 2 vCPU，
  一块屏就吃掉近半 CPU，且要在服务端跑完整 Chromium，不符合“简单直接优先”。
- 所以：渲染放客户端（各玩家自己的机器出 CPU），出口收归服务端（服务端做白名单和审计）。

## 3. 数据流

```text
管理员 browser allow <host>          玩家右键屏幕 / 键盘输入
        │                                      │
        ▼                                      ▼
Paper BrowserManager ──UPSERT/REMOVE──▶ Fabric Client (Rinku)
   │ 白名单 + 会话令牌                          │ 只打开下发基址的代理 URL
   │                                            │ 输入事件 → ACTION 回传
   ▼                                            ▼
服务端 HTTP 代理 ──▶ 外网目标站          Paper 校验 → 下一屏/翻页/关屏
（bind-address:端口/display/令牌）
```

1. 管理员 `browser open <screen-id> <url>`：Paper 校验 host 白名单 → 建会话
   （UUID 令牌，TTL 默认 30 分钟，单屏单令牌）→ 发 UPSERT（代理 URL + 几何）。
2. 管理员瞄准墙面 `browser place <screen-id> [宽] [高]`：贴面摆放（八格内命中面，
   朝外偏 1cm 防 z-fighting），几何持久化进 browser.yml，revision+1 重发 UPSERT。
3. 客户端收到 UPSERT：`Rinku.createBrowser(proxyUrl)`（URL 未变则复用实例、只调
   分辨率）→ `COLLECT_SUBMITS` 阶段把纹理画成世界里的 quad（距离/视锥裁剪，
   单屏 64 格内可见）→ ready 回传。没装 mod 的玩家看不到（地图封面后续接）。
3. 客户端把代理 URL 告诉 Rinku 后，Rinku 内核的网络请求全部打到服务端代理端口；
   代理按令牌找到目标 host，校验白名单后转发（只 GET/HEAD，大小上限，超时熔断）。
4. 玩家对屏幕的点击/滚轮/键盘 → ACTION 包 → Paper 校验权限/距离/朝向 →
   通过则记 input-sequence，回包SCREEN_INPUT 转 CEF 事件（MVP 可先只支持点击+滚轮）。

## 4. 安全边界（MVP 即有）

- 客户端永不信任 URL：只接受「WELCOME 下发的代理基址 + `/令牌`」形状，
  scheme/host/port/路径前缀全部钉死，其他一律拒绝
  （防服务端被攻破后下发恶意外链）。
- 服务端白名单（导航目标）：精确或子域匹配；子资源不限 host 但每跳都过
  私网/回环/link-local 检查；重定向每跳重检，最多 3 跳；只允许 GET/HEAD；
  响应上限 8 MiB；超时 10 秒。
- 令牌：随机 128bit，内存保存（不进仓库不打日志），TTL 过期即废；
  换 URL/关屏/删屏即吊销，旧 Rinku 实例 close。
- 审计：每次 open/navigate/close 记日志（who/when/screen/host，不记 token）。

## 5. 协议（minedisplay-protocol，无 Bukkit/Fabric 依赖）

通道 `minedisplay:main`，JSON 信封沿 MineAudio 风格（protocol/type/session/revision/data）：

| 类型 | 方向 | 内容 |
|---|---|---|
| HELLO | C→S | mod 版本、mc 版本、能力（`rinku`/`browser_screen`） |
| WELCOME | S→C | 协议版本、代理端口、心跳间隔 |
| UPSERT | S→C | screenId、revision、代理 URL、世界/几何、标题 |
| REMOVE | S→C | screenId（越界/吊销/删屏） |
| ACTION | C→S | 点击/滚轮/键盘/READY/ERROR（只引用 screenId，不带 URL） |
| INPUT | S→C | 服务端确认后的输入转述（MVP 可与 UPSERT 合并，后续拆） |

限制：单包 ≤24 KiB（沿 MineAudio），URL 长度 ≤8 KiB，revision 过期丢弃。

## 6. 模块

```text
minedisplay-protocol/    # 纯 Java：消息 DTO + 校验 + 单测
minedisplay-paper/       # BrowserManager（会话/白名单/代理）+ browser 命令
minedisplay-client/      # Fabric：Rinku 渲染 + 输入回传 + 世界 quad
```

- Paper 不依赖 Rinku；Client 编译期 `modImplementation rinku-fabric`，
  Rinku 缺失时降级（地图封面 + 提示）。
- 代理端口默认 8768（8767 已被 MineAudio 本地曲库托管占用，与 mineAgent :8766
  也错开）。**线上形态（HTTPS）**：Paper 代理只绑 `127.0.0.1:8768`，
  `browser.public-url: https://zkun.art/display`；mineAgent 反代
  `/display/* → 本机 8768`，客户端拿到的屏地址走 443/HTTPS（内容与令牌
  不出现在明文公网上，公网也不需要放行 8768）。
  回退形态（明文）：`bind-address: 0.0.0.0` + `public-url: http://zkun.art:8768/display`
  （只剩单机/开发用；无 TLS）。
- 代理透传 `Range`/`206`/`Content-Range` 并流式回传（`<video>` 分段播放、
  大文件首字节更快），`Accept-Encoding`/`Content-Encoding` 透传（跨境省流量）。
- 方法：`GET/HEAD/POST`（站点 API 多为 POST，如 YouTube innertube；请求体上限
  1 MiB）；透传 `Content-Type`/`Accept-Language`/自定义 `X-*` 头；POST 重定向按
  浏览器惯例降级为 GET。

## 7. MVP 验收

- 白名单外 host 被拒；与下发基址不匹配的 URL 客户端拒收；token 吊销后代理 404。
- 一块浏览器屏：打开→显示网页→点击翻页→关屏释放纹理（无泄漏）。
- 世界内可见：`browser place` 后墙面出现屏；跨维度不串屏；远离/视锥外不渲染。
- 指哪点哪：瞄准屏幕左/右键直接点到网页对应位置；F8 仍可开操作层（优先瞄准的屏）。
- 真实站点：CSS/图片/脚本/外链跳转都经服务端代理，页面样式与链接可用；
  点链接导航不直连目标站；F8 地址栏改网址/刷新生效。
- 公网玩家：客户端能从 `public-url` 基址取到内容（腾讯云放行 TCP 8768）。
- Rinku 内核下载失败不丢屏：聊天给出原因，修好（镜像）后 F8 重试/重启游戏即恢复。
- 无 mod 玩家：地图封面 + 聊天提示，不崩。
- `./gradlew build` + 单测通过。

## 8. 真实站点代理（子资源/外链都走服务端）

客户端在 JCEF 的请求层装了拦截器（Rinku 的 request handler 槽位没被占用）：

- 页面发出的所有 http/https 请求（文档、脚本、样式、图片、字体、XHR、链接跳转）
  都改写成**镜像路径**打回服务端代理：
  `{base}/{token}/{hostkey}/{path}?{query}`，hostkey = base64url(origin)。
  只编码 origin、路径原样跟随，浏览器自己的 `../` 归一化与服务端路径语义一致。
- **导航单独处理**：点链接/redirect 走 CEF `onBeforeBrowse` 取消并改写后再加载
  （资源层的 `setURL` 改不动主框架导航，不拦的话客户端会直连目标站——
  国内直连 google/youtube 这类直接失败）。子资源在 `onBeforeResourceLoad` 改写。
- 代理按 hostkey 解码 origin、拼上路径去取；**导航目标走白名单**，
  子资源放行任意公网 host（真实站点的 CDN 不可能逐个加白名单）；
  私网/回环/link-local 永远拦（防 SSRF），与请求类型无关。
- 相对路径解析：主文档目录从响应头 `X-MineDisplay-Upstream`（重定向后的最终 URL）
  跟踪；根相对按 origin、相对路径按目录解析后再镜像。
- 响应只透传 `Content-Type`（丢弃 CSP/CORS/Cookie/缓存头），
  页面不会因为 CSP 拒绝同源代理资源，也没有第三方 Cookie 可用。

## 9. 网页管理页（Mine 门户 /screens）

- MineDisplay 起一个本地控制 API：`127.0.0.1:8769`（`browser.control-port`），
  Bearer 令牌自动生成在 `plugins/MineDisplay/control-token.txt`（0600）。
  端点：`GET /status`、`POST /open|/close|/whitelist`（add/remove/enabled/presets）。
- MineAgent 门户加 `/screens` 管理页：屏幕列表（含摆放状态）、开屏/换网址、关屏、
  导航白名单开关 + 一键加入常用站；`/api/display/*` 转发到控制 API，
  GET 任意登录用户、写操作限管理员。
- 导航白名单可整体关闭（`browser.whitelist-enabled`，默认关）：关闭后任意公网站点
  都能打开/跳转（内网地址仍永远被拦）；页面上的开关即时生效（动态读配置）。
  子资源本来就不受白名单限制。

## 10. 已知限制（MVP）

- WebSocket/SSE 等长连接不走代理（CEF 直连目标站）；登录类站点不可用（无 Cookie）。
- 视频：普通视频（非 DRM）具备播放条件（Range/206 + 流式代理 + 内核解码）；
  但 JCEF 内核不带 **Widevine**（DRM 视频如 Netflix/YouTube 电影不可用），
  H.264/AAC 支持取决于内核构建；YouTube 需要 HTTPS（已是线上形态）才有
  完整能力（Service Worker 等安全上下文特性）。
- 强反爬站点取不回：目标站按 TLS/请求特征识别非浏览器请求（如 chatgpt.com 被
  Cloudflare 直接 403 "Enable JavaScript"），或需要登录态。这类站会在屏上显示
  「无法显示此页面（403）…」的说明页（代理把上游状态码原样透传，不再一律 502）。
- iframe 里跨站文档的“根相对路径”按主文档 origin 解析（边缘场景会取错）。
- 世界里的屏：屏幕背面也能看到镜像画面（单面绘制没做）；
  世界里的滚轮被原版拦（滚动/打字走 F8 覆盖层）。
- F8 操作层地址栏显示当前真实 URL（跟随页面内跳转），回车可改网址、⟳ 刷新
  （限 `minedisplay.admin`，服务端仍走白名单/私网校验重新开屏）。
- 没装 mod 的玩家看不到浏览器屏（M1 地图封面墙作为可选降级，未自动生成）。
- 每个浏览器能力客户端进服即创建全部屏的 CEF 实例（与距离无关），
  屏数量多时要改成按距离懒创建/释放。

## 11. 客户端前置：Rinku 与 JCEF 内核镜像

- 客户端需要 Rinku（LGPL-2.1，外部依赖，不内嵌）；Rinku 首次启动会从 GitHub
  下载 JCEF/Chromium 内核（约 180MB），国内网络常超时失败。
- 服务端已托管镜像：mineAgent `https://zkun.art/rinku-jcef/<tag>/<asset>`
  （与上游 release asset 同形状）。minedisplay-client 的 preLaunch 会在
  游戏启动前把 `config/rinku/rinku.properties` 的 `download-mirror` 写为镜像、
  policy 为 `PREFER_CONFIGURED`（先镜像、失败回退官方）；玩家自定义镜像不接管。
- 手动兜底（旧版本/排障）：
  1. 编辑 `config/rinku/rinku.properties`：
     `download-mirror=https://zkun.art/rinku-jcef`、
     `download-mirror-policy=PREFER_CONFIGURED`
  2. 可删 `rinku-libraries/.staging` 残留后重启游戏（下载与初始化只在启动时做，
     一次失败会封死本进程的重试，必须重启）。
- Rinku 升级会导致 `<tag>` 变化：需要把新 tag 的全平台六件套重新同步到
  `data/webui/rinku-jcef/<tag>/` 并校验 sha256（见 rinkujcef 运维备注）。
- 排障：日志里 `Rinku-Downloader` 线程 `NoSuchMethodError:
  ...BoundedInputStream.builder()` 并在解压阶段卡死 = 客户端有 mod 内嵌了旧
  commons-io（<2.16）盖掉了 MC 26.2 自带的 2.20.0。升级/移除该 mod 的内嵌
  （MineAudio 客户端已在 0.5.6 把内嵌版本对齐到 2.20.0）。
- 下载慢：镜像可经 mineAgent `web.rinkuJcefRedirect` 302 到加速源
  （当前 `ghfast.top`），客户端不用改配置。
