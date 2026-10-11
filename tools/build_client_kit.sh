#!/usr/bin/env bash
# 构建 MineDisplay 客户端安装包（只含本插件产物，前置 mod 由玩家自行安装）
# 产物命名带版本号：tools/out/minedisplay-client-kit-26.2-<版本>.zip
# 用法: tools/build_client_kit.sh [输出目录]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/tools/out}"
VERSION="$(grep -E '^minedisplay_version=' "$ROOT/gradle.properties" | cut -d= -f2)"
RINKU_VERSION="$(grep -E '^rinku_version=' "$ROOT/gradle.properties" | cut -d= -f2)"
MOD_JAR="$ROOT/minedisplay-client/build/libs/minedisplay-client-26.2-$VERSION.jar"
RINKU_JAR="$ROOT/tools/cache/rinku-$RINKU_VERSION.jar"

if [ ! -f "$MOD_JAR" ]; then
    echo "缺少客户端 jar，先构建 ..."
    (cd "$ROOT" && ./gradlew :minedisplay-client:build -q)
fi

mkdir -p "$OUT_DIR"
STAGE="$(mktemp -d /tmp/opencode/minedisplay-kit.XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/mods"
cp "$MOD_JAR" "$STAGE/mods/"
if [ -f "$RINKU_JAR" ]; then
    cp "$RINKU_JAR" "$STAGE/mods/"
fi

cat > "$STAGE/安装说明.txt" <<EOF
MineDisplay Client 安装说明（26.2 · v$VERSION）
==========================================
包内容：
- mods/minedisplay-client-26.2-$VERSION.jar  MineDisplay 真浏览器屏幕客户端
- mods/rinku-$RINKU_VERSION.jar  Chromium 浏览器库（LGPL-2.1，Keksuccino 发布）

前置（已安装可跳过，本包不再附带）：
- Fabric API

安装：
1) 把 mods/ 里的 jar 放入 .minecraft/mods
2) 首次启动会自动下载 Chromium 内核（约 100-200MB，需联网）
3) 启动 Fabric 26.2 进服；服务端 browser 开屏后世界里即显示网页

说明：
- 未安装 Rinku 时自动降级为地图封面，不影响游戏。
- 本客户端只渲染服务端下发的回环代理地址，永不直连外网。
EOF

KIT_ZIP="$OUT_DIR/minedisplay-client-kit-26.2-$VERSION.zip"
rm -f "$KIT_ZIP"
python3 - "$STAGE" "$KIT_ZIP" <<'PY'
import os, sys, zipfile

stage, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zf:
    for base, _dirs, files in os.walk(stage):
        for name in sorted(files):
            full = os.path.join(base, name)
            zf.write(full, os.path.relpath(full, stage))
PY
echo "安装包: $KIT_ZIP"
unzip -l "$KIT_ZIP"
