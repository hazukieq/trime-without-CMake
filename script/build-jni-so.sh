#!/usr/bin/env bash
#
# build-jni-so.sh — 从上游 osfans/trime 源码构建 arm64-v8a 的 librime_jni.so，
#                   并把 JNI 类路径 / 导出符号从 com.osfans.trime 全量改造成 --pkg 指定的包名
#                   （本项目：com.hazukieq.hkrime）。
#
# 背景：
#   hkrime 的 Java/Kotlin 侧（Rime.java / OpenCCDictManager.kt）是上游 trime v3.2.7 时代的
#   JNI API（snake_case native 方法）。v3.3.x 起上游改成 Kotlin RimeApi，符号/类路径全变，
#   所以必须钉在 v3.2.7 这个 ref 上构建，再把包名改掉。
#
# 本脚本做的事（顺序固定）：
#   1. 探测 SDK / NDK / cmake / ninja（--ndk 优先；否则 local.properties 的 sdk.dir →
#      ANDROID_HOME → ANDROID_SDK_ROOT → /home/hazukie/Android/Sdk）。
#   2. 取源码：git clone 上游（--upstream-ref，默认 v3.2.7）+ git submodule update --recursive，
#      然后逐个校验子模块 commit == 上游 gitlink（不匹配直接失败）。
#      也可以用 --src-dir 复用一份已准备好的源码树（离线/CI 场景）。
#   3. 打 patch：把 librime_jni/{rime_jni.cc,key_table.cc,levers.cc,opencc.cc,jni-utils.h} 与
#      librime_jni/CMakeLists.txt 里的 com.osfans.trime / Java_com_osfans_trime_ 改成新包名。
#      pattern 找不到就报错退出（绝不静默跳过）；已打过则跳过（幂等）。
#   4. cmake（NDK toolchain，绕开 gradle）+ ninja 构建目标 rime_jni。
#   5. llvm-strip --strip-unneeded。
#   6. 安装到 app/libs/<abi>/librime_jni.so（或 --out-dir），写 .sha256。
#   7. 调 script/verify-jni-symbols.sh 校验 73 个 JNI 符号与 hkrime 源码声明一致、无旧包名残留。
#
# 用法：
#   ./script/build-jni-so.sh                       # 全默认：clone v3.2.7 到 ../jni-build，出 arm64-v8a
#   ./script/build-jni-so.sh --clean               # 先清掉源码树与构建目录再从头来（幂等）
#   ./script/build-jni-so.sh --build-type Release  # 出 -O2 优化版（约 6MB；默认 Debug 与现有 13.5MB 库一致）
#   ./script/build-jni-so.sh --src-dir /path/to/trime-v3.2.7   # 复用已 clone 好的源码（不联网）
#   ./script/build-jni-so.sh --out-dir /tmp/out    # 只装到临时目录（不动 app/libs）
#
# 默认值：
#   upstream-repo   https://github.com/osfans/trime.git
#   upstream-ref    v3.2.7
#   pkg             com.hazukieq.hkrime    （旧包名固定为 com.osfans.trime）
#   abi             arm64-v8a
#   build-type      Debug（= 上游 gradle debug variant / 仓库现有 13.5MB 库的同构产物）
#   android-platform android-21
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------------------------------------------------------------------------
# 默认参数
# ---------------------------------------------------------------------------
UPSTREAM_REPO="https://github.com/osfans/trime.git"
UPSTREAM_REF="v3.2.7"
NEW_PKG="com.hazukieq.hkrime"
OLD_PKG="com.osfans.trime"
ABI="arm64-v8a"
ANDROID_PLATFORM="android-21"
BUILD_TYPE="Debug"
JOBS="$( (nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4) )"
NDK_DIR=""
SDK_DIR=""
SRC_DIR=""
WORK_DIR="$REPO_ROOT/../jni-build"
BUILD_DIR_NAME=""
OUT_DIR=""
CLEAN=0
EXPECT_SYMBOLS=73     # v3.2.7 的 librime_jni 源文件里恰好 73 个 Java_* 入口；0 = 不检查
LOG_FILE=""

die()  { echo "ERROR: $*" >&2; exit 1; }
info() { echo "  $*"; }
step() { echo; echo "==> $*"; }

