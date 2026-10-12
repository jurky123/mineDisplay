#!/usr/bin/env bash
# 构建 MineDisplay 客户端安装包（只含本插件产物；前置 mod 首次安装时单独提供）
# Rinku 是前置 mod：默认不打包，INCLUDE_PREREQ=1 才附带（仅首次安装用）。
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
PREREQ_LINE="- mods/rinku-$RINKU_VERSION.jar  （本包未附带，需单独安装）"
if [ "${INCLUDE_PREREQ:-0}" = "1" ] && [ -f "$RINKU_JAR" ]; then
    cp "$RINKU_JAR" "$STAGE/mods/"
    PREREQ_LINE="- mods/rinku-$RINKU_VERSION.jar  Chromium 浏览器库（LGPL-2.1，已附带）"
fi

cat > "$STAGE/安装说明.txt" <<EOF
MineDisplay Client 安装说明（26.2 · v$VERSION）
==========================================
包内容：
- mods/minedisplay-client-26.2-$VERSION.jar  MineDisplay 真浏览器屏幕客户端
$PREREQ_LINE

前置（已安装可跳过；本包默认不再附带）：
- Fabric API
- Rinku $RINKU_VERSION（Chromium 浏览器库）

安装/升级：
1) 删除 mods 里旧的 minedisplay-client jar，放入本包里的新 jar
2) 必须重启游戏（Rinku 内核下载与初始化只在启动时进行；
   之前下载失败过的话，重启后才会重试）
3) 首次启动会自动从 https://zkun.art/rinku-jcef 下载 Chromium 内核
   （约 180MB）——本 mod 启动前会自动写好 config/rinku/rinku.properties
   的镜像配置，无需手动改
4) 排障：可删掉 .minecraft/rinku-libraries/.staging 残留后再启动

说明：
- 客户端只接受服务端下发的代理地址（http://zkun.art:8768/display/<令牌>），
  目标站内容由服务端代理取回。
- 未安装 Rinku 时自动降级为地图封面，不影响游戏。
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
