# BUILD-REPORT — librime_jni.so（arm64-v8a，com.hazukieq.hkrime）

上游：`https://github.com/osfans/trime.git` @ tag **v3.2.7**
（annotated tag 对象 `97b7adb9177569f2bcef302564231de27fae244b` → commit `8f128151969d088cbbdc89cb7dd2972e4747888e`，2022-07-02）
产物：`artifacts/librime_jni.so`（JNI 类路径 / 导出符号已从 `com.osfans.trime` 全量改为 `com.hazukieq.hkrime`）

构建日期：2026-11-08 ｜ 构建机：20 核 / 31 GB / Linux，NDK r24（24.0.8215888），cmake 3.22.1，ninja 1.10.2

---

## 0. TL;DR（产物指纹）

| 项 | 值 |
|---|---|
| 文件 | `artifacts/librime_jni.so` |
| 大小 | **13,587,824 B**（13.59 MB，stripped） |
| sha256 | **`d6b295b6f795cc14ba888c8d9e9b1a69a48ee20d9f772555e89e3db65ca098ec`** |
| BuildID | `707f3101bdb3d87edf387ded0be2ffb43c50c67d` |
| 格式 | `ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, stripped` |
| NEEDED | `liblog.so libz.so libm.so libdl.so libc.so`（**无 libc++_shared.so** → c++_static 自包含） |
| SONAME | `librime_jni.so` |
| 导出 JNI 符号 | **73** 个 `Java_com_hazukieq_hkrime_*`；`Java_com_osfans_trime_*` **0** 个 |
| .note.android.ident | minSdk=21（0x15）、NDK `r24`、构建号 `8215888` — **与仓库现有旧库逐字节相同** |
| 内嵌版本串 | librime `1.7.3-74-g43023e16-arm64-v8a`、OpenCC `ver.1.1.4`、trime `v3.2.7-20261108`、app_name `com.hazukieq.hkrime` |
| 可复现性 | 同一源码树 `--clean` 冷构建 3 次，sha256 完全一致（确定性构建） |

配套（可选）优化版：`artifacts/librime_jni-release-opt.so`，6,065,152 B，sha256 `48d0494d15ceca24c7d1a0c094c03a903b2a925391743889a036b540b2684355`（`-DCMAKE_BUILD_TYPE=Release`，`-O2`，同样 73 符号）。为什么默认交付 Debug 版见 §9。

一键复现脚本：`hkrime/script/build-jni-so.sh`；符号校验脚本：`hkrime/script/verify-jni-symbols.sh`。

---

## 1. 为什么钉在 v3.2.7（证据链）

1. 仓库现有 `hkrime/app/libs/arm64-v8a/librime_jni_1_5.so`（13,558,064 B）导出 **73** 个 `Java_com_osfans_trime_*`，内嵌 librime 版本串 `1.7.3-74-g43023e1-arm64-v8a`。
2. v3.2.7 的 `app/src/main/jni/librime` 子模块 pin 正是 `43023e16d840e9cf5b11d66b1c3e426875b2dcfe`（`1.7.3-74-g43023e16`，仅 git describe abbrev 7 vs 8 位差别）。
3. 从 v3.2.7 的 `librime_jni/{rime_jni.cc,key_table.cc,levers.cc,opencc.cc}` 抽出的 `Java_com_osfans_trime_*` 符号集 = 旧库的 73 个符号，`diff` 为空（逐个对上，含 `..._data_opencc_OpenCCDictManager_openccDictConv`）。
4. 旧库 `TRIME_VERSION` 字符串是 `v3.2.6-81-g0e99167-20220625` → 旧库实际构建于 2022-06-25、v3.2.6 之后第 81 个提交；该提交与 v3.2.7 之间 `librime_jni/` 的 JNI 代码一致，因此用 v3.2.7 源码在源码级等价。
5. 上游从 v3.3.x 起把 JNI 层重构成 Kotlin `RimeApi`（44 个 camelCase 符号，如 `..._Rime_startupRime`），与 hkrime 的 snake_case Java API 不兼容 —— 不能用。仓库里 85 MB 的 `app/libs/arm64-v8a/librime_jni.so` 就是那个时代未 strip 的产物，已确认不可用。

---

## 2. 源码与取源（全部子模块 commit 已核对）

主仓库：`git clone --branch v3.2.7 --single-branch https://github.com/osfans/trime.git`
`git describe` = `v3.2.7`，`HEAD` = `8f128151969d088cbbdc89cb7dd2972e4747888e`。