usage() {
  sed -n '2,45p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

# safe_rm：优先 trash（用户的 shell 约定：删除=进回收站），没有 trash 才退化成 rm -rf
safe_rm() {
  local p="$1"
  [ -e "$p" ] || return 0
  if command -v trash >/dev/null 2>&1; then
    trash "$p" >/dev/null 2>&1 || trash-put "$p" >/dev/null 2>&1 || rm -rf "$p"
  else
    rm -rf "$p"
  fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --upstream-repo)   UPSTREAM_REPO="${2:?}"; shift 2 ;;
    --upstream-ref)    UPSTREAM_REF="${2:?}"; shift 2 ;;
    --pkg)             NEW_PKG="${2:?}"; shift 2 ;;
    --old-pkg)         OLD_PKG="${2:?}"; shift 2 ;;
    --abi)             ABI="${2:?}"; shift 2 ;;
    --ndk)             NDK_DIR="${2:?}"; shift 2 ;;
    --build-type)      BUILD_TYPE="${2:?}"; shift 2 ;;
    --android-platform) ANDROID_PLATFORM="${2:?}"; shift 2 ;;
    --jobs|-j)         JOBS="${2:?}"; shift 2 ;;
    --src-dir)         SRC_DIR="${2:?}"; shift 2 ;;
    --work-dir)        WORK_DIR="${2:?}"; shift 2 ;;
    --build-dir)       BUILD_DIR_NAME="${2:?}"; shift 2 ;;
    --out-dir)         OUT_DIR="${2:?}"; shift 2 ;;
    --expect-symbols)  EXPECT_SYMBOLS="${2:?}"; shift 2 ;;
    --log)             LOG_FILE="${2:?}"; shift 2 ;;
    --clean)           CLEAN=1; shift ;;
    -h|--help)         usage ;;
    *)                 die "未知参数: $1（-h 看用法）" ;;
  esac
done

mkdir -p "$WORK_DIR"
[ -n "$LOG_FILE" ] || LOG_FILE="$WORK_DIR/build-jni.log"
LOG_FILE="$(cd "$(dirname "$LOG_FILE")" && pwd)/$(basename "$LOG_FILE")"
# 全部输出同时进日志文件（构建日志可作为交付证据）
exec > >(tee -a "$LOG_FILE") 2>&1

echo "== build-jni-so =="
info "repo root     : $REPO_ROOT"
info "upstream      : $UPSTREAM_REPO @ $UPSTREAM_REF"
info "package       : $OLD_PKG -> $NEW_PKG"
info "abi           : $ABI   platform: $ANDROID_PLATFORM   build-type: $BUILD_TYPE"
info "jobs          : $JOBS"
info "work dir      : $WORK_DIR"
info "log           : $LOG_FILE"

# ---------------------------------------------------------------------------
# 1) 探测 SDK / NDK / cmake / ninja
# ---------------------------------------------------------------------------
step "探测 SDK / NDK / cmake / ninja"

if [ -z "$SDK_DIR" ]; then
  if [ -f "$REPO_ROOT/local.properties" ]; then
    SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | head -1 | tr -d '\r')"
  fi
  [ -n "$SDK_DIR" ] || SDK_DIR="${ANDROID_HOME:-}"
  [ -n "$SDK_DIR" ] || SDK_DIR="${ANDROID_SDK_ROOT:-}"
  [ -n "$SDK_DIR" ] || { [ -d /home/hazukie/Android/Sdk ] && SDK_DIR=/home/hazukie/Android/Sdk; }
fi
[ -n "$SDK_DIR" ] && [ -d "$SDK_DIR" ] || die "找不到 Android SDK：用 --ndk 指定 NDK，或把 SDK 路径写到 $REPO_ROOT/local.properties 的 sdk.dir"
info "SDK           : $SDK_DIR"

if [ -z "$NDK_DIR" ]; then
  if [ -d "$SDK_DIR/ndk/24.0.8215888" ]; then
    NDK_DIR="$SDK_DIR/ndk/24.0.8215888"
  else
    NDK_DIR="$(ls -d "$SDK_DIR"/ndk/*/ 2>/dev/null | sort -V | tail -1 | sed 's:/*$::')"
  fi
