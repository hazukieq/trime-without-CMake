#!/usr/bin/env bash
#
# gen-keystore.sh — 生成本 fork 专用的 release 签名密钥（不写入仓库）。
#
# 产物（默认目录 ~/.android-keys，权限 700）：
#   hkrime-release.jks           签名密钥（2048-bit RSA，有效期 10000 天）
#   hkrime-release.properties    gradle 读取的配置（storeFile/storePassword/keyAlias/keyPassword）
#
# app/build.gradle 的查找顺序：
#   1) 仓库根 release.properties（gitignore）
#   2) ~/.android-keys/hkrime-release.properties  ← 本脚本产物
#   3) 环境变量 HKREME_STORE_FILE / HKREME_STORE_PASSWORD / HKREME_KEY_ALIAS / HKREME_KEY_PASSWORD
#
# 注意：密钥一旦丢失，已发布版本的升级包将无法覆盖安装（签名不一致），请自行备份。
#
# 用法：
#   ./script/gen-keystore.sh                     # 生成到 ~/.android-keys（已存在则拒绝覆盖）
#   ./script/gen-keystore.sh --force             # 覆盖已有密钥（危险：会让已发布版本无法升级）
#   ./script/gen-keystore.sh --alias hkrime --dir ~/.android-keys
#
set -euo pipefail

ALIAS="hkrime"
KEY_DIR="$HOME/.android-keys"
FORCE=0
DNAME="CN=hkrime, OU=hazukieq, O=hkrime-fork, L=Guiping, ST=Guangxi, C=CN"

while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=1; shift ;;
    --alias) ALIAS="${2:?}"; shift 2 ;;
    --dir)   KEY_DIR="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

KEYSTORE="$KEY_DIR/$ALIAS-release.jks"
PROPS="$KEY_DIR/$ALIAS-release.properties"

if [ -f "$KEYSTORE" ] && [ "$FORCE" != 1 ]; then
  echo "已存在: $KEYSTORE（覆盖会破坏已发布版本的升级路径）" >&2
  echo "确需重新生成请加 --force" >&2
  exit 1
fi

KEYTOOL="$(command -v keytool || true)"
if [ -z "$KEYTOOL" ]; then
  for cand in "$HOME/opt/jdk17"/*/bin/keytool "$HOME"/.jdks/*/bin/keytool; do
    [ -x "$cand" ] && { KEYTOOL="$cand"; break; }
  done
fi
[ -n "$KEYTOOL" ] || { echo "找不到 keytool（装 JDK 17 或把 keytool 放进 PATH）" >&2; exit 1; }

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"

PW="$(head -c 64 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"

"$KEYTOOL" -genkeypair \
  -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PW" -keypass "$PW" -dname "$DNAME"

cat > "$PROPS" <<PROPS_EOF
storeFile=$KEYSTORE
storePassword=$PW
keyAlias=$ALIAS
keyPassword=$PW
PROPS_EOF

chmod 600 "$KEYSTORE" "$PROPS"
echo
echo "已生成："
echo "  keystore : $KEYSTORE"
echo "  props    : $PROPS"
echo
echo "密码已随机生成并写入 props（请自行备份 $KEY_DIR）。"
echo "gradle 会按顺序自动读取；不需要往仓库里放任何密钥文件。"
