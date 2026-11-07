#!/usr/bin/env bash
#
# verify-jni-symbols.sh — 校验 librime_jni.so 与 hkrime Java/Kotlin 源码的 JNI 契约
#
# 做什么：
#   1. 扫描 hkrime 的 Java / Kotlin 源码，抽出所有 `native` 方法（Java）与
#      `external fun`（Kotlin）声明，按 JNI 名字修饰规则生成期望的导出符号名
#      （如 `Java_com_hazukieq_hkrime_core_Rime_deploy`）。
#   2. 读 librime_jni.so 的动态符号表（nm -D --defined-only），取出所有 Java_* 导出。
#   3. 比对：
#        - 缺失符号（源码声明了但 .so 没导出）      → 报错，退出码 1
#        - 残留旧包名符号（Java_com_osfans_trime_*）→ 报错，退出码 1
#        - 多余的 Java_* 导出（.so 有、源码没声明）  → 仅警告
#
# JNI 名字修饰规则（JNI Spec §2.2 / §2.3）：
#   包名/类名路径：'.' → '/'，'/' → '_'，'_' → '_1'，'$' → '_00024'
#   方法名：'_' → '_1'，'$' → '_00024'，';' → '_2'，'[' → '_3'（本仓库只用到 '_'）
#
# 用法：
#   ./script/verify-jni-symbols.sh                       # 默认校验 app/libs/arm64-v8a/librime_jni.so
#   ./script/verify-jni-symbols.sh --so /path/to/lib.so --abi arm64-v8a
#   ./script/verify-jni-symbols.sh --src app/src/main --legacy-pkg com.osfans.trime
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ABI="arm64-v8a"
SO=""
SRC_ROOT="$REPO_ROOT/app/src/main"
LEGACY_PKG="com.osfans.trime"
NM_BIN="${NM:-nm}"
VERBOSE=0

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "  $*"; }

usage() {
  sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --so)         SO="${2:?--so 需要路径}"; shift 2 ;;
    --src)        SRC_ROOT="${2:?--src 需要目录}"; shift 2 ;;
    --abi)        ABI="${2:?--abi 需要值}"; shift 2 ;;
    --legacy-pkg) LEGACY_PKG="${2:?--legacy-pkg 需要值}"; shift 2 ;;
    --nm)         NM_BIN="${2:?--nm 需要路径}"; shift 2 ;;
    -v|--verbose) VERBOSE=1; shift ;;
    -h|--help)    usage ;;
    *)            die "未知参数: $1（-h 看用法）" ;;
  esac
done

[ -n "$SO" ] || SO="$REPO_ROOT/app/libs/$ABI/librime_jni.so"

echo "== verify-jni-symbols =="
info "repo      : $REPO_ROOT"
info "so        : $SO"
info "src root  : $SRC_ROOT"
info "abi       : $ABI"
info "legacy pkg: $LEGACY_PKG"

[ -f "$SO" ]    || die "找不到 .so: $SO"
[ -d "$SRC_ROOT" ] || die "找不到源码根目录: $SRC_ROOT"
command -v "$NM_BIN" >/dev/null 2>&1 || die "找不到 nm: $NM_BIN（可 --nm /path/to/llvm-nm）"
command -v python3  >/dev/null 2>&1 || die "找不到 python3（解析源码用）"

# 基本合法性：必须是 aarch64 的 ELF 共享库
if command -v file >/dev/null 2>&1; then
  file "$SO" | grep -q 'ELF 64-bit LSB shared object, ARM aarch64' \
    || die "$SO 不是 aarch64 ELF 共享库：$(file -b "$SO")"
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

EXPECTED="$TMP_DIR/expected.syms"
DECLS="$TMP_DIR/decls.txt"
ACTUAL="$TMP_DIR/actual.syms"

# ---------------------------------------------------------------------------
# 1) 从源码抽 native / external fun 声明 → 期望 JNI 符号
# ---------------------------------------------------------------------------
python3 - "$SRC_ROOT" > "$EXPECTED.tmp" <<'PY' || die "源码解析失败"
import os, re, sys

src_root = sys.argv[1]

def strip_comments(text):
    # 去掉 /* ... */ 与 // ...（不处理字符串字面量，JNI 声明场景足够）
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    text = re.sub(r'//[^\n]*', ' ', text)
    return text

JAVA_CONTAINER = re.compile(
    r'^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*\n?[ \t]*)*'
    r'(?:public[ \t]+|final[ \t]+|abstract[ \t]+|strictfp[ \t]+|sealed[ \t]+|non-sealed[ \t]+)*'
    r'(?:class|interface|enum|record)[ \t]+(\w+)', re.M)
KT_CONTAINER = re.compile(
    r'^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*\n?[ \t]*)*'
    r'(?:public[ \t]+|internal[ \t]+|private[ \t]+|protected[ \t]+|'
    r'expect[ \t]+|open[ \t]+|abstract[ \t]+|sealed[ \t]+|data[ \t]+|enum[ \t]+|annotation[ \t]+|value[ \t]+)*'
    r'(?:object|class|interface)[ \t]+(\w+)', re.M)
JAVA_NATIVE = re.compile(r'\bnative\b')
KT_EXTERNAL = re.compile(r'\bexternal\s+fun\s+(?:<[^>]*>\s*)?(\w+)\s*\(')

def esc_ident(s):
    return s.replace('_', '_1').replace('$', '_00024')

def jni_symbol(pkg, cls, method):
    path = (pkg.replace('.', '/') + '/' + cls) if pkg else cls
    path = path.replace('_', '_1').replace('/', '_').replace('$', '_00024')
    return 'Java_' + path + '_' + esc_ident(method)

