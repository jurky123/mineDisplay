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
2. 客户端收到 UPSERT：`Rinku.createBrowser(proxyUrl)` → 世界渲染（方块姿态 quad，
   MVP 用 DisplayManager 的屏幕几何）→ READY 回传。
3. 客户端把代理 URL 告诉 Rinku 后，Rinku 内核的网络请求全部打到服务端代理端口；
   代理按令牌找到目标 host，校验白名单后转发（只 GET/HEAD，大小上限，超时熔断）。
4. 玩家对屏幕的点击/滚轮/键盘 → ACTION 包 → Paper 校验权限/距离/朝向 →
   通过则记 input-sequence，回包SCREEN_INPUT 转 CEF 事件（MVP 可先只支持点击+滚轮）。

## 4. 安全边界（MVP 即有）

- 客户端永不信任 URL：只接受「WELCOME 下发的代理基址 + `/令牌`」形状，
  scheme/host/port/路径前缀全部钉死，其他一律拒绝
  （防服务端被攻破后下发恶意外链）。
- 服务端白名单：精确或子域匹配；DNS 每条 A/AAAA 过私网/回环/link-local；
  重定向每跳重检，最多 3 跳；只允许 GET/HEAD；响应上限 8 MiB；超时 10 秒。
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
  也错开）。`browser.bind-address` 默认 `127.0.0.1`（单机/开发）；
  公网玩家场景改 `0.0.0.0` 并配 `browser.public-url`（如
  `http://zkun.art:8768/display`），云防火墙放行对应端口（IPv4/IPv6 都放行，
  避免 DNS 解析到 AAAA 时等待回退）。

## 7. MVP 验收

- 白名单外 host 被拒；与下发基址不匹配的 URL 客户端拒收；token 吊销后代理 404。
- 一块浏览器屏：打开→显示网页→点击翻页→关屏释放纹理（无泄漏）。
- 公网玩家：客户端能从 `public-url` 基址取到内容（腾讯云放行 TCP 8768）。
- Rinku 内核下载失败不丢屏：聊天给出原因，修好（镜像）后 F8 重试/重启游戏即恢复。
- 无 mod 玩家：地图封面 + 聊天提示，不崩。
- `./gradlew build` + 单测通过。

## 8. 已知限制（MVP）

- 代理只转换屏 URL 的首个文档；页面内的子资源与外链跳转（绝对 URL）
  目前由 CEF 直接加载，尚不经过服务端代理，也不受白名单约束。
  简单页面（如 example.com）可正常显示；重资源站点会缺图/缺样式。
  下一步：用 CEF 请求拦截把所有子请求重写回代理路径（需评估 Rinku 暴露面）。
- 输入回传 MVP 只做点击/滚轮/键盘；地址栏跳转（navigate）协议位已留，服务端未实现。
- 屏幕几何/世界 quad 未落地：现在覆盖层（F8）先行，世界里摆屏后续做。

## 9. 客户端前置：Rinku 与 JCEF 内核镜像

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