子模块（`git ls-tree HEAD app/src/main/jni/` 的 gitlink 为准，逐个 `git rev-parse HEAD` 核对过）：

| 子模块 | commit | 上游版本描述 |
|---|---|---|
| `OpenCC` | `2ddaffd31f6f9cae776ec2faa4b88d5c45e0d4d6` | `ver.1.1.2-46-g2ddaffd3` |
| `boost` | `5df8086b733798c8e08e316626a16babe11bd0d2` | boostorg/boost，2022-03-10（boost 1.79 时代） |
| `capnproto` | `5cff5efc2bbb19b2512b940f79c8c696e0737424` | "Set release branch version to 0.10.1"（v0.10.1） |
| `libiconv` | `51aaded80d1986be940d9e73f7927606e316c21e` | `v1.14-74` |
| `librime` | `43023e16d840e9cf5b11d66b1c3e426875b2dcfe` | `1.7.3-74-g43023e16` |
| `librime-charcode` | `b569184772b12965e3ebe1dfd431026951fed81c` | — |
| `librime-lua` | `fff26227fc126b2e266454f932e69936ec6664d7` | `lua-dev-build-17-49-gfff2622` |
| `librime-lua-deps` | `b59a6b0536e0608aa2667ba02f3ec4434cdeeb93` | — |
| `librime-octagram` | `7302048f12c065e0971aed3b0af46a10ec3c4cf2` | — |
| `snappy` | `2b63814b15a2aaae54b7943f0cd935892fae628f` | `1.1.9` |
| `librime/deps/yaml-cpp`（嵌套） | `0579ae3d976091d7d664aa9d2527e0d0cff25763` | `yaml-cpp-0.7.0` |
| `librime/deps/leveldb`（嵌套） | `99b3c03b3284f5886f9ef9a4ef703d57373e61be` | `1.23` |

其它 vendored 依赖（上游仓库内自带，非 submodule）：`librime/deps/{snowball,utfcpp,...}`、`OpenCC/deps/{marisa-0.2.6,darts-clone,rapidjson-1.1.0,tclap-1.2.2,...}`、`librime/plugins/`（插件由 `cmake/RimePlugins.cmake` 自动发现）。

> 取源细节（本次环境网络很慢，GitHub clone ≈60 KB/s）：10 个子模块中 8 个（OpenCC/libiconv/librime/librime-charcode/librime-lua/librime-lua-deps/librime-octagram/snappy）是**从本机已有的 hkime fork 仓库的 module repo 离线取出的**（做法：把 submodule url 指向 `file:///…/hkime/.git/modules/<name>` 后 `git submodule update --init --force`），取出的 HEAD 与上表 pin 逐一相等；`librime/deps/{yaml-cpp,leveldb}` 同样离线取出。离线取源只影响“下载方式”，不影响 commit/内容。

### 2.1 两处与 pin 的偏离（必须记录的偏差）

| 依赖 | pin | 实际使用 | 原因 / 影响 |
|---|---|---|---|
| boost | `5df8086b`（boost 1.79 时代） | **boost 1.79.0 官方 release tarball** `boost_1_79_0.tar.gz`（134,111,395 B，archives.boost.io），自建 CMake-enabled 布局 | 官方 1.79.0 release tar 是 b2 布局：根目录没有 `CMakeLists.txt`、`libs/*/` 也没有 `include/`（头文件全在根 `boost/`），无法直接 `add_subdirectory(boost)`。修法见 §7.2：补官方 superproject 根 `CMakeLists.txt`（`boostorg/boost@boost-1.79.0` 原文）+ 给 143 个 `libs/**/include/boost` 建相对 symlink 指向根 `boost/`。**版本 1.79.0 与上游 pin 的 1.79 时代一致**，只是布局改造。 |
| capnproto | `5cff5efc`（v0.10.1） | **capnproto 0.10.1 官方 release tarball** `capnproto-c++-0.10.1.tar.gz`（1,700,546 B），补 `capnproto/c++/src → ../src` symlink | 官方 tar 是扁平布局（根有 `src/`、无 `c++/`），而 `cmake/CapnprotoWorkarounds.cmake` 要求 `${CMAKE_SOURCE_DIR}/capnproto/c++/src`。版本与 pin 相同。**注意：librime 1.7.3 不含任何 `.capnp` 文件、不链接 capnp runtime**（全树只有 `CMakeLists.txt` / `cmake/CapnprotoWorkarounds.cmake` / `librime/src/rime_api.h` 注释提到 capnp），capnproto 产出的东西不会进最终 .so，对产物无影响。 |

