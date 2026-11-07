# 02 · JNI 与 `librime_jni.so`（定稿）

> **本篇状态：定稿（v3，2026-11-08 第二轮）**。`.so` 已重建（新包名 + 16 KB 对齐）、`loadLibrary` 已统一、符号契约 73/73 通过、真机仪器测试通过。
> 历史故障（85 MB 废库）保留在本篇 §2 作为「为什么会崩」的记录与排障依据。

## 目录

1. [定稿事实](#1-定稿事实)
2. [历史：为什么曾经会崩](#2-历史为什么曾经会崩)
3. [JNI 桥的工作原理](#3-jni-桥的工作原理)
4. [为什么改包名必须重编 `.so`](#4-为什么改包名必须重编-so)
5. [怎么重建（脚本化）](#5-怎么重建脚本化)
6. [怎么验证](#6-怎么验证)
7. [16 KB page 对齐（Android 15/16 必需）](#7-16-kb-page-对齐android-1516-必需)
8. [优化版 `.so`（备选，未采用）](#8-优化版-so备选未采用)
9. [附录：72 个 native 方法清单](#9-附录72-个-native-方法清单)

---

## 1. 定稿事实

**唯一有效库**：`app/libs/arm64-v8a/librime_jni.so`（仓库内 `app/libs/` 下**只有这一个** `.so`）。

| 项 | 实测值 |
|---|---|
| 大小 | **13,587,744 B**（13.59 MB） |
| sha256 | **`fd4372553592830cc8af03a7cae10009eb1882f2eada957086513a7fbc60f03f`** |
| 格式 | `ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, stripped` |
| 页对齐 | **4 个 LOAD 段全部 `Align=0x4000`（16 KB）**，见 §7 |
| 构建类型 | **Debug（`-O0`）**，刻意与旧 13.5 MB 库同构（默认即此；`--build-type Release` 可出 `-O2` 版） |
| 构建标识 | 无 GNU build-id（strip 后未保留）；`.note.android.ident` 记录 NDK `r24` / `8215888` |
| 导出符号 | **73 个** `Java_com_hazukieq_hkrime_*`，旧前缀 `Java_com_osfans_trime_` **0 个** |
| 内嵌版本串 | `1.7.3-74-g43023e16-arm64-v8a`（librime）、`v3.2.7-20261108`（trime）、`ver.1.1.4`（OpenCC） |
| 内嵌包名 | `com.hazukieq.hkrime`（`traits.app_name`），`com/osfans/trime` **0 处**，`com/hazukieq/hkrime` 10 处 |
| 调试串 | Debug 构建保留 `__FILE__` 断言串，`strings` 可见构建机路径 `/home/hazukie/Projects/common/trime-workspace/jni-build/trime-v3.2.7/...`——**功能无影响**，仅说明它由 Debug 构建产出 |
| NEEDED | `liblog.so` `libz.so` `libm.so` `libdl.so` `libc.so` —— **无 `libc++_shared.so`**（`c++_static` 自包含，无需额外打包） |
| SONAME | `librime_jni.so`（与 `System.loadLibrary("rime_jni")` 的文件名解析一致） |

**加载点已统一**（仓库内 `rime_jni_1_5` 引用为 0）：

| 文件 | 行 | 内容 |
|---|---|---|
| `app/src/main/java/com/hazukieq/hkrime/core/Rime.java` | `:238` | `System.loadLibrary("rime_jni");` |
| `app/src/main/java/com/hazukieq/hkrime/data/opencc/OpenCCDictManager.kt` | `:15` | `System.loadLibrary("rime_jni")` |

**契约校验**（`./script/verify-jni-symbols.sh` 实测）：

```text
# parsed_files=2 parsed_decls=73
  源码声明（去重）    : 73
  .so 导出 Java_*     : 73
  缺失                : 0
  残留旧包名          : 0  (prefix: Java_com_osfans_trime_)
  多余（源码未声明）  : 0
OK: JNI 符号契约一致（73 个声明全部导出，无旧包名残留）
```

> 73 = `Rime.java` 的 72 个 `native` + `OpenCCDictManager.kt:15` 的 1 个 `external fun openccDictConv`。

### 历史遗留（已清理）

| 路径 | 状态 | 说明 |
|---|---|---|
| `libs_backup/arm64-v8a/librime_jni.so` | **已删除**（`2b50c1f`，13,558,064 B → 0） | 旧的**正确但过时**库（`com.osfans.trime` 包名）。删除前是仓库里唯一的「旧包名可用库」；如仍需，可从 git 历史 `92c7aa5`/`c96f92b` 检出 |
| `app/libs/arm64-v8a/librime_jni_1_5.so` | **已删除**（`922a2ba`） | 13,558,064 B 旧库，与 `libs_backup` 备份同文件 |
| `artifacts/librime_jni-release-opt.so`（仓库外） | 保留 | 6,065,152 B，sha256 `6b6173d1…f920`，`-O2` 优化版备选，**未装入 app**，见 §8 |

清理结论：`app/libs/arm64-v8a/` 下只剩 1 个 `.so`，不再存在「装错库」的选项。

## 2. 历史：为什么曾经会崩

> 以下为 2026-11-08 之前的状态，现已全部修复。保留它是因为**同类事故极易复发**（「直接提交预编译 `.so`」的代价）。

### 2.1 曾经并存的两个库

| 项 | `librime_jni.so`（85 MB，废） | `librime_jni_1_5.so`（13.5 MB，旧） |
|---|---|---|
| 大小 | 85,786,600 B | 13,558,064 B |
| sha256 | `ff510498…38aad` | `1b5f1eac…563a` |
| 符号表 | **未 strip（含 debug_info）** | stripped |
| 内嵌 librime | `1.8.5-5-g12ff7bf-arm64-v8a` | `1.7.3-74-g43023e1-arm64-v8a` |
| 导出 `Java_` | 39（旧 camelCase：`getRimeContext`、`openCCDictConv` 大写 CC） | 73（v3.2.7 式 `get_1context`、`openccDictConv` 小写 cc） |
| 与 Java 72 个 native 的匹配 | **0 / 72** | **72 / 72** |
| 本机调试路径 | 有 `/home/hazukie/trime-new/...` | 无 |

**崩溃机制**（两条互相矛盾的加载路径）：

```text
路径 A：IME 启动
  Trime.onCreate → invalidate() → Rime.get() → Rime.init() 调 native setup()
    loadLibrary("rime_jni_1_5") → 13.5MB 库 → 有 setup → OK
    loadLibrary("rime_jni")     → 85MB 库   → 无 setup → UnsatisfiedLinkError

路径 B：OpenCC 词典部署
  OpenCCDictManager.internalDeploy() → loadLibrary("rime_jni") → 85MB 库
    external fun openccDictConv 期望 Java_..._openccDictConv
    85MB 库只有 openCCDictConv（大写 CC）→ UnsatisfiedLinkError
```

两个库的 `SONAME` 都是 `librime_jni.so`，但 `System.loadLibrary("rime_jni_1_5")` 是**按文件名**找
`librime_jni_1_5.so`，与 SONAME 无关 —— 所以「名字不同、加载点也不同」这件事**编译期完全不报错**，
只在运行时按调用路径分别爆炸，表现为「有时能用、有时崩」。

### 2.2 事故是怎么进入仓库的

```text
92c7aa5 "close log" (2023-09-15)   librime_jni.so 13.5MB → 85MB（换成 1.8.5 调试构建）
                                   同时把 Rime.java 的 loadLibrary 改成 "rime_jni_1_5"
c96f92b "close log" (2023-09-15)   补入 13.5MB 的 librime_jni_1_5.so + libs_backup 备份
                                   → 旧的 85MB 忘了删，形成「一废一好并存」
2026-11-08 task-1/3                重编 13,587,824 B 新库（新包名），删除两个旧库，统一 loadLibrary
```

> 注：网上常见的说法是「hakka 分支把 `_1_5` 写进 Rime.java 却漏了库」——**不准确**。
> `git log --all -S "rime_jni_1_5"` 证明改 `loadLibrary` 的是 `92c7aa5`；`4af2a94` 对 `Rime.java` 只改了一行 Timber 日志。

## 3. JNI 桥的工作原理

本项目**不使用 `RegisterNatives` 动态注册**，完全依赖**符号名静态绑定**：

| Java 侧 | native 侧符号 |
|---|---|
| `com.hazukieq.hkrime.core.Rime.setup()` | `Java_com_hazukieq_hkrime_core_Rime_setup` |
| `Rime.getContext()`（Java 内部包装） | `Java_com_hazukieq_hkrime_core_Rime_get_1context` |
| `OpenCCDictManager.openccDictConv()` | `Java_com_hazukieq_hkrime_data_opencc_OpenCCDictManager_openccDictConv` |

名字修饰规则（JNI Spec）：

| Java 字符 | 符号中 | 例 |
|---|---|---|
| `.` 包分隔 | `_` | `com.hazukieq.hkrime.core.Rime` → `com_hazukieq_hkrime_core_Rime` |
| `_` | `_1` | `get_context` → `get_1context` |
| `$`（内嵌类） | `_00024` | 用 `FindClass` 而非符号，本仓库未触发 |

反向（native → Java）靠 `jni-utils.h` 里的 **`FindClass` + `GetFieldID` 硬编码字符串**：

| 位置 | 内容 |
|---|---|
| `librime_jni/jni-utils.h:136` | `FindClass("com/hazukieq/hkrime/core/Rime")` |
| `:139,146,150,154,161,170` | `Rime$RimeComposition` / `Candidate` / `Commit` / `Context` / `Menu` / `Status` |
| `:137` | `GetStaticMethodID(Rime, "handleRimeNotification", "(Ljava/lang/String;Ljava/lang/String;)V")` |
| `:156,157,167` | 字段签名 `Lcom/hazukieq/hkrime/core/Rime$RimeComposition;` 等 |

这些字符串**只存在于 `.so` 的调试信息/字符串表里**（`strings` 可见），源码层面已随 task-1 的 patch 全量替换。

## 4. 为什么改包名必须重编 `.so`

包名/类名被写死进二进制的**三处**，任一不改，改名后必然 `UnsatisfiedLinkError` 或 `NoSuchMethodError`：

| # | 位置（上游 v3.2.7） | 内容 | 影响 |
|---|---|---|---|
| 1 | 所有 `.cc` 函数名 | `Java_com_osfans_trime_*` 共 **73 处**（`rime_jni.cc` 60、`opencc.cc` 4、`librime_jni/levers.cc` 7、`key_table.cc` 2） | 符号名对不上 → `UnsatisfiedLinkError` |
| 2 | `librime_jni/jni-utils.h:136-170` | `FindClass` / 字段签名 / `GetStaticMethodID` | 找不到类 → native 层 pending exception / 崩溃 |
| 3 | `rime_jni.cc:45` | `traits.app_name = "com.osfans.trime"` | 见下 |

**关键推论**：旧的 13.5 MB「正确库」**同样是按 `com.osfans.trime` 编译的**。
所以「改名」与「换 `.so`」**必须同批发布**——只做其中一件事，输入法必然崩。
这也是任务链 task-1（重建）→ task-2（改名）→ task-3（集成）必须串行、且最终一次性提交的原因。

### `traits.app_name` 改名的真实影响

| 项 | 结论（已核实） |
|---|---|
| 用户数据目录 | **不受影响**——目录由 Java 侧通过 `Rime.init()` 的 `setup(sharedDataDir, userDataDir, …)` 显式传入（`core/Rime.java:324-353`） |
| glog 日志文件名 | **唯一行为变化**：`com.osfans.trime.*` → `com.hazukieq.hkrime.*`（librime 用 app_name 作日志文件名前缀） |
| 与旧版共存 | 两个 app 的 `app_name` 不同 → 不会争抢同一份日志/身份，共存更干净 |

> 因此 `app_name` 改成新包名是**安全且推荐**的；若将来需要「与旧版共享 RIME 身份」，才考虑保留旧值，并需单独评估。

### 一个必须知道的陷阱（已确认为死代码）

`librime_jni/CMakeLists.txt:37-42` 定义了 `CLASSNAME="com/osfans/trime/core/Rime"` 宏，
但 grep 全部 5 个 `.cc` + 2 个 `.h`，**`CLASSNAME` 在 v3.2.7 里从未被引用**。
→ 改这个宏对产物**毫无影响**。真正要改的是 `.cc` 里的函数名与 `jni-utils.h` 里的字面量。

## 5. 怎么重建（脚本化）

**不要手工执行 git clone / sed / cmake**——已固化为 `script/build-jni-so.sh`（415 行，幂等、可重复）。

```bash
cd /home/hazukie/Projects/common/trime-workspace/hkrime

# 默认：clone 上游 v3.2.7 → 校验子模块 pin → patch 包名 → cmake+ninja → strip
#       → 安装到 app/libs/arm64-v8a/librime_jni.so → 自动调 verify-jni-symbols.sh
./script/build-jni-so.sh

# 常用变体
./script/build-jni-so.sh --clean                    # 清掉源码树与构建目录后从头来（幂等）
./script/build-jni-so.sh --build-type Release       # -O2 优化版（约 6 MB，见 §7）
./script/build-jni-so.sh --src-dir /path/to/trime-v3.2.7   # 复用已 clone 的源码（离线）
./script/build-jni-so.sh --out-dir /tmp/out         # 只装到临时目录（不动 app/libs）
```

### 参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--upstream-repo` | `https://github.com/osfans/trime.git` | 上游仓库 |
| `--upstream-ref` | `v3.2.7` | **必须钉在 v3.2.7**（v3.3.x 起 JNI API 全变） |
| `--pkg` | `com.hazukieq.hkrime` | 目标包名 |
| `--old-pkg` | `com.osfans.trime` | 旧包名（patch 的匹配源） |
| `--abi` | `arm64-v8a` | AB 仅此一个 |
| `--ndk` | 自动探测（`--ndk` → `local.properties` 的 `sdk.dir` → `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `/home/hazukie/Android/Sdk`） | NDK 24.0.8215888 |
| `--build-type` | `Debug` | `Debug`=`-O0`（与旧库同构）；`Release`=`-O2` |
| `--android-platform` | `android-21` | 与 `minSdk` 对齐 |
| `--src-dir` | 空 | 复用源码树，跳过 clone |
| `--work-dir` / `--build-dir` / `--out-dir` | `../jni-build` / 默认 / `app/libs/<abi>` | 工作目录与产物去向 |
| `--expect-symbols` | `73` | 期望导出符号数 |
| `--log` | 自动 | 构建日志 |
| `--clean` | 关 | 先清理 |

### 脚本内部顺序（固定的 7 步）

```text
1. 探测 SDK / NDK / cmake / ninja
2. 取源码：git clone 上游 @ v3.2.7 + submodule update --recursive
   → 逐个校验子模块 commit == 上游 gitlink（不匹配直接失败）
3. patch：librime_jni/{rime_jni.cc,key_table.cc,levers.cc,opencc.cc,jni-utils.h} 与
   librime_jni/CMakeLists.txt 中的 com.osfans.trime / Java_com_osfans_trime_ → 新包名
   （pattern 找不到就报错退出，绝不静默跳过；已打过则跳过，幂等）
4. cmake（NDK toolchain，绕开 Gradle）+ ninja 构建目标 rime_jni
5. llvm-strip --strip-unneeded
6. 安装到 app/libs/<abi>/librime_jni.so（或 --out-dir），写 .sha256
7. 调 script/verify-jni-symbols.sh 校验 73 符号 + 无旧包名残留
```

### 实测数据

| 指标 | 值 |
|---|---|
| 冷构建（configure + 357 步编译/链接） | **18–21 s**（20 核 `-j20`；`-j16` 时 19–21 s） |
| 增量复跑 | ≈ 1.4 s |
| 幂等性 | 冷构建 3 次，sha256 全部一致（`fd437255…`） |
| 16 KB 对齐 | 脚本固定注入 `-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"`（`:348` 注释、`:366` 实参） |
| 完整体积 | `.so` 13,587,744 B；构建日志 `app/libs/arm64-v8a/build-jni.log`（239,849 B，含环境探测→pin 校验→patch→configure→357 步→strip→校验→汇总） |

### 两个已知坑（脚本已规避，手工复现时会踩）

| 坑 | 现象 | 规避 |
|---|---|---|
| **`-UBOOST_EXCLUDE_LIBRARIES`** | 第二次 configure 必失败：`Target "boost_log" links to target "Boost::coroutine" but the target was not found`。原因：`BoostRoot.cmake` 把 `BOOST_EXCLUDE_LIBRARIES` 写进 CMakeCache，第二次起 boost 不再创建 `Boost::coroutine`，而 `boost_log` 仍链接它 | configure 时加 `-UBOOST_EXCLUDE_LIBRARIES`（脚本已内置） |
| **boost 1.79 官方 tar 是 b2 布局** | `add_subdirectory(boost)` 报 `does not contain a CMakeLists.txt`；`libs/*/` 下没有 `include/` | 补 superproject 根 `CMakeLists.txt` + 给 143 个 lib 建 `include/boost -> ../../../boost` symlink（脚本自动做） |

依赖偏离与细节见 [reports/BUILD-REPORT-jni.md](reports/BUILD-REPORT-jni.md) §2.1、§7。

## 6. 怎么验证

### 6.1 一键契约校验（首选）

```bash
./script/verify-jni-symbols.sh
# 默认校验 app/libs/arm64-v8a/librime_jni.so，退出码 0 = 通过

# 其他用法
./script/verify-jni-symbols.sh --so /path/to/lib.so --abi arm64-v8a
./script/verify-jni-symbols.sh --src app/src/main --legacy-pkg com.osfans.trime
```

它做三件事：

| # | 动作 | 判定 |
|---|---|---|
| 1 | 扫描 Java `native` + Kotlin `external fun`，按 JNI 规则生成期望符号名 | — |
| 2 | `nm -D --defined-only` 取 `.so` 的 `Java_*` 导出 | — |
| 3 | 比对 | 缺失→**退出码 1**；残留旧包名→**退出码 1**；多余→仅警告 |

**用旧库跑应当 FAILED**（`1b5f1eac…` 那份，符号前缀是 `com_osfans_trime`），这正好构成脚本有效性的反向证明。

### 6.2 手工复核（脚本不可用时）

```bash
SO=app/libs/arm64-v8a/librime_jni.so

file $SO                        # stripped
stat -c%s $SO                   # 13587744
sha256sum $SO                   # fd437255…f03f

nm -D --defined-only $SO | grep -c ' Java_com_hazukieq_hkrime_'   # 73
nm -D --defined-only $SO | grep -c ' Java_com_osfans_trime_'      # 0

readelf -d $SO | grep -E 'NEEDED|SONAME'   # 无 libc++_shared.so；SONAME=librime_jni.so
readelf -n $SO                             # 只有 .note.android.ident（无 GNU build-id）

# 16 KB 页对齐：4 个 LOAD 段应为 0x4000
$HOME/Android/Sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf -l $SO \
  | awk '/LOAD/{print $NF}'                # 0x4000 ×4

strings $SO | grep -E '1\.7\.3-74|v3\.2\.7-2026|ver\.1\.1\.4|com\.hazukieq\.hkrime'
```

### 6.3 APK 内校验

```bash
APK=../artifacts/hkrime-debug.apk
unzip -l $APK | grep 'lib/'                 # 只应有 1 个 lib/arm64-v8a/librime_jni.so（13,587,744）
unzip -p $APK lib/arm64-v8a/librime_jni.so | sha256sum
#   应与 app/libs/arm64-v8a/librime_jni.so 一致：fd437255…f03f

# APK 级 16 KB 对齐（zipalign 校验的是未压缩条目的对齐）
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 4 $APK   # 退出码 0 = 通过
#   注意：`-P 16` 需要 build-tools 35+；34.0.0 的 zipalign 不认这个参数

$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging $APK | head -3
#   package: name='com.hazukieq.hkrime' versionCode='20220701' versionName='3.2.7' minSdk 21 targetSdk 33
```

### 6.4 真机验证（**已通过：仪器测试**）

`.so` 的真机证据不是「能打字」，而是**仪器测试里 `Rime.get(ctx,true)` 成功完成部署并跑通 4 组 OpenCC 转换**——任何符号/包名不匹配都会在那一步抛 `UnsatisfiedLinkError`。

```bash
timeout 300 adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
# 期望：OK (1 test)
```

完整步骤见 [03-build.md](03-build.md) §5、报告 [reports/DEVICE-TEST-REPORT.md](reports/DEVICE-TEST-REPORT.md)。

| 现象 | 判定 |
|---|---|
| `OK (1 test)` | 通过（部署 + ocd2 生成 + 转换 + 方案列表全部正常） |
| `No implementation found for ... Rime.setup` | `.so` 与 Java 包名不匹配（装错库/没重编） |
| `UnsatisfiedLinkError: dlopen failed` | 库缺失或 ABI 不符；`libc++_shared.so not found` 表示用了 shared STL（当前库静态链接，不会出现） |
| 键盘出但候选为空 | 不是 JNI 问题 → 无输入方案，见 06 §4.1 |
| 系统弹「不符合 16 KB 对齐要求」 | 用了未加 `max-page-size=16384` 的旧库，见 §7 |

## 7. 16 KB page 对齐（Android 15/16 必需）

**背景**：Android 15 起系统支持 16 KB 内存页；Android 16 的部分设备（本轮测试机 Xiaomi M2012K11AC / Android 16 就是）在安装/启动时校验 ELF 的 LOAD 段对齐。4 KB 对齐的 `.so` 会被判不合规，弹系统对话框：

> 此应用不符合 16 KB 对齐要求。ELF 文件对齐检查失败。以下库未进行 16 KB 对齐：`lib/arm64-v8a/librime_jni.so: LOAD 区段未对齐`

**修法**：链接时指定最大页大小。

```bash
# script/build-jni-so.sh 里已固定带上（:348 注释、:366 实参）
-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
```

**验证两层**：

```bash
SO=app/libs/arm64-v8a/librime_jni.so
llvm-readelf -l $SO | awk '/LOAD/{print $NF}'          # 期望 0x4000 0x4000 0x4000 0x4000

APK=../artifacts/hkrime-debug.apk
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 4 $APK   # 退出码 0
```

本轮重建后：`librime_jni.so` 13,587,744 B（比 4 KB 版少 80 B）、4 个 LOAD 全 `0x4000`；debug/release 两个 APK 的 `zipalign -c -P 16 4` 均通过；真机 16 KB 弹窗消失。

> debug 包在 Android 16 上还可能弹「Android 应用兼容性…（可调试应用）」提示——那是可调试包的常规提醒；本轮的 16 KB 警告在修复对齐后消失，两者别混为一谈。

## 8. 优化版 `.so`（备选，未采用）

| 项 | 主产物（采用） | 优化版（未采用） |
|---|---|---|
| 路径 | `app/libs/arm64-v8a/librime_jni.so` | `artifacts/librime_jni-release-opt.so`（仓库外） |
| 大小 | 13,587,744 B | **6,065,152 B**（-55%） |
| sha256 | `fd437255…f03f` | `6b6173d10039262a57205efb321d195d72c1e71b568ec8ba0320beaf4696f920` |
| 16 KB 对齐 | 0x4000 ×4 | 0x4000 ×4（本轮同批重建） |
| 构建类型 | Debug（`-O0`） | Release（`-O2`） |
| 复现 | `./script/build-jni-so.sh` | `./script/build-jni-so.sh --build-type Release` |

**为什么默认用 Debug 版**：与仓库原有 13.5 MB 库（同样 `-O0`）**同构**，行为最接近已验证过的历史产物，做集成验证时变量最少。
优化版体积小一半、理论上更快，但**未做真机差异验证**（性能/稳定性/是否命中 librime 的未定义行为），故不冒进。

`TODO:` 若后续要减包体，先做 A/B：同设备同方案下对比启动/按键延迟与稳定性，再决定是否切到 `--build-type Release`。

## 9. 附录：72 个 native 方法清单

按功能分组（`core/Rime.java` 的 `native` 声明，与 `.so` 导出一一对应；另加 1 个 Kotlin OpenCC 方法 = 73）：

| 组 | 方法 |
|---|---|
| 生命周期 | `setup` `initialize` `finalize1` `create_session` `find_session` `destroy_session` `cleanup_stale_sessions` `cleanup_all_sessions` |
| 部署维护 | `start_maintenance` `is_maintenance_mode` `join_maintenance_thread` `deployer_initialize` `prebuild` `deploy` `deploy_schema` `deploy_config_file` `sync_user_data` |
| 输入处理 | `process_key` `commit_composition` `clear_composition` `get_commit` `get_context` `get_status` `get_input` `simulate_key_sequence` |
| 候选 | `select_candidate` `select_candidate_on_current_page` `delete_candidate` `delete_candidate_on_current_page` |
| 选项/属性 | `set_option` `get_option` `set_property` `get_property` |
| 方案 | `get_schema_list` `get_current_schema` `select_schema` `get_available_schema_list` `get_selected_schema_list` `select_schemas` |
| 配置读取 | `config_get_bool/int/double/string` `config_set_bool/int/double/string` `config_list_size` `config_get_list` `config_get_map` `config_get_value` `schema_get_value` `customize_bool/int/double/string` |
| 光标/工具 | `get_caret_pos` `set_caret_pos` `get_modifier_by_name` `get_keycode_by_name` `run_task` |
| 路径 | `get_shared_data_dir` `get_user_data_dir` `get_sync_dir` `get_user_id` |
| 版本 | `get_version` `get_librime_version` `get_trime_version` `get_opencc_version` |
| 回调 | `set_notification_handler` |
| OpenCC | `opencc_convert`（Rime）+ `openccDictConv`（`OpenCCDictManager.kt:15`，第 73 个符号） |
