# INTEGRATION-REPORT — 集成新 JNI `librime_jni.so`（task-3）

- 仓库：`/home/hazukie/Projects/common/trime-workspace/hkrime`，branch `hakka`，origin `https://github.com/hazukieq/trime-without-CMake.git`
- 任务：task-3（集成新 JNI `.so` + 统一 `loadLibrary` + 重建/校验 APK；前置 task-1 交付 `.so`、task-2 完成改名）
- 执行者：renamer
- 状态：**完成**（debug/release 均重建并静态校验通过；符号契约 73/73；未做真机冒烟）
- 未执行 `git commit` / `git push`（由 Lead 统一提交）
- 本报告**取代** `RENAME-REPORT.md` 中关于旧 `.so` / `loadLibrary("rime_jni_1_5")` / 旧 APK 数值的结论；RENAME-REPORT.md 相应段落已就地加注「已被 task-3 取代」。

## 1. 结论摘要

| 项目 | 结果 |
|---|---|
| 新 `.so` 来源 | `artifacts/librime_jni.so`（jni-builder 交付，**Debug `-O0` 主产物**） |
| 新 `.so` 大小 / sha256 | **13,587,824 B** / **`d6b295b6f795cc14ba888c8d9e9b1a69a48ee20d9f772555e89e3db65ca098ec`** |
| 安装位置 | `app/libs/arm64-v8a/librime_jni.so`（覆盖旧 85,786,600 B 废库） |
| 删除的旧库 | `app/libs/arm64-v8a/librime_jni_1_5.so`（13,558,064 B，`git rm`） |
| `loadLibrary` 统一 | `core/Rime.java:238`：`"rime_jni_1_5"` → **`"rime_jni"`**；`data/opencc/OpenCCDictManager.kt:15` 原就是 `"rime_jni"`，未改 |
| 符号契约（`./script/verify-jni-symbols.sh`） | 源码声明 **73** / `.so` 导出 **73** / 缺失 **0** / 残留旧包名 **0** / 多余 **0** → **OK** |
| `.so` 内旧包名 | `com/osfans/trime` **0 处**；`com/hazukieq/hkrime` 10 处（原旧库各 10–11 处） |
| debug APK（正式交付） | `artifacts/hkrime-debug.apk`，**11,418,991 B**，sha256 **`8cc718eb0b90071e2bb514c71dc999baca4de2a48d3a54deb598865a09cce545`** |
| release APK（签名验证用） | `artifacts/hkrime-release.apk`，**9,586,652 B**，sha256 **`9fe31e156084e275612cce1ee285900f1a5fde0033eadb9bd098cfb7353575fe`**，apksigner 通过（C=cn） |
| APK 内 `.so` | 只有 **1 个** `lib/arm64-v8a/librime_jni.so`（条目 13,587,824 B），`unzip -p` 提取后 sha256 **与 `artifacts/librime_jni.so` 完全一致** |
| APK 内旧包名残留 | 15 个 dex 全 0；`resources.arsc` 0；`.so` 0（仅保留上游 URL，同 task-2 §4） |
| 构建 | `:app:assembleDebug` **BUILD SUCCESSFUL in 24s**（clean 后全量）；`:app:assembleRelease` 24s（需 `-x lintVital*`，同 task-2 环境问题） |

## 2. 输入交付物核验（jni-builder → artifacts）

| 文件 | 大小 | sha256 | 用途 |
|---|---|---|---|
| `artifacts/librime_jni.so` | 13,587,824 B | `d6b295b6…ca098ec` | **主产物，已装入 app**（Debug `-O0`，与仓库旧 13.5MB 库同构，size +0.22%） |
| `artifacts/librime_jni-release-opt.so` | 6,065,152 B | `48d0494d15ceca24c7d1a0c094c03a903b2a925391743889a036b540b2684355` | 备选（`-O2`），**未装入 app**；保留在 artifacts 供后续决策 |
| `script/build-jni-so.sh` | 19,219 B | — | jni-builder 产线脚本（我只读引用，未修改） |
| `script/verify-jni-symbols.sh` | 8,905 B | — | 符号契约校验（我执行，未修改） |

