# 03 · 构建与测试

> 覆盖三条链路：**编 APK**（日常）、**编 native `.so`**（改包名/升级 librime/换对齐参数时）、**跑仪器测试**（验证 Rime 数据链路）。
> 基线 HEAD `bebad9f`（`dev` 分支）。所有命令在仓库根 `trime-workspace/hkrime/` 下执行。

## 目录

1. [环境要求](#1-环境要求)
2. [签名配置（release 必读）](#2-签名配置release-必读)
3. [编 APK（最常用）](#3-编-apk最常用)
4. [编 native `.so`](#4-编-native-so)
5. [仪器测试（真机）](#5-仪器测试真机)
6. [产物与校验](#6-产物与校验)
7. [常见报错](#7-常见报错)
8. [代码风格与格式化](#8-代码风格与格式化)

---

## 1. 环境要求

### 1.1 版本矩阵（本机实测）

| 组件 | 要求 | 本机值 | 说明 |
|---|---|---|---|
| JDK | **17** | `$HOME/opt/jdk17/jdk-17.0.9`（`openjdk 17.0.9 / JBR-17.0.9+7`） | **不要用系统默认的 JDK 21**：AGP 7.4.2 + Gradle 7.5 未适配 21 |
| Gradle | 7.5 | wrapper 自动下载，`~/.gradle/wrapper/dists/gradle-7.5-bin` 已缓存 | `gradle/wrapper/gradle-wrapper.properties:3` |
| AGP | 7.4.2 | `build.gradle:37` | |
| Kotlin | 1.8.20 / 插件 1.7.20 | `build.gradle:10,53` | |
| compileSdk | 33 | `app/build.gradle:5` | |
| NDK | 24.0.8215888 | `/home/hazukie/Android/Sdk/ndk/24.0.8215888` | `app/build.gradle:6` **硬编码**，版本不存在会直接失败 |
| CMake | 3.22.1 | `$ANDROID_HOME/cmake/3.22.1/bin/cmake` | 仅编 `.so` 用；Gradle 侧不编 C++ |
| Ninja | 1.10.2 | 随 SDK cmake 附带 | 同上 |
| SDK | platform 33 + build-tools **35+** | `/home/hazukie/Android/Sdk`（`local.properties` 的 `sdk.dir`） | build-tools 35+ 才有 `zipalign -P 16`（本机 36.0.0） |
| 测试设备 | Android 15+（16 KB page）可选 | Xiaomi M2012K11AC / Android 16 / serial `5f08bc20` | 16 KB 对齐问题只在 16 KB page 设备暴露 |

```bash
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"
export PATH="$JAVA_HOME/bin:$PATH"
java -version    # 期望：openjdk version "17.0.9"
```

> `local.properties` 不入库（`.gitignore:38`），新机器需自己写 `sdk.dir=/path/to/Android/Sdk`。

### 1.2 三个容易被忽略的前置

| 前置 | 为什么 |
|---|---|
| **git 可用且在仓库内** | `app/build.gradle:94-143` 的 `getBuildInfo/getCommitInfo/getGitBranch…` 靠执行 `git …` 取 BUILD_INFO（写进 `BuildConfig`）。纯拷贝目录（无 `.git`）可能取不到信息 |
| **`release.properties`（可选）** | release 签名配置的**第一优先级**来源；缺失不会再让构建失败，详见 §2 |
| **`adb` 与设备授权** | 仅跑仪器测试需要；重装 APK 会重置「所有文件访问」授权，必须重新 `appops set` |

---

## 2. 签名配置（release 必读）

**历史问题**：早期仓库把 `skeys.jks` + `release.properties`（含明文口令 `263909`）直接提交进版本库。`bebad9f` 已把两者从当前树删除（`git rm --cached` + 删文件），改为「密钥在仓库外 + 三级查找」。

### 2.1 查找顺序（`app/build.gradle:45-79`）

| 优先级 | 来源 | 用途 |
|---|---|---|
| ① | 仓库根 `release.properties` | 本地临时覆盖（该文件已 gitignore） |
| ② | `~/.android-keys/hkrime-release.properties` | **推荐**，由 `script/gen-keystore.sh` 生成 |
| ③ | 环境变量 `HKREME_STORE_FILE` / `HKREME_STORE_PASSWORD` / `HKREME_KEY_ALIAS` / `HKREME_KEY_PASSWORD` | CI/临时构建 |
| — | 都没有 | **release 不签名**（Gradle 打 `logger.lifecycle` 提示），产物是未签名 APK，不能直接装 |

### 2.2 首次生成密钥

```bash
./script/gen-keystore.sh
# 产物：~/.android-keys/hkrime-release.jks        （2048-bit RSA，10000 天，600）
#       ~/.android-keys/hkrime-release.properties （600，目录 700）

# 其他用法
./script/gen-keystore.sh --alias hkrime --dir ~/.android-keys
./script/gen-keystore.sh --force    # 覆盖已有密钥——危险：已发布版本无法再升级
```

脚本 DN：`CN=hkrime, OU=hazukieq, O=hkrime-fork, L=Guiping, ST=Guangxi, C=CN`。
`release.properties.example`（7 行）是占位模板，说明「复制到 `~/.android-keys/` 或仓库根并改口令」。

**密钥一旦丢失，已发布版本就无法覆盖升级**（签名不同 Android 拒绝安装），务必自行备份 `hkrime-release.jks`。

### 2.3 用环境变量签名（CI 形态）

```bash
export HKREME_STORE_FILE="$HOME/.android-keys/hkrime-release.jks"
export HKREME_STORE_PASSWORD='…'
export HKREME_KEY_ALIAS=hkrime
export HKREME_KEY_PASSWORD='…'
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
```

> 旧密钥仍留在 git 历史里（`92c7aa5`/`4af2a94` 引入），彻底清除需改写历史 + force push；风险评估见 [05](05-branches-release.md) §6。

---

## 3. 编 APK（最常用）

```bash
cd /home/hazukie/Projects/common/trime-workspace/hkrime
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"

# 1) Debug（日常开发；自动用 debug key 签名，可直接装）
./gradlew :app:assembleDebug
#    产物：app/build/outputs/apk/debug/trime-3.2.7-debug.apk

# 2) Debug + 测试 APK（跑仪器测试前必须两个都编）
./gradlew assembleDebug assembleDebugAndroidTest
#    产物：.../debug/trime-3.2.7-debug.apk
#          .../androidTest/debug/trime-3.2.7-debug-androidTest.apk

# 3) Release（正式包；注意 lint 绕过，见下）
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
#    产物：app/build/outputs/apk/release/trime-3.2.7-release.apk

# 4) 只做静态检查（跑 lint，不打包）
./gradlew :app:lintDebug

# 5) 清干净重来
./gradlew clean
```

### 为什么 release 要 `-x lintVital…`

本环境 `lintVitalAnalyzeRelease` 报错（AGP 7.4.2 + JDK 17 的 lint 环境问题，与本项目代码无关），
`lintVitalRelease` 依赖它，故一并跳过，否则 `assembleRelease` 直接失败。
副作用：**release 包未经过 lint 检查**，属已知欠债（见 [06](06-troubleshooting-roadmap.md) §5）。

### APK 名与路径（易错）

| 名称 | 值 |
|---|---|
| Gradle 产物名 | **`trime-3.2.7-debug.apk`**（`archivesBaseName = "trime-$versionName"`，`app/build.gradle:14`） |
| 完整路径 | `app/build/outputs/apk/debug/trime-3.2.7-debug.apk` |
| 测试 APK | `app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk` |
| 对外交付名 | `artifacts/hkrime-debug.apk`（人工改名后的拷贝，仓库外） |

> ⚠ 不是 `app-debug.apk`。文档与脚本里出现 `app-debug.apk` 的地方都是错的。

### 为什么不再需要编 C++

本仓库是 **without-CMake** fork：native 库以**预编译 `.so` 提交在 `app/libs/arm64-v8a/`**，
`app/build.gradle:61-65` 用 `sourceSets.main.jniLibs.srcDirs = ['libs']` 直接打包。
所以 `assembleDebug` **不触发** NDK 编译，NDK 只是 `ndkVersion` 声明（版本仍需存在，否则配置期校验失败）。

---

## 4. 编 native `.so`

只有四种情况需要：**改包名**、**升级/降级 librime**、**改 patch 逻辑**、**改页对齐/优化参数**。

```bash
# 默认流程（clone 上游 v3.2.7 → 校验 pin → patch → cmake+ninja → strip → 安装 → 校验）
./script/build-jni-so.sh

# 已 clone 过源码（离线、最快）：复用源码树
./script/build-jni-so.sh --src-dir ../jni-build/trime-v3.2.7

# 强制从头（清源码树与构建目录；实测冷构建 18–21s）
./script/build-jni-so.sh --clean

# 出 -O2 优化版（约 6 MB，默认不装入 app）
./script/build-jni-so.sh --build-type Release --out-dir /tmp/opt
```

脚本已固定注入 **16 KB 页对齐**：`-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"`（`:348` 注释、`:366` 实参）。
Android 15/16 的 16 KB page 设备会拒装 4 KB 对齐的 `.so`，详见 [02 §7](02-jni-so.md)。

完整参数表、内部 7 步流程、`-UBOOST_EXCLUDE_LIBRARIES` 坑，见 [02-jni-so.md](02-jni-so.md) §5。
构建完成后**必须**跑校验：

```bash
./script/verify-jni-symbols.sh
# OK: JNI 符号契约一致（73 个声明全部导出，无旧包名残留）
```

> 关键约束：**.so 与 Java 包名必须同批**。改了 `applicationId`/`namespace` 而不重编 `.so`，
> 运行期必 `UnsatisfiedLinkError`（旧库全部按 `com.osfans.trime` 编）。

---

## 5. 仪器测试（真机）

用例：`app/src/androidTest/java/com/hazukieq/hkrime/RimeDataTest.kt`（111 行，`@RunWith(AndroidJUnit4::class)`，单用例 `deployAndOpenccWorks()`）。

### 5.1 它验证什么

| # | 断言 |
|---|---|
| 1 | `Config.get(ctx)` + `Rime.destroy()/Rime.get(ctx,true)` 不崩（JNI/符号/包名全链路） |
| 2 | sharedDataDir 已部署 `trime.yaml`、`default.custom.yaml`、`hakka_jiugong.schema.yaml`、`hakka.dict.yaml`、`hakka_oftenwords.dict.yaml` |
| 3 | `opencc/` 下 `.ocd2` ≥15 且存在 `STCharacters.ocd2` |
| 4 | userDataDir `build/` 有 `.bin` 且含 `hakka.table.bin` |
| 5 | `Rime.get_opencc_version()` 非空；`s2t/t2s/s2tw/s2twp` 四组转换结果正确 |
| 6 | `Rime.getSchemaNames()` 非空 |

> 注意第 4 项：`build/` 在 **userDataDir**，不在 sharedDataDir。默认两者都指 `.../files/rime`；但用户若在设置里把 user 数据目录改到 `/sdcard/rime`，`build/` 就在那里。

### 5.2 完整步骤

```bash
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"

# 1) 编 app + 测试 APK
./gradlew assembleDebug assembleDebugAndroidTest

# 2) 安装（测试 APK 需要 -t）
adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk

# 3) 授予「所有文件访问」——每次重装都会被重置，必须重设
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow

# 4) 跑（必须套 timeout）
timeout 300 adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
# 期望最后一行：OK (1 test)
```

**为什么要 `timeout`**：权限被拒时，`Config.setTheme()`（`Config.java:292`）→ `init()`（`:355`）→ 异常分支里又调 `setTheme(defaultName)`（`:395`），形成**无限递归**，`am instrument` 会一直不返回。这不是测试的 bug，是真实应用的缺陷路径（见 06 §4.2）。

### 5.3 真机结果（2026-11-08）

`OK (1 test)`。实测：sharedDataDir `/storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime`、userDataDir `/storage/emulated/0/rime`、opencc 44 条目 / 15 个 ocd2、OpenCC `ver.1.1.4`、`s2t 汉字→漢字`、`t2s 漢字→汉字`、`s2tw 软件→軟件`、`s2twp 软件→軟體`、`hakka.table.bin` 生成。详见 [reports/DEVICE-TEST-REPORT.md](reports/DEVICE-TEST-REPORT.md)。

### 5.4 测试前必看：别动用户的 `/sdcard/rime`

若设备上已有真实 RIME 数据（`/sdcard/rime` 含 `*.userdb`、`backup/`），测试会往里写 `build/`。本轮测试前就发现该目录是用户真实数据，先 `mv` 备份、测完恢复。**操作前先 `adb shell ls /sdcard/rime` 确认，必要时备份。**

---

## 6. 产物与校验

### 6.1 定稿值（2026-11-08 第二轮）

| 项 | Debug APK | Release APK |
|---|---|---|
| 路径 | `artifacts/hkrime-debug.apk` | `artifacts/hkrime-release.apk` |
| 大小 | **17,233,842 B** | **11,004,041 B** |
| sha256 | `d0e6159c79844e465d22cf2b80834246e47d9c81dd344a0de226dd7b6917aac7` | `b2e7b748d88b87eeded4aab2dac95724b19a264080196768795ae0f5b4b0426e` |
| package | `com.hazukieq.hkrime` | 同左 |
| versionCode / versionName | `20220701` / `3.2.7` | 同左 |
| minSdk / targetSdk | 21 / 33 | 同左 |
| 签名 | debug key | 新 keystore（证书 SHA-256 `d9d7d91a…93cb`） |
| native-code | `arm64-v8a`，仅 1 个 `librime_jni.so`（13,587,744 B） | 同左 |

> 体积比上一轮（11.4 MB / 9.6 MB）大，原因：本轮恢复了 29 个 OpenCC 文件 + 并入 16 个客家话文件（`2b50c1f`，+269,435 行）。

### 6.2 校验命令

```bash
APK=artifacts/hkrime-debug.apk
AAPT2=$ANDROID_HOME/build-tools/36.0.0/aapt2   # 本机只有 aapt2 可用，aapt 无输出

unzip -l  "$APK" | grep 'lib/'            # 期望：只有 1 行 librime_jni.so
unzip -p  "$APK" lib/arm64-v8a/librime_jni.so | sha256sum
#   应与 app/libs/arm64-v8a/librime_jni.so 一致：fd437255…f03f

$AAPT2 dump badging "$APK" | head -3
#   package: name='com.hazukieq.hkrime' versionCode='20220701' versionName='3.2.7'
#   minSdkVersion:'21'  targetSdkVersion:'33'

# 16 KB 页对齐（构建产物级）
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 4 "$APK"   # 退出码 0

# 签名证书
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs artifacts/hkrime-release.apk
#   DN: CN=hkrime, OU=hazukieq, O=hkrime-fork, L=Guiping, ST=Guangxi, C=CN
#   SHA-256: d9d7d91ae5e7c789ff79b8eb9a1644461e5b7b2c48b52a184d993a90594693cb
```

### 6.3 安装与手工冒烟

```bash
adb install -r artifacts/hkrime-debug.apk
adb logcat -c && adb logcat | grep -iE 'UnsatisfiedLink|dlopen|AndroidRuntime'
# 手动：设置→输入法→启用 hkrime → 选择 → 点输入框 → 打字 → 看候选
```

系统级冒烟（启用/选择/上屏）本轮**尚未做**，仪器测试只覆盖应用内数据链路——见 06 路线图。

---

## 7. 常见报错

| 报错 / 现象 | 原因 | 解法 |
|---|---|---|
| `Unsupported class file major version 65` / Kotlin 编译崩 | 用了 JDK 21 | `export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9` |
| `IllegalAccessError: class com.google.googlejavaformat.java... cannot access class com.sun.tools.javac...` | google-java-format 在 JDK 17 下需 `--add-exports` | `gradle.properties:7-12` 已含 5 个 `--add-exports jdk.compiler/...=ALL-UNNAMED`，确认没被覆盖删除 |
| `NDK not configured. Download it with SDK manager.` / 找不到 24.0.8215888 | `ndkVersion` 硬编码，本机未装 | SDK Manager 装 NDK `24.0.8215888`，或改 `app/build.gradle:6`（不建议，会引入差异） |
| `SDK location not found` | 缺 `local.properties` | 写 `sdk.dir=/home/hazukie/Android/Sdk` |
| `assembleRelease` 在 `lintVitalAnalyzeRelease` 失败 | 环境 lint 问题 | 加 `-x lintVitalAnalyzeRelease -x lintVitalRelease` |
| release 产物未签名 / 装不上 | 三级密钥来源都没有 | `./script/gen-keystore.sh`，或设 `HKREME_*` 环境变量，或放仓库根 `release.properties` |
| `Could not read store file` / `Keystore was tampered` | 密钥路径错或口令错 | 检查 `~/.android-keys/hkrime-release.properties` 里的 `storeFile` 与口令；与 05 §6 的密钥说明对齐 |
| `make opencc-data` 失败（`No such file: app/src/main/jni/OpenCC`） | `jni/` 被 `.gitignore:14` 忽略，仓库内无此目录 | 不要用 Makefile 的 opencc 目标；OpenCC 词典已随 assets 提供（`assets/rime/opencc/`），见 [07](07-opencc-and-rime-data.md) |
| `make debug` / `make release` 失败 | Makefile 是上游遗产，`spotlessCheck` + `opencc-data` 前置在当前环境不成立 | 直接用 `./gradlew`，见 §3 |
| `am instrument` 卡住不返回 | 权限被拒触发 `Config.setTheme`↔`init` 无限递归 | 重设 `appops ... MANAGE_EXTERNAL_STORAGE allow`；命令套 `timeout 300` |
| 系统弹「此应用不符合 16 KB 对齐要求」 | `.so` 的 LOAD 段按 4 KB 对齐 | 用带 `max-page-size=16384` 的脚本重建；见 02 §7 |
| 装机后输入法「启用」列表里没有 hkrime | 包名变了 / 未重装 | 卸载旧包后重装；见 06 §2 |
| 键盘能出、候选永远空 | 没有可用输入方案（不是构建问题） | 见 06 §4.1、07 §8 |

---

## 8. 代码风格与格式化

`build.gradle:63-77` 用 **spotless 6.3.0**：

| 目标 | 规则 |
|---|---|
| `app/src/main/java/com/hazukieq/hkrime/**/*.java` | google-java-format **1.15.0** |
| `app/src/main/java/com/hazukieq/hkrime/**/*.kt` | ktlint **0.44.0** |

```bash
./gradlew spotlessCheck     # 检查（CI 语义）
./gradlew spotlessApply     # 自动修复
```

> spotless 的 target 已随改名更新为 `com/hazukieq/hkrime/...`；
> 若新增了包目录，记得同步改 `build.gradle:63-77`，否则新文件不被检查。
