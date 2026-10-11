# MineDisplay

> Minecraft Java 26.2 · Paper 插件 + 可选 Fabric 客户端 · **M1 已实现：原版静态地图墙**

MineDisplay 让服务器展示图片与视频：世界里的画框、公告墙、电影院屏幕，以及类似侧边计分板的图片 HUD。
原版玩家可以看地图拼接图片；安装客户端后可使用高清世界屏幕和视频。管理界面与图片 HUD 优先复用 MineUI。

## 能力与阶段

| 场景 | 原版客户端 | 增强客户端 | 计划阶段 |
|---|---|---|---|
| 墙面图片 / 画框 | 128×128 地图分块拼接，有限色板 | 高清纹理平面 | M1 / M3 |
| 海报轮播 | 低频换图 | 高清换图 | M2 / M3 |
| 侧边图片 HUD | 聊天/文字状态提示 | MineUI 图片 HUD | M2 |
| 世界视频 | 默认展示封面；实验低帧率地图动画需另行开启 | 预处理视频、本地解码、同步播放 | M4 |
| 视频 HUD | 文字状态提示 | MineDisplay 自有 HUD 渲染 | M4 |
| 视频声音 | 默认静音；可选预制资源包声音 | 视频客户端同步音轨 | M4 |

“类似计分板”指固定在屏幕边缘的面板，不接管其他插件的 Bukkit scoreboard。
“像原版画”指世界中的图片平面，不修改原版 Painting 注册表。

## 文档

- [总体设计](docs/DESIGN.md)：技术取舍、架构、素材、播放同步、协议、安全、配置与拟定 API。
- [实施计划](docs/PLAN.md)：里程碑、交付物、验证门槛、待确认事项。
- [操作规范](docs/OPERATIONS.md)：拟定命令、素材制作、部署、故障处理与交付方式。

## 项目边界

MineDisplay 管理视觉素材、屏幕及播放状态。MineUI 提供现有的声明式界面/静态图片 HUD；MineAudio 提供现有声音业务 API。
两者均为可选集成。当前 API 不提供通用视频组件或外部视频时钟，相关扩展仅记录需求，不修改其他项目。
PackHost 仅在需要预制资源包时通过现有部署流程使用，不代理视频、不调用其实现类。

## 开发与安装

```bash
./gradlew build        # 构建 + 单测
./deploy.sh            # 部署到 /home/ubuntu/minecraft（默认只部署不重启）
```

1. 把 PNG/JPEG 放到 `plugins/MineDisplay/media/incoming/`，游戏内 `/minedisplay media import <id> <文件>`。
2. `/minedisplay create <id> <宽tiles> <高tiles> <media-id>` 后右键墙面左上角放置。
3. `/minedisplay set <id> <media-id>` 换图（复用自身 map ID）；`/minedisplay glow <id>` 转发光画框。

目标环境沿用相邻项目：Java 25、Paper 26.2（`26.2.build.124-stable`）、Fabric 26.2。
独立仓库：[jurky123/mineDisplay](https://github.com/jurky123/mineDisplay)。
API 按真实调用方需求发布到 Maven Local（M1 暂无公开 API，只有命令）。

## 基座与许可证

地图墙核心移植自 [ImageMaps](https://github.com/SydMontague/ImageMaps)（MIT），
已去掉 CLCore 依赖并适配 26.2（MapRenderer 首帧直画、画布一次缩放多 tile 共享、
Display 模型/持久化/保护/配额按本项目 DESIGN 扩展）。MIT 允许修改与闭源分发，
保留原作者版权声明见 `minedisplay-paper/NOTICE.md`。