装入后 `app/libs/arm64-v8a/librime_jni.so` 实测：

```
$ file app/libs/arm64-v8a/librime_jni.so
ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked,
BuildID[sha1]=707f3101bdb3d87edf387ded0be2ffb43c50c67d, stripped

$ stat -c%s app/libs/arm64-v8a/librime_jni.so
13587824
$ sha256sum app/libs/arm64-v8a/librime_jni.so
d6b295b6f795cc14ba888c8d9e9b1a69a48ee20d9f772555e89e3db65ca098ec

$ nm -D --defined-only app/libs/arm64-v8a/librime_jni.so | grep -c ' Java_com_hazukieq_hkrime_'
73
$ nm -D --defined-only app/libs/arm64-v8a/librime_jni.so | grep -c ' Java_com_osfans_trime_'
0

$ strings app/libs/arm64-v8a/librime_jni.so | grep -c 'com/osfans/trime'
0
$ strings app/libs/arm64-v8a/librime_jni.so | grep -c 'com/hazukieq/hkrime'
10

$ readelf -d app/libs/arm64-v8a/librime_jni.so | grep -E 'NEEDED|SONAME'
 (NEEDED)  共享库：[liblog.so]
 (NEEDED)  共享库：[libz.so]
 (NEEDED)  共享库：[libm.so]
 (NEEDED)  共享库：[libdl.so]
 (NEEDED)  共享库：[libc.so]
 (SONAME)  Library soname: [librime_jni.so]
```

结论：`NEEDED` **不含 `libc++_shared.so`**（C++ 运行时已静态链接进 `.so`），因此**不需要**额外打包 `libc++_shared.so`；`SONAME` 为 `librime_jni.so`，与 `System.loadLibrary("rime_jni")` 的文件名解析一致。

## 3. 变更清单（task-3 净变更 4 项）

| # | 文件 | 变更 | 前 | 后 |
|---|---|---|---|---|
| 1 | `app/libs/arm64-v8a/librime_jni.so` | 覆盖（`cp`） | 85,786,600 B，sha256 `ff510498ca417782cc297627815520473103e862f81c9b038462b938dd138aad`（未 strip 的废库） | 13,587,824 B，sha256 `d6b295b6…ca098ec` |
| 2 | `app/libs/arm64-v8a/librime_jni_1_5.so` | `git rm`（删除） | 13,558,064 B，sha256 `1b5f1eac66ca2897b018471aaeead155b793e91d320fdc27a3f4848f5c10563a` | 不存在 |
| 3 | `app/src/main/java/com/hazukieq/hkrime/core/Rime.java:238` | `sed` 单行 | `System.loadLibrary("rime_jni_1_5");` | `System.loadLibrary("rime_jni");` |
| 4 | `app/src/main/java/com/hazukieq/hkrime/data/opencc/OpenCCDictManager.kt:15` | 无变更（确认） | `System.loadLibrary("rime_jni")` | 同左 |

验证「无其他 `rime_jni_1_5` 引用」（源码/资源/脚本范围，docs 按 Lead 要求不动）：

```
$ grep -rn 'rime_jni_1_5' app/ script/ | grep -v '/build/'
（无输出）
$ git grep -n 'rime_jni_1_5' -- ':!docs' ':!README.md'
（无输出）
```

> `docs/**` 内仍有 `rime_jni_1_5` 的历史描述（`docs/00-overview.md`、`docs/README.md`、`docs/02-jni-so.md`），属文档 owner 范围、且在该处是**历史事实记录**，本任务不改。

其它说明：
- `app/build.gradle`（`jniLibs.srcDirs = ['libs']`、abiFilters `arm64-v8a`）**未改**，无需为单库调整。
- `app/libs_backup/**` **未动**（见 §7）。
- `git status` 中另有 `README.md`、`docs/**` 等改动，非本任务产物（Lead/文档 owner 的工作）。

