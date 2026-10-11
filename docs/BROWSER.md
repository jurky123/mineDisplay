# MineDisplay 真浏览器屏幕（方向A）

> 状态：实施中（2026-10-11）。目标：世界里的真 Chromium 屏幕，
> 网络出口全部走服务端，玩家客户端只连服务端下发的地址。

## 1. 一句话方案

- Paper 是唯一出口：白名单校验 → 短期令牌 → 本地 HTTP 代理转发。
- Fabric + Rinku（Chromium）只渲染服务端下发的 `http://127.0.0.1:端口/display/令牌`，
  永不直连外网；点击/滚动/键盘回传服务端校验后转成 CEF 事件。
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
   │ 白名单 + 会话令牌                          │ 只连 127.0.0.1 代理 URL
   │                                            │ 输入事件 → ACTION 回传
   ▼                                            ▼
服务端 HTTP 代理 ──▶ 外网目标站          Paper 校验 → 下一屏/翻页/关屏
（127.0.0.1:端口/display/令牌）
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

- 客户端永不信任 URL：只接受 `http://127.0.0.1:<port>/display/<token>` 形状，
  其他一律拒绝（防服务端被攻破后下发恶意外链）。
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
- 代理端口默认 127.0.0.1:8768（8767 已被 MineAudio 本地曲库托管占用，
  与 mineAgent :8766 也错开），可配，仅回环监听。

## 7. MVP 验收

- 白名单外 host 被拒；非 127.0.0.1 URL 客户端拒收；token 吊销后代理 404。
- 一块浏览器屏：打开→显示网页→点击翻页→关屏释放纹理（无泄漏）。
- 无 mod 玩家：地图封面 + 聊天提示，不崩。
- `./gradlew build` + 单测通过。