---

## 3. 构建环境

| 组件 | 值 |
|---|---|
| Android SDK | `/home/hazukie/Android/Sdk`（`hkrime/local.properties` 的 `sdk.dir`） |
| NDK | `/home/hazukie/Android/Sdk/ndk/24.0.8215888`（r24，与旧库 `.note.android.ident` 一致） |
| cmake | `/home/hazukie/Android/Sdk/cmake/3.22.1/bin/cmake`（**必须用 SDK 自带**） |
| ninja | SDK 自带 ninja 1.10.2（`…/cmake/3.22.1/bin/ninja`） |
| STL | `-DANDROID_STL=c++_static`（CMakeLists 里写的是 `c++_shared`，命令行覆盖；对齐旧库，产物 NEEDED 无 libc++_shared.so） |
| Build type | **Debug**（`-DCMAKE_BUILD_TYPE=Debug`，-O0 + -g，strip 后交付） |

> **host cmake 3.28.3 不能用**：Ubuntu 的 cmake 包把 `/usr/share/cmake-3.28/Modules/Platform/Android/` 整个目录剥掉了，报
> `CMake Error at …/Platform/Android-Determine-C.cmake:1 (include): include could not find requested file: Platform/Android/Determine-Compiler`，随后 `CMAKE_C_COMPILER not set`。所以脚本优先用 SDK 的 cmake。

---

## 4. patch 清单（JNI 类路径 / 符号改造）

改动全部落在 `app/src/main/jni/librime_jni/` 内，共 **6 个文件、85 行修改 / 85 行删除**：

```
$ git -C <src> diff --stat
 app/src/main/jni/librime_jni/CMakeLists.txt |   2 +-
 app/src/main/jni/librime_jni/jni-utils.h    |  20 ++---
 app/src/main/jni/librime_jni/key_table.cc   |   4 +-
 app/src/main/jni/librime_jni/levers.cc      |  14 ++--
 app/src/main/jni/librime_jni/opencc.cc      |   8 +-
 app/src/main/jni/librime_jni/rime_jni.cc    | 122 ++++++++++++++--------------
 6 files changed, 85 insertions(+), 85 deletions(-)
```

等价 sed 命令（脚本按“old 存在 → sed；new 已存在 → 跳过；都不在 → 报错退出”三态执行，绝不静默跳过；`--pkg` 可参数化）：

```bash
cd app/src/main/jni/librime_jni
# 1) 导出的 JNI 符号名：Java_com_osfans_trime_* -> Java_com_hazukieq_hkrime_*（73 处）
sed -i 's/Java_com_osfans_trime_/Java_com_hazukieq_hkrime_/g' \
    rime_jni.cc key_table.cc levers.cc opencc.cc
# 2) jni-utils.h：硬编码的 FindClass / GetFieldID 类名与字段签名（class 路径形式，20 处）
sed -i 's|com/osfans/trime|com/hazukieq/hkrime|g' jni-utils.h
#    （覆盖 "com/osfans/trime/core/Rime"、Rime$RimeComposition…$RimeStatus、
#      "Lcom/osfans/trime/core/Rime$RimeComposition;"、"Lcom/osfans/trime/core/Rime$RimeMenu;"、
#      "[Lcom/osfans/trime/core/Rime$RimeCandidate;"）
# 3) rime_jni.cc：traits.app_name = "com.osfans.trime" -> "com.hazukieq.hkrime"（点号形式，1 处，第 45 行）
sed -i 's|com\.osfans\.trime|com.hazukieq.hkrime|g' rime_jni.cc
# 4) CMakeLists.txt：编译期 CLASSNAME 宏（第 38 行）
sed -i 's|com/osfans/trime/core/Rime|com/hazukieq/hkrime/core/Rime|g' CMakeLists.txt
```

兜底断言（脚本内置）：`grep -rnE '(com[./]osfans[./]trime|Java_com_osfans_trime_)' librime_jni/` 必须为空；`cat *.cc | grep -o Java_com_hazukieq_hkrime_ | wc -l` == 73。
源码里 73 个入口的分布：`rime_jni.cc` 60 + `levers.cc` 7 + `opencc.cc` 4 + `key_table.cc` 2。