## 4. 构建记录

环境（与 task-2 相同，未升级任何工具链）：

```
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9
export PATH=$JAVA_HOME/bin:$PATH
# Gradle wrapper 7.5 / AGP 7.4.2 / Kotlin 1.8.20；compileSdk 33 / targetSdk 33 / minSdk 21 / arm64-v8a
```

### 4.1 debug（最终命令）

```bash
cd /home/hazukie/Projects/common/trime-workspace/hkrime
./gradlew :app:clean --no-daemon -q
./gradlew :app:assembleDebug --no-daemon --stacktrace \
  -Dorg.gradle.internal.http.connectionTimeout=120000 \
  -Dorg.gradle.internal.http.socketTimeout=180000
```

结果：`BUILD SUCCESSFUL in 24s`，`34 actionable tasks: 34 executed`，EXIT=0（日志 `/tmp/gradle-task3-debug-clean.log`）。

> 为什么先 `clean`：第一次未 clean 的增量构建虽然也 `BUILD SUCCESSFUL in 12s`，但产出的 APK 为 15,719,610 B，zip 条目压缩总和仅 11,256,944 B，**在 `META-INF/MANIFEST.MF` 之后残留 4,255,777 B 死区**（≈ 被删除的 `librime_jni_1_5.so` 压缩存储 4,234,492 B + 头），判定为 AGP 增量打包未回收已删条目空间。该 APK zipalign/apksigner 仍通过、功能无碍，但白胖 4.25MB，故用 `clean` 全量重建；clean 后 APK 11,418,991 B，overhead 仅 162,035 B（zip 头 + APK Signing Block），死区消失。**该中间产物不交付。**

### 4.2 release（签名配置验证）

```bash
./gradlew :app:assembleRelease --no-daemon \
  -x lintVitalAnalyzeRelease -x lintVitalRelease \
  -Dorg.gradle.internal.http.connectionTimeout=120000 \
  -Dorg.gradle.internal.http.socketTimeout=180000
```

结果：`BUILD SUCCESSFUL in 24s`，`42 actionable tasks: 42 executed`，EXIT=0（日志 `/tmp/gradle-task3-release.log`）。

> `-x lintVital*` 原因同 task-2：AGP 7.4.2 + JDK17 下 `:app:lintVitalAnalyzeRelease` 会抛 `java.lang.reflect.InvocationTargetException`（`SimplePlatformLookup.platformFromSourceProp`），属工具链环境问题，非本次改动引入；按要求不升级 AGP/Gradle。

## 5. APK 校验

### 5.1 debug APK badging

```
$ $SDK/build-tools/33.0.1/aapt2 dump badging artifacts/hkrime-debug.apk | grep -E '^package:|sdkVersion|targetSdkVersion|application-label|native-code'
package: name='com.hazukieq.hkrime' versionCode='20220701' versionName='3.2.7'
        platformBuildVersionName='13' platformBuildVersionCode='33'
        compileSdkVersion='33' compileSdkVersionCodename='13'
sdkVersion:'21'
targetSdkVersion:'33'
application-label:'Ftrime'
native-code: 'arm64-v8a'
```

### 5.2 native lib 与 sha256 闭环

```
$ unzip -v artifacts/hkrime-debug.apk | grep '\.so$'
13587824  Defl:N  4236042  69%  1981-01-01 01:01  06265f4f  lib/arm64-v8a/librime_jni.so
```

APK 内 **只有 1 个** `.so`（旧 `librime_jni_1_5.so` 已不在包内）；条目 Length 13,587,824 B = 文件系统大小（新库本就已 strip，AGP `stripDebugDebugSymbols` 未再改变它）。

```
$ unzip -p artifacts/hkrime-debug.apk lib/arm64-v8a/librime_jni.so > /tmp/x.so
$ sha256sum /tmp/x.so artifacts/librime_jni.so
d6b295b6f795cc14ba888c8d9e9b1a69a48ee20d9f772555e89e3db65ca098ec  /tmp/x.so
d6b295b6f795cc14ba888c8d9e9b1a69a48ee20d9f772555e89e3db65ca098ec  artifacts/librime_jni.so
```