fi
[ -n "$NDK_DIR" ] && [ -d "$NDK_DIR" ] || die "找不到 NDK（SDK 下没有 ndk/），用 --ndk 指定"
[ -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ] || die "$NDK_DIR 不像 NDK（缺 build/cmake/android.toolchain.cmake）"
info "NDK           : $NDK_DIR (Pkg.Revision=$(sed -n 's/^Pkg.Revision = //p' "$NDK_DIR/source.properties" 2>/dev/null || echo '?'))"

STRIP_BIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$STRIP_BIN" ] || die "找不到 llvm-strip: $STRIP_BIN"

# cmake：优先 SDK 自带（host cmake 3.28.3 在 Debian/Ubuntu 上缺 Platform/Android 模块，不能用于 Android）
CMAKE_BIN=""
if [ -d "$SDK_DIR/cmake" ]; then
  for cand in "$SDK_DIR/cmake/3.22.1/bin/cmake" \
              "$(ls -d "$SDK_DIR"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1 | sed 's:/*$::')"; do
    [ -n "$cand" ] && [ -x "$cand" ] && { CMAKE_BIN="$cand"; break; }
  done
fi
[ -n "$CMAKE_BIN" ] || CMAKE_BIN="$(command -v cmake || true)"
[ -n "$CMAKE_BIN" ] || die "找不到 cmake（SDK 的 cmake/ 与系统 cmake 都没有）"
info "cmake         : $CMAKE_BIN ($("$CMAKE_BIN" --version | head -1))"

NINJA_BIN=""
for cand in "$SDK_DIR/cmake/3.22.1/bin/ninja" "$(dirname "$CMAKE_BIN")/ninja" "$(command -v ninja || true)"; do
  [ -n "$cand" ] && [ -x "$cand" ] && { NINJA_BIN="$cand"; break; }
done
[ -n "$NINJA_BIN" ] || die "找不到 ninja（SDK cmake 目录或系统 PATH 都没有；安装：apt install ninja-build）"
info "ninja         : $NINJA_BIN ($("$NINJA_BIN" --version))"

[ -f "$SCRIPT_DIR/verify-jni-symbols.sh" ] || die "缺 $SCRIPT_DIR/verify-jni-symbols.sh"

# ---------------------------------------------------------------------------
# 2) 取源码 + 校验子模块 commit
# ---------------------------------------------------------------------------
step "取源码（$UPSTREAM_REPO @ $UPSTREAM_REF）"

if [ -n "$SRC_DIR" ]; then
  SRC="$(cd "$SRC_DIR" && pwd)"
  info "复用 --src-dir: $SRC"
  [ -d "$SRC/app/src/main/jni" ] || die "$SRC 不是 trime 源码树（缺 app/src/main/jni）"
  [ -d "$SRC/.git" ] || die "$SRC 不是 git 仓库"
else
  SRC="$WORK_DIR/trime-$UPSTREAM_REF"
  if [ "$CLEAN" = 1 ]; then
    info "清理源码树: $SRC"
    safe_rm "$SRC"
  fi
  if [ -d "$SRC/.git" ]; then
    info "复用已 clone 的源码树: $SRC"
    git -C "$SRC" fetch --tags --force origin
  else
    info "git clone $UPSTREAM_REPO -> $SRC"
    git clone --branch "$UPSTREAM_REF" --single-branch "$UPSTREAM_REPO" "$SRC"
  fi
  git -C "$SRC" checkout --force "$UPSTREAM_REF"
  git -C "$SRC" reset --hard "$UPSTREAM_REF"
  info "HEAD: $(git -C "$SRC" rev-parse HEAD) ($(git -C "$SRC" describe --tags 2>/dev/null || echo '?'))"
  info "git submodule update --init --recursive -j $JOBS"
  git -C "$SRC" submodule sync --recursive
  git -C "$SRC" submodule update --init --recursive --force -j "$JOBS"
fi