---

## 5. 完整可复现构建命令（本次实际执行）

```bash
SRC=/home/hazukie/Projects/common/trime-workspace/jni-build/trime-v3.2.7
cd "$SRC"
NDK=/home/hazukie/Android/Sdk/ndk/24.0.8215888
SDKCM=/home/hazukie/Android/Sdk/cmake/3.22.1/bin

# configure（-UBOOST_EXCLUDE_LIBRARIES 是必须的坑规避，见 §7.6）
$SDKCM/cmake -S app/src/main/jni -B build-arm64-v8a-Debug -G Ninja \
  -DCMAKE_MAKE_PROGRAM=$SDKCM/ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Debug -UBOOST_EXCLUDE_LIBRARIES
# build（357 个编译/链接步）
$SDKCM/ninja -C build-arm64-v8a-Debug -j20 rime_jni
# strip
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded \
  build-arm64-v8a-Debug/librime_jni/librime_jni.so
```

一键（等价、含 clone/patch/校验，推荐）：

```bash
cd /home/hazukie/Projects/common/trime-workspace/hkrime
./script/build-jni-so.sh                        # 默认：clone v3.2.7 → patch → build → strip → 装 app/libs/arm64-v8a → 校验
./script/build-jni-so.sh --clean                # 先清源码/构建目录再全量（幂等，可反复跑）
./script/build-jni-so.sh --src-dir <已备好的源码树> --out-dir /tmp/out   # 离线复用源码（本报告就是这样产出的）
./script/build-jni-so.sh --build-type Release   # 出 6 MB 的 -O2 版
```

**耗时**：冷构建（configure + 357 步编译/链接）**18–21 s**（20 核，`-j20`；`-j16` 时 19–21 s）；无改动复跑（增量）≈ **1.4 s**。
`artifacts/build-jni.log` = 本次交付产物的**完整**构建日志（脚本 tee 输出：环境探测 → 子模块 pin 校验 → patch → configure → 357 步 → strip → 符号校验 → 汇总，109,816 B）。另两份证据日志：`artifacts/build-jni-release-attempt.log`（Release 尝试 + 早期失败 configure 的错误原文）、`artifacts/build-jni-defaulttype.log`（不传 `CMAKE_BUILD_TYPE` 的对照实验）。

---

## 6. 配置阶段关键输出（日志摘录）

```
-- Found plugin: …/librime/plugins/librime-charcode
-- Found plugin: …/librime/plugins/librime-lua
-- Found plugin: …/librime/plugins/librime-octagram
-- Using in-tree lua source
-- rime_plugins_libs: rime-charcode-deps
-- rime_plugins_modules: charcode;lua;octagram
-- Configuring done
-- Generating done
```

链接行（`ninja -t commands` 尾部）实际参与静态链接的库：
`librime/lib/librime.a`、`OpenCC/src/libopencc.a`、`OpenCC/deps/marisa-0.2.6/libmarisa.a`、`boost_{filesystem,iostreams,random,serialization,locale,thread,date_time,exception,container,atomic,chrono}`、`librime/deps/yaml-cpp/libyaml-cpp.a`、`librime/deps/leveldb/libleveldb.a`、`snappy/libsnappy.a`、`libiconv.a`、`-llog -lz -latomic -lm`。
（`X11/keysym.h not found` 是无关警告，librime 的 X11 支持在 Android 上不启用。）

---

## 7. 遇到的错误与解决办法（全部已解决）

### 7.1 host cmake 3.28.3 缺 Android platform 模块
见 §3。→ 改用 SDK 自带 cmake 3.22.1。