→ APK 内 `.so` 与交付物 **逐字节一致**（`unzip -p` 解压后比对，规避 zipalign/压缩误差）。`.so` 内 `com/osfans/trime` 0 处、`com/hazukieq/hkrime` 10 处（对 APK 内提取的那份复测，结果同上）。

### 5.3 dex / resources 旧包名

```
$ for d in $(unzip -Z1 artifacts/hkrime-debug.apk | grep -oE 'classes[0-9]*\.dex'); do
    unzip -p artifacts/hkrime-debug.apk "$d" | strings | grep -c 'com/osfans/trime'; done
15 个 dex → 全部 0（classes.dex、classes2..15.dex）
$ unzip -p artifacts/hkrime-debug.apk resources.arsc | strings | grep -c 'com\.osfans\.trime'
0
```

APK 内非 dex/so 的旧包名残留仍仅上游外部 URL（与 RENAME-REPORT.md §4 一致，未变化）。

### 5.4 zip 结构 / 对齐 / 签名

```
debug APK:   size=11,418,991  entries=935  compress_sum=11,256,956  overhead=162,035（zip 头 + APK Signing Block）
release APK: size=9,586,652   entries=925  compress_sum=9,469,886   overhead=116,766
```

```
$ $SDK/build-tools/33.0.1/zipalign -c 4 artifacts/hkrime-debug.apk && echo OK
OK
$ $SDK/build-tools/33.0.1/apksigner verify artifacts/hkrime-debug.apk && echo SIGN_OK
SIGN_OK   # 仅输出 META-INF/*.version 等 "not protected by signature" 提示，属 AGP 产物常态
```

### 5.5 release APK

```
package: name='com.hazukieq.hkrime' versionCode='20220701' versionName='3.2.7'
sdkVersion:'21' targetSdkVersion:'33' application-label:'Ftrime' native-code: 'arm64-v8a'

lib/arm64-v8a/librime_jni.so  13,587,824 B（Deflate 后 3,689,207 B）
提取 sha256 = d6b295b6…ca098ec（与 artifacts 一致）；.so 内旧包名 0 处
dex（2 个）：classes.dex 0、classes2.dex 0；resources.arsc 0
zipalign -c 4 → OK
apksigner verify --print-certs → Signer #1 certificate DN: C=cn
                                 SHA-256: 32208d4775841c37530d755f93eff86f79602081663f8221d3130e868817e860
```

release 的 `.so` 条目经 R8/资源处理未变（13,587,824 B、CRC 与 debug 相同），压缩率 73% vs debug 69%——zip 压缩器对同一份二进制给出不同压缩尺寸属正常（release 打包并发/块大小差异），**解压后字节完全相同**。

## 6. 体积对比（task-2 旧库 → task-3 新库）

| 项 | task-2（旧双库） | task-3（新单库） | 变化 |
|---|---|---|---|
| `app/libs/` 内 `.so` 总量 | 85,786,600 + 13,558,064 = **99,344,664 B** | **13,587,824 B** | −85,756,840 B（−86.3%） |
| debug APK | 15,804,283 B | **11,418,991 B** | −4,385,292 B（−27.75%） |
| release APK | 13,407,731 B | **9,586,652 B** | −3,821,079 B（−28.50%） |
| APK 内 `.so` 条目数 | 2 | **1** | −1 |
| APK 内 `.so` 解压总量 | 13,931,184 + 13,558,064 = 27,489,248 B | **13,587,824 B** | −13,901,424 B |
| APK 内 `.so` 压缩存储 | 4,386,617 + 4,234,492 = 8,621,109 B | **4,236,042 B**（debug）/ 3,689,207 B（release） | −4,385,067 B（debug） |

