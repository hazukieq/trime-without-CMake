#!/usr/bin/env bash
# 在仓库根目录起一个静态服务器，供 theme-previewer 读取 app/src/main/assets/rime/*.yaml
# 用法：./tools/theme-previewer/serve.sh [端口]
set -euo pipefail

PORT="${1:-8765}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

echo "仓库根目录: $ROOT"
echo "预览地址:   http://127.0.0.1:${PORT}/tools/theme-previewer/"
echo "Ctrl+C 停止"

cd "$ROOT"
exec python3 -m http.server "$PORT" --bind 127.0.0.1