### 7.2 boost 1.79.0 官方 tar 是 b2 布局，`add_subdirectory(boost)` 直接失败
错误：`CMake Error at CMakeLists.txt:22 (add_subdirectory): The source directory …/app/src/main/jni/boost does not contain a CMakeLists.txt file.`
排查：`ls boost/libs/filesystem/` 只有 `bug build cmake config doc example meta src test`，没有 `include/`；头文件全在根 `boost/`。
解决：
1. 取官方 superproject 根 `CMakeLists.txt`（`raw.githubusercontent.com/boostorg/boost/boost-1.79.0/CMakeLists.txt`，821 B，内容为 `cmake_minimum_required(VERSION 3.5...3.16)` / `project(Boost VERSION 1.79.0 LANGUAGES CXX)` / `list(APPEND CMAKE_MODULE_PATH ${PROJECT_SOURCE_DIR}/tools/cmake/include)` / `include(BoostRoot)`）放进 `boost/`；
2. 给 `libs/*/CMakeLists.txt` 的每个 lib 建 `include/boost -> ../../../boost`（`libs/numeric/<x>/` 用 `../../../../boost`），使每个 `Boost::<lib>` 的 `INTERFACE_INCLUDE_DIRECTORIES` 都存在且指向合并后的头文件树（共 143 个 lib）。
之后 `BoostRoot.cmake` 正常 `file(GLOB libs/*/CMakeLists.txt libs/numeric/*/CMakeLists.txt)` 并创建 `Boost::algorithm / any / crc / …` 等 target。

### 7.3 `Boost::algorithm` 等 target 不存在（连带错误）
`CMake Error at cmake/RimePlugins.cmake:13 (get_target_property): get_target_property() called with non-existent target "Boost::algorithm"` —— 是 7.2 的后果，7.2 修好即消失。

### 7.4 `librime/deps/{yaml-cpp,leveldb}` 是空的（嵌套子模块未 init）
错误：`CMake Error at CMakeLists.txt:35/47 (add_subdirectory): … does not contain a CMakeLists.txt file`。
解决：`git -C librime submodule update --init --force -- deps/yaml-cpp deps/leveldb`（本次同样走向 hkime 的 module repo 离线取），HEAD 分别为 `0579ae3…`/`99b3c03…`，与 librime pin 一致。
附带结论：librime 1.7.3 不需要 `marisa-trie/glog/googletest` 子模块（marisa 由 `OpenCC/CMakeLists.txt` 的 `add_subdirectory(deps/marisa-0.2.6)` 提供）。

### 7.5 capnproto 0.10.1 官方 tar 是扁平布局
`cmake/CapnprotoWorkarounds.cmake` 要求 `${CMAKE_SOURCE_DIR}/capnproto/c++/src`（含 `CAPNPC_IMPORT_DIRS`）。解决：`mkdir -p capnproto/c++ && ln -s ../src capnproto/c++/src`。（再次强调：capnproto 的产物不进 librime_jni.so。）

### 7.6 第二次 configure 必失败：`boost_log links to target Boost::coroutine but the target was not found`（**最隐蔽的坑**，已定位并规避）
现象：同一个 build 目录，第一次 configure 成功并编译 357 步；之后任何一次重新 configure 都在 Generate 阶段失败：
```
CMake Error at boost/libs/log/CMakeLists.txt:434 (add_library):
  Target "boost_log" links to target "Boost::coroutine" but the target was not found.
```
根因：`app/src/main/jni/cmake/FindBoost.cmake:4` 是
`set(BOOST_EXCLUDE_LIBRARIES "context;coroutine;fiber" CACHE STRING "" FORCE)`，
而 boost 的 `tools/cmake/include/BoostRoot.cmake` 在第 29 行把 `BOOST_EXCLUDE_LIBRARIES` 声明为 cache、第 260 行据此**跳过** excluded lib 的 target 创建。
时序：`FindBoost.cmake` 在 `include(RimePlugins)`（`add_subdirectory(boost)` 之后）里执行 → 第一次 configure 时 boost 尚看不到排除列表，`Boost::coroutine` 等 target 正常创建，generate 通过；但它把排除列表写进 CMakeCache → **第二次起** boost 在 configure 最开始就看到排除列表，不建 `Boost::coroutine`，而 `boost_log` 仍链接它 → Generate 失败。
解决：每次 configure 前从 cache 删掉该变量。脚本里作为固定参数：
`cmake … -UBOOST_EXCLUDE_LIBRARIES`（已实测：同一 build 目录重新 configure 成功、可反复）。这一点写进了 `build-jni-so.sh` 的注释。

---

## 8. 产物核验

### 8.1 file / readelf
```
$ file artifacts/librime_jni.so
ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, BuildID[sha1]=707f3101bdb3d87edf387ded0be2ffb43c50c67d, stripped

$ readelf -h artifacts/librime_jni.so
  类别: ELF64   数据: 2 补码，小端序   类型: DYN (共享目标文件)   系统架构: AArch64
  程序头数量: 9   节头数量: 27

$ readelf -d artifacts/librime_jni.so
 (NEEDED) liblog.so / libz.so / libm.so / libdl.so / libc.so
 (SONAME) librime_jni.so
 (FLAGS)  BIND_NOW   (FLAGS_1) NOW

主要节：.text 5,352,736 B ｜ .rodata 1,142,380 B ｜ .eh_frame 1,487,988 B ｜ .rela.dyn 3,753,424 B
```