# 子模块 pin 校验：上游 gitlink（index 里记的 SHA）必须等于子模块实际 HEAD
# 注意 boost / capnproto 在“离线复用树”里可能是普通源码目录（不是 submodule），
# 这种情况只警告（构建偏离项会写进报告），其余不匹配一律失败。
JNI_REL="app/src/main/jni"
PINS="
OpenCC 2ddaffd31f6f9cae776ec2faa4b88d5c45e0d4d6
boost 5df8086b733798c8e08e316626a16babe11bd0d2
capnproto 5cff5efc2bbb19b2512b940f79c8c696e0737424
libiconv 51aaded80d1986be940d9e73f7927606e316c21e
librime 43023e16d840e9cf5b11d66b1c3e426875b2dcfe
librime-charcode b569184772b12965e3ebe1dfd431026951fed81c
librime-lua fff26227fc126b2e266454f932e69936ec6664d7
librime-lua-deps b59a6b0536e0608aa2667ba02f3ec4434cdeeb93
librime-octagram 7302048f12c065e0971aed3b0af46a10ec3c4cf2
snappy 2b63814b15a2aaae54b7943f0cd935892fae628f
"
NESTED_PINS="
librime/deps/yaml-cpp 0579ae3d976091d7d664aa9d2527e0d0cff25763
librime/deps/leveldb 99b3c03b3284f5886f9ef9a4ef703d57373e61be
"

step "校验子模块 commit（对上上游 v3.2.7 的 gitlink）"
PIN_FAIL=0
check_pin() {
  local rel="$1" expect="$2" path pin actual
  path="$SRC/$JNI_REL/$rel"
  pin="$(git -C "$SRC" rev-parse "HEAD:$JNI_REL/$rel" 2>/dev/null || true)"
  if [ -z "$pin" ]; then
    echo "  FAIL  $rel: 上游 index 里没有这个 gitlink（ref 不对？）"
    PIN_FAIL=1; return
  fi
  if [ "$pin" != "$expect" ]; then
    echo "  FAIL  $rel: 上游 pin=$pin，与脚本登记值 $expect 不一致（上游 ref 变了？）"
    PIN_FAIL=1; return
  fi
  # 只有真·submodule 才有 .git（gitfile 或目录）；普通目录里 rev-parse 会爬到父仓库，不能用来判断
  if [ -e "$path/.git" ]; then
    actual="$(git -C "$path" rev-parse HEAD)"
    if [ "$actual" = "$expect" ]; then
      [ "$rel" = "boost" ] || [ "$rel" = "capnproto" ] || printf '  ok    %-20s %s\n' "$rel" "$actual"
      [ "$rel" = "boost" ] && printf '  ok    %-20s %s（submodule）\n' "$rel" "$actual"
      [ "$rel" = "capnproto" ] && printf '  ok    %-20s %s（submodule）\n' "$rel" "$actual"
    else
      echo "  FAIL  $rel: 工作树 HEAD=$actual != pin=$expect"
      PIN_FAIL=1
    fi
  else
    case "$rel" in
      boost|capnproto)
        echo "  WARN  $rel: 未初始化为 git submodule（普通源码目录），跳过 pin 校验（偏离项，需在报告里注明）" ;;
      *)
        echo "  FAIL  $rel: 目录为空/未初始化（git submodule update 没跑成功）"
        PIN_FAIL=1 ;;
    esac
  fi
}
PIN_LOG="$(mktemp)"
{
  echo "$PINS" | while read -r rel sha; do
    [ -n "${rel:-}" ] || continue
    check_pin "$rel" "$sha" || true
  done
} > "$PIN_LOG" 2>&1
cat "$PIN_LOG"
if grep -q '^  FAIL' "$PIN_LOG"; then
  rm -f "$PIN_LOG"
  die "子模块 commit 校验失败（见上）"
fi
rm -f "$PIN_LOG"

