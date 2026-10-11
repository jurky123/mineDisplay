#!/usr/bin/env bash
# 拉取 Rinku（Chromium 浏览器库，LGPL-2.1）到 tools/cache，不进仓库。
# 用法: tools/fetch_rinku.sh
# 来源：Modrinth 官方发布（Keksuccino），sha512 校验。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/tools/cache"
VERSION="$(grep -E '^rinku_version=' "$ROOT/gradle.properties" | cut -d= -f2)"
JAR="$CACHE/rinku-$VERSION.jar"

# 3.0.4-26.2-fabric（2026-08-27 发布，809100 字节）
URL="https://cdn.modrinth.com/data/bQhBuv7x/versions/LtluKaTp/rinku_fabric_3.0.4_MC_26.2.jar"
SHA512="909ee38b454fdfc8"

mkdir -p "$CACHE"
if [ -f "$JAR" ]; then
    echo "已存在: $JAR"
    exit 0
fi

echo "下载 Rinku $VERSION ..."
curl -fsSL -o "$JAR" "$URL"

ACTUAL="$(sha512sum "$JAR" | cut -d' ' -f1 | cut -c1-16)"
if [ "$ACTUAL" != "$SHA512" ]; then
    echo "sha512 校验失败（期望 $SHA512 开头，实际 $ACTUAL 开头），已删除" >&2
    rm -f "$JAR"
    exit 1
fi
echo "校验通过: $JAR ($(stat -c%s "$JAR") 字节)"
