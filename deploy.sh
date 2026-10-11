#!/usr/bin/env bash
# 构建并部署 MineDisplay 到 Paper 服务器（默认只部署，不重启）。
# 用法: ./deploy.sh [服务器根目录]   （默认 /home/ubuntu/minecraft）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SERVER="${1:-/home/ubuntu/minecraft}"

if [ ! -d "$SERVER/plugins" ]; then
    echo "找不到服务器目录: $SERVER（应包含 plugins/）" >&2
    exit 1
fi

echo "==> 构建 MineDisplay"
(cd "$ROOT" && ./gradlew build -q)

JAR=$(ls -t "$ROOT"/minedisplay-paper/build/libs/MineDisplay-*.jar | head -n1)
rm -f "$SERVER/plugins/"MineDisplay-*.jar
cp "$JAR" "$SERVER/plugins/"

echo
echo "部署完成："
echo "  $SERVER/plugins/$(basename "$JAR")"
echo
echo "下一步："
echo "  1. 重启服务器（维护窗口内，部署前检查在线玩家与正在进行的游戏）"
echo "  2. 把 PNG/JPEG 放到 plugins/MineDisplay/media/incoming/，游戏内 /minedisplay media import"
echo "  3. /minedisplay status 检查运行状态"