> APK 体积下降以「去掉 85MB 废库被 strip 后的 13.9MB + 去掉重复 1_5 库 13.5MB + 换入单个 13.5MB 新库」为主；`.so` 压缩率 ~69% 是本次 APK 的最大占用项（debug APK 中占 37%）。

## 7. `app/libs_backup/**` 说明（未动，按 Lead 指示）

| 文件 | 大小 | sha256 | 状态 |
|---|---|---|---|
| `libs_backup/arm64-v8a/librime_jni.so` | 13,558,064 B | `1b5f1eac66ca2897b018471aaeead155b793e91d320fdc27a3f4848f5c10563a` | git 跟踪中，**未修改** |

- 该文件与被 `git rm` 的 `app/libs/arm64-v8a/librime_jni_1_5.so` **逐字节相同**（`cmp` 验证 IDENTICAL），是上游 v3.2.7 时代那个「正确但旧包名」的库的历史副本。
- 它**不参与构建**（`jniLibs.srcDirs = ['libs']` 只收 `app/libs`），不影响 APK；但仓库因此仍保留 13.5MB 旧二进制、内含 10 处 `com/osfans/trime`（`strings` 计数）。建议后续清理（见 §9）。

## 8. 与 `RENAME-REPORT.md` 的差异（已就地更新）

| RENAME-REPORT.md 位置 | 原结论（task-2） | task-3 后 |
|---|---|---|
| §1 表「旧包名残留位置」 | 2 个旧 JNI `.so`（10–11 处） | `.so` 内 0 处；仅剩上游 URL |
| §1 表 debug/release APK | 15,804,283 / 13,407,731 B | 11,418,991 / 9,586,652 B |
| §4 表旧 `.so` 行 | 旧库各 10–11 处 | 已替换，加注「已被 task-3 取代」 |
| §5.1 JNI 段 | `loadLibrary("rime_jni_1_5")` 未动 + 运行时会崩 | 已统一 `rime_jni`；风险消除，仅缺真机冒烟 |
| §7.4 native libs 口径 | 两个旧库 | 加注 task-3 最终口径（单库、sha256 闭环） |
| §7.6 交付物校验和 | 旧 APK 数值 | 已替换为新 APK 数值（保留旧值作历史） |
| §8 遗留风险/交接 | task-3 待办清单 | 标记已完成并指向本报告 |

## 9. 遗留风险

1. **未真机运行验证（唯一实质缺口）**：本次只做静态校验（符号/哈希/包名/对齐/签名）。JNI 真正的成功判据是运行期：装 APK → 在系统设置启用并选择「Ftrime」输入法 → 触发 Rime 初始化/部署 → `adb logcat` 确认无 `UnsatisfiedLinkError` / `ClassNotFoundException` / `dlopen` 失败。建议 Lead 询问用户是否冒烟（真机 arm64-v8a、minSdk 21+）。
2. **`libs_backup/arm64-v8a/librime_jni.so` 仍留在仓库**（13.5MB，旧包名 10 处，不参与构建）：建议后续任务删除或移出 git（历史可回溯，不必留工作树副本）。
3. **release 构建依赖 `-x lintVitalAnalyzeRelease -x lintVitalRelease`**：AGP 7.4.2 + JDK17 的 lint 崩溃问题未修（按要求不升 AGP）；上 CI 需同样规避或单独修 lint 环境。
4. **签名材料仍在仓库**：`skeys.jks` + `release.properties` 明文密码（RENAME-REPORT.md §5.6 已记录）；本次未处理，勿用该密钥正式分发。
5. **`docs/**` 仍描述旧 `rime_jni_1_5` 状态**（`docs/README.md`、`docs/02-jni-so.md`、`docs/00-overview.md`）：属文档 owner 范围，本任务按边界未改。
6. **ABI 只有 arm64-v8a**（上游配置，未变）：x86_64 模拟器无法运行本 APK。
7. **数据不迁移**：新包名 = 全新应用，旧 `com.osfans.trime`/`com.tumuyan.trime` 的用户数据、方案、剪贴板历史不会自动继承（同 RENAME-REPORT.md §5.4）。