### 8.2 `.note.android.ident`（与旧库**逐字节相同**）
两者 description data 均为 `15 00 00 00`（minSdk **21**）+ `r24` + `8215888`：
```
Android 0x00000084 NT_VERSION
  15 00 00 00 72 32 34 00 … 38 32 31 35 38 38 38 00 …      # 21 / "r24" / "8215888"
```

### 8.3 符号统计
```
$ nm -D --defined-only artifacts/librime_jni.so | wc -l
24825
$ nm -D --defined-only … | awk '{print $NF}' | grep -c '^Java_'
73                       # 全部 Java_com_hazukieq_hkrime_*
$ … | grep -c '^Java_com_osfans'
0
$ … | grep -c '^Java_com_hazukieq_hkrime_'
73
```
73 个符号覆盖：`Java_com_hazukieq_hkrime_core_Rime_*`（72 个 native 方法，snake_case，如 `_cleanup_stale_sessions`、`_config_get_bool`、`_finalize1`、`_opencc_convert_dictionary`、`_get_trime_version`）
\+ `Java_com_hazukieq_hkrime_data_opencc_OpenCCDictManager_openccDictConv`（1 个）。

### 8.4 内嵌字符串
```
1.7.3
1.7.3-74-g43023e16-arm64-v8a
v3.2.7-20261108                 # TRIME_VERSION = git describe + TODAY(构建日 2026-11-08)
ver.1.1.4                       # OpenCC
com.hazukieq.hkrime             # gflags/glog app_name（旧库此处是 com.osfans.trime）
```

### 8.5 插件是否真的编进去了
`build-arm64-v8a-Debug` 下目标文件计数：librime-charcode 3、librime-lua 38、librime-octagram 4、OpenCC 29、leveldb 38、snappy 4（全部经 `librime.a` 链入）；`strings` 里 `charcode`/`octagram` 各出现 2 次，与旧库一致。

---

## 9. 与仓库现有 13.5 MB 旧库对比

| 指标 | 旧库 `librime_jni_1_5.so` | 新库（本次交付，Debug） | 差异 |
|---|---|---|---|
| 大小 | 13,558,064 | 13,587,824 | +29,760 B (+0.22 %) |
| `.text` | 5,348,440 | 5,352,736 | +4,296 B (+0.08 %) |
| `.rodata` | 1,131,772 | 1,142,380 | +10,608 B |
| dynsym 总数 | 24,750 | 24,825 | +75 |
| 导出 Java_* | 73（`com_osfans_trime`） | 73（`com_hazukieq_hkrime`） | 包名替换，一一对应 |
| Java 符号集（归一化包名后） | — | — | **完全一致**（`diff` 为空） |
| NEEDED | liblog libz libm libdl libc | 同 | 一致 |
| `.note.android.ident` | 21 / r24 / 8215888 | 同 | 逐字节一致 |
| BuildID | 583233859186ac7e00b371d547094f01b76a43fd | 707f3101bdb3d87edf387ded0be2ffb43c50c67d | — |
| librime 版本串 | `1.7.3-74-g43023e1-arm64-v8a` | `1.7.3-74-g43023e16-arm64-v8a` | git abbrev 7 vs 8 位 |
| trime 版本串 | `v3.2.6-81-g0e99167-20220625` | `v3.2.7-20261108` | 源码 ref / 构建日期不同 |

**体积归因（为什么 13.5 MB 而不是 6 MB）**：旧库是 **Debug/-O0** 产物（`-O0` + `-g`，strip 掉调试信息后仍保留 -O0 代码与内联的字符串），不是 Release。实测对照：
- `-DCMAKE_BUILD_TYPE=Release`（-O2）→ strip 后 **6,065,152 B**，`.text` 2,620,224（是旧库 `.text` 的 0.49×）；
- 不传 `CMAKE_BUILD_TYPE`（NDK toolchain 默认带 -O2）→ strip 后 5,988,264 B；
- `-DCMAKE_BUILD_TYPE=Debug`（-O0）→ strip 后 **13,587,824 B**，与旧库仅差 0.22 %。