problems = []
n_files = 0
n_decls = 0
for dirpath, _dirnames, filenames in os.walk(src_root):
    for fn in sorted(filenames):
        if not (fn.endswith('.java') or fn.endswith('.kt')):
            continue
        path = os.path.join(dirpath, fn)
        try:
            raw = open(path, encoding='utf-8', errors='replace').read()
        except OSError as e:
            problems.append(f'{path}: 读取失败 {e}')
            continue
        text = strip_comments(raw)
        pm = re.search(r'^\s*package\s+([\w.]+)\s*;?', text, re.M)
        if not pm:
            continue
        pkg = pm.group(1)
        if fn.endswith('.java'):
            cm = JAVA_CONTAINER.search(text)
            natives = []
            for m in JAVA_NATIVE.finditer(text):
                paren = text.find('(', m.end())
                if paren < 0:
                    continue
                seg = text[m.end():paren]
                if len(seg) > 200 or '{' in seg or '}' in seg or '=' in seg:
                    continue
                ids = re.findall(r'[A-Za-z_$][\w$]*', seg)
                if not ids:
                    continue
                natives.append(ids[-1])
        else:
            cm = KT_CONTAINER.search(text)
            natives = KT_EXTERNAL.findall(text)
        if not natives:
            continue
        if not cm:
            problems.append(f'{path}: 有 native/external 声明但没找到类/对象名，已跳过')
            continue
        cls = cm.group(1)
        n_files += 1
        for meth in natives:
            n_decls += 1
            print(f'{jni_symbol(pkg, cls, meth)}\t{pkg}.{cls}#{meth}')
        if len(natives) > 1 and len(set(natives)) != len(natives):
            problems.append(f'{path}: 同一文件内重复声明 {sorted(natives)}')

for p in problems:
    print('WARN ' + p, file=sys.stderr)
print(f'# parsed_files={n_files} parsed_decls={n_decls}', file=sys.stderr)
if n_decls == 0:
    print('ERROR: 没有从源码中解析到任何 native/external fun 声明', file=sys.stderr)
    sys.exit(3)
PY

grep -v '^#' "$EXPECTED.tmp" | cut -f1 | sort -u > "$EXPECTED"
grep -v '^#' "$EXPECTED.tmp" | cut -f2 | sort -u > "$DECLS"
rm -f "$EXPECTED.tmp"

# ---------------------------------------------------------------------------
# 2) 从 .so 抽实际导出
# ---------------------------------------------------------------------------
"$NM_BIN" -D --defined-only "$SO" | awk '{print $NF}' | grep '^Java_' | sort -u > "$ACTUAL" || true

LEGACY_PREFIX="Java_$(echo "$LEGACY_PKG" | tr '.' '_')_"
grep -F "$LEGACY_PREFIX" "$ACTUAL" > "$TMP_DIR/legacy.syms" || true
grep -Fxv -f "$ACTUAL" "$EXPECTED" > "$TMP_DIR/missing.syms" || true
grep -Fxv -f "$EXPECTED" "$ACTUAL" > "$TMP_DIR/extra.syms" || true

EXPECTED_N=$(wc -l < "$EXPECTED" | tr -d ' ')
ACTUAL_N=$(wc -l < "$ACTUAL" | tr -d ' ')
MISSING_N=$(wc -l < "$TMP_DIR/missing.syms" | tr -d ' ')
LEGACY_N=$(wc -l < "$TMP_DIR/legacy.syms" | tr -d ' ')
EXTRA_N=$(wc -l < "$TMP_DIR/extra.syms" | tr -d ' ')

echo
echo "== 统计 =="
info "源码声明（去重）    : $EXPECTED_N"
info ".so 导出 Java_*     : $ACTUAL_N"
info "缺失                : $MISSING_N"
info "残留旧包名          : $LEGACY_N  (prefix: $LEGACY_PREFIX)"
info "多余（源码未声明）  : $EXTRA_N"

if [ "$VERBOSE" = 1 ]; then
  echo
  echo "== 期望符号清单 =="
  sed 's/^/  /' "$EXPECTED"
fi

rc=0

if [ "$MISSING_N" -gt 0 ]; then
  echo
  echo "!! 缺失符号（源码声明了，但 .so 没有导出）:" >&2
  sed 's/^/  MISSING /' "$TMP_DIR/missing.syms" >&2
  rc=1
fi

if [ "$LEGACY_N" -gt 0 ]; then
  echo
  echo "!! 残留旧包名符号（.so 仍是旧包名，APK 里会 UnsatisfiedLinkError）:" >&2
  sed 's/^/  LEGACY  /' "$TMP_DIR/legacy.syms" >&2
  rc=1
fi

if [ "$EXTRA_N" -gt 0 ]; then
  echo
  echo "-- 警告：.so 导出了源码中不存在的 Java_* 符号（可能是旧代码遗留，通常无害）:" >&2
  sed 's/^/  EXTRA   /' "$TMP_DIR/extra.syms" | head -40 >&2
  [ "$EXTRA_N" -gt 40 ] && echo "  ... 共 $EXTRA_N 个" >&2
fi

echo
if [ "$rc" -eq 0 ]; then
  echo "OK: JNI 符号契约一致（$EXPECTED_N 个声明全部导出，无旧包名残留）"
else
  echo "FAILED: JNI 符号契约不一致（缺失 $MISSING_N，旧包名 $LEGACY_N）" >&2
fi
exit "$rc"
