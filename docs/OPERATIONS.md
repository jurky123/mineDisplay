# MineDisplay 操作与交付规范

以下命令/配置/脚本是拟定规范，当前尚不可执行。已实现状态以 README 阶段表与发行说明为准。

## 拟定命令与权限

根命令 `/minedisplay`，别名 `/display`。ID 使用简单字母、数字、下划线与短横线，不接受任意路径。

| 命令 | 用途 | 权限 |
|---|---|---|
| `media import <id> <incoming-file>` | 导入管理员放置的素材 | `minedisplay.media` |
| `media list / info <id>` | 素材索引与转换状态 | `minedisplay.admin` |
| `create <id> <width-tiles> <height-tiles>` | 基于所看墙面创建地图屏幕 | `minedisplay.admin` |
| `set <id> <media-id>` | 绑定素材；完成校验后原子替换 | `minedisplay.admin` |
| `remove <id>` | 删除屏幕；保留共享素材 | `minedisplay.admin` |
| `list / info <id> / status` | 查看配置、状态与降级原因 | `minedisplay.admin` |
| `play / pause / stop <id>` | M4 播放控制 | `minedisplay.control` |
| `seek <id> <seconds>` | M4 定位，按能力返回结果 | `minedisplay.control` |
| `hud <id> / hud off` | M2 图片 HUD；客户端可隐藏 | `minedisplay.hud` |
| `reload` | 校验并替换配置，保留合法运行状态 | `minedisplay.admin` |

管理员节点默认 op；`minedisplay.view` / `minedisplay.hud` 默认普通玩家可用。屏幕可配置额外观看权限。
命令补全只列出可见 ID；客户端所有动作重新验证权限。地图实体本身不适合保密内容：地图物品/世界数据可能留存，严格私有素材只用受控客户端分发且仍不能防观看者截图。

## 素材准备

M1 图片：优先 PNG，管理员将文件放到插件 incoming 目录，运行导入后检查 info，再绑定屏幕。
目标画布宽高为 tiles×128；图片保留比例时会有背景边，不想留边可显式选择裁剪。
透明图在地图端需背景色；地图色板与原图不同属原版限制。

M4 视频：在离线机器运行未来的 `tools/prepare_media` 工具，检查输出 profile、时长、封面和摘要。
不要直接把未经验证的大型 MP4 作为开服自动转码任务；发布 manifest 与派生文件到受控 HTTPS 站点后再登记素材。
转换工具的确切 CLI 与编码参数在选型验证后补充，本阶段不提供伪可运行脚本。
仅分享有权使用的素材；素材、访问票据和站点部署配置不进 Git。

## 部署顺序与安装包

1. 备份 MineDisplay 定义、素材索引以及世界地图数据；确认目标 Paper/Java 与插件版本。
2. 按需安装兼容 MineUI（静态 HUD/管理）与 MineAudio（已注册声音）；只有对应集成需要它们。
3. 部署 MineDisplay jar；可选资源包通过 PackHost 现有 packs 流程合并，不直接访问其内部类。
4. M3/M4 部署受控 HTTPS 媒体目录与配额，确认权限、缓存头、摘要和客户端可达性。
5. 在维护窗口重启 Paper；部署脚本默认只部署，不能自动踢人重启。部署前检查服务器在线与正在进行的游戏。
6. 验证原版/增强客户端、已有游戏和 HUD 同时使用，确认素材回退。

未来客户端包名带版本，如 `minedisplay-client-kit-26.2-0.1.0.zip`，只包含本项目产物。
前置 mod 第一次安装时按需要单独提供，后续升级不重复打包。构建与验证后上传：

```bash
curl -s -F "file=@<versioned-zip>" https://temp.sh/upload
```

当前没有 jar/zip，不上传文档冒充安装包。公开 README 不写服务器地址、账号、令牌或运行时路径细节。

## 故障与回滚

| 现象 | 排查顺序 |
|---|---|
| 原版屏幕空白 | 素材导入状态、区块/实体对账、地图 renderer、权限、map ID 对应世界 |
| HUD 不显示 | MineUI 是否安装、客户端版本/能力、HUD 本地开关、HTTPS 白名单 |
| 高清图只有封面 | HELLO 版本、READY/ERROR、摘要、站点可达、纹理预算 |
| 视频卡顿/不同步 | profile 与解码支持、缓冲、CPU/GPU、同步 RTT/误差、并发上限 |
| 无声音 | 静音/音量/距离、有声视频优先级、实际音轨，不默认归因于 MineAudio |
| 画框重复 | 区块载入对账、PDC 和 UUID、是否手工复制过运行时数据 |

回滚先停止新播放，部署上一版本，使用对应 schema 备份恢复定义；保留旧摘要素材直到无引用。
不要删除世界 map 文件“修复”图片，不直接复用他人的 map ID。缓存可以在停止使用后清理，素材源文件不是缓存。
reload 验证失败保留旧配置；当前播放器换配置必须通过 revision 更新，避免陈旧下载回调复活旧内容。