旧库之所以是 Debug：`app/build.gradle` 的 `externalNativeBuild` 没有传 `CMAKE_BUILD_TYPE`，AGP 对 **debug variant** 会自动传 `-DCMAKE_BUILD_TYPE=Debug` —— 旧库就是这样来的。
**交付决策**：主产物选 Debug 版（与现有库同构、行为风险最低、符合 13–15 MB 预期、功能/符号完全等价）；同时保留 `librime_jni-release-opt.so`（6.06 MB，-O2，同样 73 符号、NEEDED 相同）供 Lead 权衡（换它可减少 APK 体积约 7.5 MB，但属于未在真机验证过的代码生成路径）。

---

## 10. 运行期兼容性核对（比符号更强的证据）

`jni-utils.h` 里所有 `GetFieldID` 都是**硬编码字段名 + 签名**，一旦与 hkrime Java 侧不一致，运行期就是 `NoSuchFieldError`/内存错乱。已逐项核对 `hkrime/app/src/main/java/com/hazukieq/hkrime/core/Rime.java`（renamer 改包名后的现状），**全部匹配**：

| 内部类 | `.so` 里硬编码的字段（签名） | Rime.java 实际声明 |
|---|---|---|
| `Rime$RimeComposition` | `length(I) cursor_pos(I) sel_start(I) sel_end(I) preedit(Ljava/lang/String;)` | `int length; int cursor_pos; int sel_start; int sel_end; String preedit;` ✓ |
| `Rime$RimeCandidate` | `text(Ljava/lang/String;) comment(Ljava/lang/String;)` | `public String text; public String comment;` ✓ |
| `Rime$RimeMenu` | `page_size(I) page_no(I) is_last_page(Z) highlighted_candidate_index(I) num_candidates(I) candidates([LRime$RimeCandidate;) select_keys(Ljava/lang/String;)` | 同名同类型 ✓ |
| `Rime$RimeCommit` | `data_size(I) text(Ljava/lang/String;)` | ✓ |
| `Rime$RimeContext` | `data_size(I) composition(LRime$RimeComposition;) menu(LRime$RimeMenu;) commit_text_preview(Ljava/lang/String;) select_labels([Ljava/lang/String;)` | ✓ |
| `Rime$RimeStatus` | `data_size(I) schema_id schema_name is_disabled(Z) is_composing(Z) is_ascii_mode(Z) is_full_shape(Z) is_simplified(Z) is_traditional(Z) is_ascii_punct(Z)` | ✓ |
| `Rime`（顶层） | `FindClass("com/hazukieq/hkrime/core/Rime")` | `package com.hazukieq.hkrime.core; public class Rime` ✓ |

JNI 契约合计 = Rime.java 72 个 `public static native` + OpenCCDictManager.kt 1 个 `@JvmStatic external fun openccDictConv(String,String,boolean)` = **73**，与 .so 导出 73 完全吻合（`verify-jni-symbols.sh` 实测：缺失 0、残留旧包名 0、多余 0）。
注意 `OpenCCDictManager` 是 Kotlin `object`，`@JvmStatic external fun` 的符号落在 `…_data_opencc_OpenCCDictManager_openccDictConv`（不是 `…$Companion_…`），已实测导出 ✓。

---

## 11. 风险与注意点

1. **`traits.app_name` 改名（唯一的“行为敏感点”）**
   `rime_jni.cc:45` 由 `"com.osfans.trime"` 改成 `"com.hazukieq.hkrime"`。查 librime 源码，`RimeTraits.app_name` 只在一处被消费：`librime/src/rime_api.cc:55-60` → `SetupLogging(traits->app_name, …)` → `librime/src/rime/setup.cc:77` `google::InitGoogleLogging(app_name)`。
   - **不影响** `shared_data_dir` / `user_data_dir`：这两个路径由 Java 侧显式传给 `setup/initialize/deployer_initialize`（`rime_jni.cc:39-48`），与 app_name 无关 → **用户词典、配置、旧用户数据目录完全不受影响**。
   - **影响** glog 日志文件名与日志头里的程序名：`com.osfans.trime.<host>.<user>.log.<SEVERITY>.<date>` → `com.hazukieq.hkrime.<host>.<user>.log.<SEVERITY>.<date>`。旧日志文件不会被自动清理/续写；若有按文件名解析或清理日志的脚本（含 CI、bugreport 收集），需要同步改。
   - rime_jni.cc 里没有设置 `traits.log_dir`/`min_log_level`，沿用上游默认（`SetupLogging(app_name, 0, NULL)`）。