# 嵌套子模块（librime/deps 下的 yaml-cpp / leveldb，构建必需）
echo "$NESTED_PINS" | while read -r rel sha; do
  [ -n "${rel:-}" ] || continue
  path="$SRC/$JNI_REL/$rel"
  pin="$(git -C "$SRC/$JNI_REL/librime" rev-parse "HEAD:deps/${rel#librime/deps/}" 2>/dev/null || true)"
  if [ -e "$path/.git" ]; then actual="$(git -C "$path" rev-parse HEAD 2>/dev/null || true)"; else actual=""; fi
  if [ -n "$pin" ] && [ "$pin" = "$sha" ] && [ "$actual" = "$sha" ]; then
    printf '  ok    %-20s %s（嵌套）\n' "$rel" "$actual"
  else
    printf '  FAIL  %-20s pin=%s expect=%s actual=%s\n' "$rel" "${pin:-none}" "$sha" "${actual:-none}"
    die "嵌套子模块校验失败: $rel（cmake 会找不到 librime/deps/${rel#librime/deps/}/CMakeLists.txt）"
  fi
done || exit 1

# ---------------------------------------------------------------------------
# 3) 打 patch（包名 com.osfans.trime -> $NEW_PKG）
# ---------------------------------------------------------------------------
step "打 patch：$OLD_PKG -> $NEW_PKG"
OLD_PKG_PATH="${OLD_PKG//./\/}"        # com/osfans/trime
NEW_PKG_PATH="${NEW_PKG//./\/}"        # com/hazukieq/hkrime
OLD_UNDER="${OLD_PKG//./_}"            # com_osfans_trime
NEW_UNDER="${NEW_PKG//./_}"            # com_hazukieq_hkrime

JNI_DIR="$SRC/$JNI_REL/librime_jni"
[ -d "$JNI_DIR" ] || die "缺 $JNI_DIR"

patched_any=0
patch_file() {
  local f="$1" old="$2" new="$3" label="$4"
  [ -f "$f" ] || die "patch 目标不存在: $f"
  if grep -qF -- "$old" "$f"; then
    sed -i "s|$old|$new|g" "$f"
    echo "  patched  ${label}: '$old' -> '$new'"
    patched_any=1
  elif grep -qF -- "$new" "$f"; then
    echo "  already  ${label}: 已是 '$new'"
  else
    die "pattern 不存在，拒绝静默跳过: '$old' 不在 ${f#$SRC/}"
  fi
}

for f in rime_jni.cc key_table.cc levers.cc opencc.cc; do
  patch_file "$JNI_DIR/$f" "Java_${OLD_UNDER}_" "Java_${NEW_UNDER}_" "$f"
done
patch_file "$JNI_DIR/jni-utils.h"       "$OLD_PKG_PATH"  "$NEW_PKG_PATH"  "jni-utils.h (FindClass/GetFieldID)"
patch_file "$JNI_DIR/rime_jni.cc"       "$OLD_PKG"       "$NEW_PKG"       "rime_jni.cc (traits.app_name)"
patch_file "$JNI_DIR/CMakeLists.txt"    "$OLD_PKG_PATH/core/Rime" "$NEW_PKG_PATH/core/Rime" "CMakeLists.txt (CLASSNAME)"

# 兜底：不允许任何旧包名残留
LEFTOVER="$(grep -rnE "(com[./]osfans[./]trime|Java_${OLD_UNDER}_)" "$JNI_DIR" 2>/dev/null || true)"
[ -z "$LEFTOVER" ] || { echo "$LEFTOVER"; die "patch 后仍有旧包名残留（见上）"; }

if [ "$EXPECT_SYMBOLS" -gt 0 ] 2>/dev/null; then
  n=$(cat "$JNI_DIR"/*.cc | grep -o "Java_${NEW_UNDER}_" | wc -l | tr -d ' ')
  info "源码里的 JNI 入口数: $n（期望 $EXPECT_SYMBOLS）"
  [ "$n" -eq "$EXPECT_SYMBOLS" ] || die "JNI 入口数 $n != 期望 $EXPECT_SYMBOLS（ref 变了？用 --expect-symbols 0 关闭此检查）"
fi
[ "$patched_any" = 1 ] && info "patch 已应用" || info "patch 早已应用（幂等跳过）"

# ---------------------------------------------------------------------------
# 4) cmake + ninja
# ---------------------------------------------------------------------------
[ -n "$BUILD_DIR_NAME" ] || BUILD_DIR_NAME="build-$ABI-$BUILD_TYPE"
BUILD_DIR="$SRC/$BUILD_DIR_NAME"
if [ "$CLEAN" = 1 ]; then
  info "清理构建目录: $BUILD_DIR"
  safe_rm "$BUILD_DIR"
fi

step "cmake configure（NDK toolchain，绕过 gradle）"
info "build dir: $BUILD_DIR"
# -UBOOST_EXCLUDE_LIBRARIES 是必须的坑规避：
#   app/src/main/jni/cmake/FindBoost.cmake 第 4 行做
#     set(BOOST_EXCLUDE_LIBRARIES "context;coroutine;fiber" CACHE STRING "" FORCE)
#   而 boost 的 BoostRoot.cmake 在 add_subdirectory(boost) 时读这个变量决定是否为
#   coroutine/context/fiber 建 Boost::* target。第一次 configure 时缓存还是空的，boost_log
#   链接 Boost::coroutine 正常；从第二次起缓存里已有排除列表 → 这三个 target 不存在 →
#   Generate 阶段报 "Target boost_log links to target Boost::coroutine but the target was not found"。
#   每次 configure 前把这个缓存项删掉，就与首次 configure 完全一致（可重复）。
"$CMAKE_BIN" -S "$SRC/app/src/main/jni" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
  -UBOOST_EXCLUDE_LIBRARIES

step "构建 rime_jni（ninja -j $JOBS）"
"$NINJA_BIN" -C "$BUILD_DIR" -j "$JOBS" rime_jni

SO_SRC="$BUILD_DIR/librime_jni/librime_jni.so"
[ -f "$SO_SRC" ] || SO_SRC="$(find "$BUILD_DIR" -name librime_jni.so | head -1)"
[ -n "${SO_SRC:-}" ] && [ -f "$SO_SRC" ] || die "构建完成但找不到 librime_jni.so"
info "built: $SO_SRC ($(stat -c%s "$SO_SRC") B, 未 strip)"

# ---------------------------------------------------------------------------
# 5) strip + 安装
# ---------------------------------------------------------------------------
step "strip + 安装"
[ -n "$OUT_DIR" ] || OUT_DIR="$REPO_ROOT/app/libs/$ABI"
mkdir -p "$OUT_DIR"
OUT_SO="$OUT_DIR/librime_jni.so"
cp -f "$SO_SRC" "$OUT_SO"
"$STRIP_BIN" --strip-unneeded "$OUT_SO"
( cd "$OUT_DIR" && sha256sum "$(basename "$OUT_SO")" > "$(basename "$OUT_SO").sha256" )
info "installed: $OUT_SO"

# STL 自包含检查：NEEDED 里出现 libc++_shared.so 时必须一并交付，否则 APK dlopen 失败
if readelf -d "$OUT_SO" 2>/dev/null | grep -q 'libc++_shared.so'; then
  echo "  WARN  NEEDED 含 libc++_shared.so（ANDROID_STL 不是 c++_static）"
  LIBCXX="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
  if [ -f "$LIBCXX" ]; then
    cp -f "$LIBCXX" "$OUT_DIR/"
    echo "  已一并拷贝: $OUT_DIR/libc++_shared.so（APK 打包时必须包含）"
  else
    die "找不到 $LIBCXX，且产物依赖 libc++_shared.so —— APK 会 dlopen 失败"
  fi
else
  info "STL: 自包含（NEEDED 无 libc++_shared.so，c++_static）"
fi

# ---------------------------------------------------------------------------
# 6) 校验符号契约
# ---------------------------------------------------------------------------
step "校验 JNI 符号（script/verify-jni-symbols.sh）"
"$SCRIPT_DIR/verify-jni-symbols.sh" --so "$OUT_SO" --src "$REPO_ROOT/app/src/main" --abi "$ABI" --legacy-pkg "$OLD_PKG"

# ---------------------------------------------------------------------------
# 7) 汇总
# ---------------------------------------------------------------------------
step "完成"
info "artifact : $OUT_SO"
info "size     : $(stat -c%s "$OUT_SO") B ($(du -h "$OUT_SO" | cut -f1))"
info "sha256   : $(cut -d' ' -f1 "$OUT_SO.sha256")"
info "build    : ref=$UPSTREAM_REF  type=$BUILD_TYPE  abi=$ABI  ndk=$(basename "$NDK_DIR")"
info "log      : $LOG_FILE"
# 最后一步才把完整日志（含校验与汇总）拷进产物目录（这条 cp 自身不在日志里）
cp -f "$LOG_FILE" "$OUT_DIR/build-jni.log" 2>/dev/null || true