## 10. 复现命令（全量）

```bash
set -euo pipefail
REPO=/home/hazukie/Projects/common/trime-workspace/hkrime
ART=/home/hazukie/Projects/common/trime-workspace/artifacts
SDK=/home/hazukie/Android/Sdk
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9
export PATH=$JAVA_HOME/bin:$PATH

# 1) 集成新 .so
cd "$REPO"
cp "$ART/librime_jni.so" app/libs/arm64-v8a/librime_jni.so
git rm app/libs/arm64-v8a/librime_jni_1_5.so
sed -i 's/System\.loadLibrary("rime_jni_1_5")/System.loadLibrary("rime_jni")/' \
  app/src/main/java/com/hazukieq/hkrime/core/Rime.java

# 2) 符号契约 + 干净重建
./script/verify-jni-symbols.sh
./gradlew :app:clean --no-daemon -q
./gradlew :app:assembleDebug --no-daemon \
  -Dorg.gradle.internal.http.connectionTimeout=120000 \
  -Dorg.gradle.internal.http.socketTimeout=180000
./gradlew :app:assembleRelease --no-daemon \
  -x lintVitalAnalyzeRelease -x lintVitalRelease

# 3) 交付 + 校验
cp app/build/outputs/apk/debug/trime-3.2.7-debug.apk   "$ART/hkrime-debug.apk"
cp app/build/outputs/apk/release/trime-3.2.7-release.apk "$ART/hkrime-release.apk"
sha256sum "$ART/hkrime-debug.apk" "$ART/hkrime-release.apk"
"$SDK/build-tools/33.0.1/aapt2" dump badging "$ART/hkrime-debug.apk" | head -3
unzip -v "$ART/hkrime-debug.apk" | grep '\.so$'
unzip -p "$ART/hkrime-debug.apk" lib/arm64-v8a/librime_jni.so | sha256sum
unzip -p "$ART/hkrime-debug.apk" resources.arsc | strings | grep -c 'com\.osfans\.trime'
"$SDK/build-tools/33.0.1/zipalign" -c 4 "$ART/hkrime-debug.apk" && echo ZIPALIGN_OK
"$SDK/build-tools/33.0.1/apksigner" verify "$ART/hkrime-debug.apk" >/dev/null && echo SIGN_OK
```

## 附录 A：`verify-jni-symbols.sh` 完整输出

```
== verify-jni-symbols ==
  repo      : /home/hazukie/Projects/common/trime-workspace/hkrime
  so        : /home/hazukie/Projects/common/trime-workspace/hkrime/app/libs/arm64-v8a/librime_jni.so
  src root  : /home/hazukie/Projects/common/trime-workspace/hkrime/app/src/main
  abi       : arm64-v8a
  legacy pkg: com.osfans.trime
# parsed_files=2 parsed_decls=73

== 统计 ==
  源码声明（去重）    : 73
  .so 导出 Java_*     : 73
  缺失                : 0
  残留旧包名          : 0  (prefix: Java_com_osfans_trime_)
  多余（源码未声明）  : 0

OK: JNI 符号契约一致（73 个声明全部导出，无旧包名残留）
```

## 附录 B：构建日志位置

| 日志 | 内容 |
|---|---|
| `/tmp/gradle-task3-debug.log` | 第一次未 clean 的增量 debug 构建（12s，产出含 4.25MB 死区的中间 APK，不交付） |
| `/tmp/gradle-task3-debug-clean.log` | **最终 debug 构建**（clean 后 24s，34 tasks，EXIT=0） |
| `/tmp/gradle-task3-release.log` | **最终 release 构建**（24s，42 tasks，EXIT=0） |
| `/tmp/verify-jni.txt` | 符号契约校验输出 |
| `/tmp/integration-checks.txt` | 本报告 §2/§5 各项校验命令的原始输出汇总 |

> 注：`/tmp` 为临时目录，若需长期留存请由 Lead 复制进 artifacts（本次未复制，避免产物膨胀）。