2. **boost / capnproto 偏离 pin**（见 §2.1）：boost 版本仍是 1.79 系列但来自官方 release tar + 自制 CMake 布局；capnproto 0.10.1 且不参与链接。报告保留此项，供审阅。
3. **TRIME_VERSION 含构建日期**：`v3.2.7-20261108`（上游 CMake 的 `git describe` + `TODAY`）。若 `Rime.get_trime_version()` 的展示会被用户看到/被比对，需要知道它随构建日期变。
4. **Debug 版交付**：-O0 + `-g`（已 strip，不含调试信息）。若日后想换 -O2 版，用 `--build-type Release` 或直接用 `artifacts/librime_jni-release-opt.so`（符号/NEEDED 均相同，但未做真机验证）。
5. **尚未做真机/APK 验证**（超出本任务边界，且 gradle 由其他 teammate 独占）：本次只保证「源码级等价 + 符号契约一致 + 字段 ID 一致 + 与旧库同构」。建议集成后在设备上跑一次：启动输入法 → 候选栏（`RimeContext`/`RimeMenu` 字段）→ 上屏（`RimeCommit`）→ 状态栏中英/简繁（`RimeStatus`）→ OpenCC 转换 → 部署/维护。
6. **APK 集成动作**：把 `artifacts/librime_jni.so` 放到 `hkrime/app/libs/arm64-v8a/librime_jni.so`（`build-jni-so.sh` 不带 `--out-dir` 时自动做这一步；本次为避免写 `hkrime/app/**` 只用 `--out-dir artifacts`）。仓库里的旧 `librime_jni_1_5.so` / 85 MB 的 `librime_jni.so` 是否删除由 Lead 决定。
7. **脚本的 clone 链路未在本次实跑**（GitHub 太慢，取源改用离线 hkime module repo + 官方 tar）；`--src-dir` 模式、pin 校验、patch、构建、strip、校验全链路已实跑 3 次（含 `--clean`）。clone 模式与 `--src-dir` 模式共用同一套 pin 校验/ patch / 构建逻辑。

---

## 12. 交付物与证据索引

| 路径 | 内容 |
|---|---|
| `artifacts/librime_jni.so` | arm64-v8a stripped 产物，13,587,824 B，sha256 `d6b295b6…98ec` |
| `artifacts/librime_jni.so.sha256` | 上述 sha256（相对文件名） |
| `artifacts/build-jni.log` | **完整**构建日志（环境->pin 校验->patch->configure->357 步->strip->符号校验->汇总） |
| `artifacts/librime_jni-release-opt.so` (+`.sha256`) | 可选 -O2 版：6,065,152 B，sha256 `48d0494d…4355` |
| `artifacts/build-jni-release-attempt.log` | Release 尝试日志（含早期 configure 失败的错误原文，问题现场证据） |
| `artifacts/build-jni-defaulttype.log` | 不传 `CMAKE_BUILD_TYPE` 的对照构建日志（体积归因证据） |
| `hkrime/script/build-jni-so.sh` | 一键可复现构建脚本（clone/离线复用、pin 校验、patch、cmake+ninja、strip、安装、调校验） |
| `hkrime/script/verify-jni-symbols.sh` | JNI 契约校验（源码声明 vs .so 导出，缺失/旧包名 → 非 0 退出） |
| 本次工作源码树 | `jni-build/trime-v3.2.7/`（已 patch；`build-arm64-v8a-Debug/` 为最终构建目录） |
| 官方包下载缓存 | `jni-build/dl/boost_1_79_0.tar.gz`、`jni-build/dl/capnproto-0.10.1.tar.gz` |

校验脚本自测结果（两条用例，可复跑）：
```
# 用例 A：新库 → 通过
$ ./script/verify-jni-symbols.sh --so artifacts/librime_jni.so
  源码声明 73 / .so 导出 73 / 缺失 0 / 残留旧包名 0 / 多余 0  → OK (exit 0)
# 用例 B：旧 13.5MB 库 → 按预期失败
$ ./script/verify-jni-symbols.sh --so app/libs/arm64-v8a/librime_jni_1_5.so
  缺失 73 / 残留旧包名 73 / 多余 73  → FAILED (exit 1)
```
