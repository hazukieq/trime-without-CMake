# 06 · 排障与路线图

> 排障按「**先分层定位，再对症下药**」组织。所有命令都在仓库根或 adb shell 中可直接执行。
> 基线 HEAD `bebad9f`（`dev` 分支，2026-11-08 第二轮）。

## 目录

1. [四层定位法](#1-四层定位法)
2. [症状速查表](#2-症状速查表)
3. [诊断命令集](#3-诊断命令集)
4. [典型故障详解](#4-典型故障详解)
5. [技术债与路线图](#5-技术债与路线图)

---

## 1. 四层定位法

```text
L1 构建层   能不能编出来？        → gradlew / 环境 / NDK / 签名
L2 装载层   .so 能不能加载？      → UnsatisfiedLinkError / dlopen / 符号 / 页对齐
L3 运行层   键盘能不能出？        → IME 启用/选择、service 绑定、权限、递归卡死
L4 数据层   能不能出字？          → 方案、词典、OpenCC、部署、用户目录
```

从下往上排除，**不要跳层**：候选为空是 L4，但如果是 L2 崩了也会表现成「键盘空白」。

| 层 | 一句话判据 |
|---|---|
| L1 | `./gradlew :app:assembleDebug` 成功 |
| L2 | `./script/verify-jni-symbols.sh` → `OK`，且 logcat 无 `UnsatisfiedLinkError`；`llvm-readelf -l` 的 LOAD 全 `0x4000` |
| L3 | 系统设置里已启用 hkrime 且为当前输入法；`appops` 有「所有文件访问」 |
| L4 | `Rime.get()` 后有 session；`RimeDataTest` 通过 |

---

## 2. 症状速查表

| 症状 | 最可能层 | 原因 | 处置 |
|---|---|---|---|
| 装了但「输入法」列表里没有 hkrime | L3 | 包名变化后系统仍记旧 IME id；或未声明 service | 卸载重装 → 设置里重新启用；查 `dumpsys input_method` |
| 能启用、点输入框弹「hkrime 已停止运行」 | L2 | `.so` 与 Java 包名不匹配（装错库/没重编） | `verify-jni-symbols.sh`；`logcat \| grep UnsatisfiedLink` |
| `dlopen failed: library "libc++_shared.so" not found` | L2 | 用了 shared STL 且未打包 | 当前库是 `c++_static`（NEEDED 无 libc++_shared）；若出现说明换过库 → 重编 |
| 系统弹「**此应用不符合 16 KB 对齐要求**」 | L2 | `.so` 的 LOAD 段按 4 KB 对齐（`0x1000`） | 用脚本的 `-Wl,-z,max-page-size=16384` 重建；见 [02 §7](02-jni-so.md)、§4.6 |
| 键盘出得来，候选永远空 | L4 | 方案缺失 / `schema_list` 里的项没有对应文件（当前 `hakka_qwerty`、`ipa_yunlong` 即如此） | 见 §4.1、[07 §8](07-opencc-and-rime-data.md) |
| 方案菜单有项，点开没候选 | L4 | 该项无 `.schema.yaml`（缺料） | 补文件或从 `default.custom.yaml` 删该项 |
| 「情」「联」开关无效 / 部署告警 | L4 | `ywz.json`、`lenth.json` 缺失 | 补 OpenCC 配置或删开关（07 §8） |
| `` ` `` 反查没结果 | L4 | `luna_pinyin` 词典缺失 | 补 `luna_pinyin.*`（04 §6） |
| 简繁转换无效 | L4 | opencc 资源缺失 / ocd2 未生成 / 无存储权限 | 查 `opencc/*.ocd2` 数量（应 15）；见 07 §10 |
| 首次启动卡在权限页，拒绝后循环弹 | L3 | `LauchAct.requestPermission()` 无退出路径 | 手动去系统设置授予「所有文件访问」+「悬浮窗」，或清 App 数据重启 |
| **`am instrument` 卡住不返回** | L3/L4 | 权限被拒触发 `Config.setTheme`↔`init` 无限递归（§4.2） | 先 `appops set` 授权；命令套 `timeout 300` |
| 装 debug 包时弹「Android 应用兼容性…（可调试应用）」 | — | Android 16 对 **debug 包**的常规提醒 | 非 bug；release 包不会弹；16 KB 弹窗与之不同（见 §4.6） |
| 词库不生效 / 部署没反应 | L4 | `Config` 只在包版本变化时复制 assets；或广播 action 变了 | 清 App 数据；或 `adb shell am broadcast -a com.hazukieq.hkrime.deploy` |
| 点输入法「设置」崩溃 | L3 | `res/xml/method.xml:24` 的 `settingsActivity` 与真实类名不符 | 应为 `com.hazukieq.hkrime.settings.PrefMainActivity` |
| 点「评价」跳到别的 app | — | `about_preference.xml:116/121` 仍是上游 `com.osfans.trime` 商店链接（故意保留） | 已知残留，非 bug |
| `assembleRelease` 在 lint 阶段失败 | L1 | 环境 lint 问题 | 加 `-x lintVitalAnalyzeRelease -x lintVitalRelease` |
| release 产物未签名 | L1 | 三级密钥来源都没有 | `./script/gen-keystore.sh` 或设 `HKREME_*`（03 §2） |
| `make opencc-data` 失败 | L1 | `app/src/main/jni/OpenCC` 不存在（被 gitignore） | 别用 Makefile，见 03 §7 |

---

## 3. 诊断命令集

### 3.1 装载层（L2）

```bash
SO=app/libs/arm64-v8a/librime_jni.so
./script/verify-jni-symbols.sh                                    # 契约：73/73
nm -D --defined-only $SO | grep -c ' Java_com_hazukieq_hkrime_'   # 73
nm -D --defined-only $SO | grep -c ' Java_com_osfans_trime_'      # 0
readelf -d $SO | grep -E 'NEEDED|SONAME'                          # 无 libc++_shared；SONAME=librime_jni.so
readelf -n $SO                                                    # 只有 .note.android.ident（无 GNU build-id）
llvm-readelf -l $SO | awk '/LOAD/{print $NF}'                     # 0x4000 ×4（16 KB 对齐）
sha256sum $SO                                                     # fd437255…f03f

# 设备侧：库是否真被装上
adb shell "run-as com.hazukieq.hkrime ls -l lib/" 2>/dev/null
adb logcat -c && adb logcat | grep -iE 'dlopen|UnsatisfiedLink|JNI|16 KB|align'
```

### 3.2 运行层（L3）

```bash
# IME 是否被系统识别
adb shell dumpsys input_method | grep -iE 'hazukieq|mCurMethodId|mEnabledInputMethods'

# 当前/已启用输入法
adb shell settings get secure default_input_method
adb shell settings get secure enabled_input_methods

# 「所有文件访问」是否已授权（false/allow；重装后会被重置）
adb shell appops get com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow

# 手动触发部署/同步（action 已改名）
adb shell am broadcast -a com.hazukieq.hkrime.deploy
adb shell am broadcast -a com.hazukieq.hkrime.sync
```

### 3.3 数据层（L4）

```bash
# 数据目录（默认 getExternalFilesDir(null)/rime）
adb shell "ls -l /sdcard/Android/data/com.hazukieq.hkrime/files/rime"
# 方案/词典/主题
adb shell "ls /sdcard/Android/data/com.hazukieq.hkrime/files/rime/*.schema.yaml"
# OpenCC：应 44 条目、15 个 ocd2
adb shell "ls /sdcard/Android/data/com.hazukieq.hkrime/files/rime/opencc | wc -l"
adb shell "ls /sdcard/Android/data/com.hazukieq.hkrime/files/rime/opencc | grep -c ocd2"
# 方案列表由 default.custom.yaml 决定
adb shell "cat /sdcard/Android/data/com.hazukieq.hkrime/files/rime/default.custom.yaml"
# librime 构建产物（注意在 userDataDir）
adb shell "ls /sdcard/Android/data/com.hazukieq.hkrime/files/rime/build"
# 清数据重来（等价于首次安装）
adb shell pm clear com.hazukieq.hkrime
```

---

## 4. 典型故障详解

### 4.1 候选为空 / 方案点开没字

先分清三种情况：

| 现象 | 根因 | 判定 | 处置 |
|---|---|---|---|
| 方案菜单**完全没有中文方案** | `default.custom.yaml` 缺失/为空 | `ls *.schema.yaml` 有文件但菜单空 | 清数据重部署；确认 `default.custom.yaml` 有 `patch: schema_list` |
| 菜单**有 hakka_jiugong**，打字无候选 | 词典未编译 | `build/` 无 `hakka.table.bin` | 重新部署；查权限；跑 `RimeDataTest` |
| 菜单有 `hakka_qwerty` / `ipa_yunlong`，点开无候选 | **这两个 schema 文件不存在**（`default.custom.yaml` 残留项） | `ls` 确认无对应 `.schema.yaml` | 补文件或从 `schema_list` 删掉（07 §8） |

**判定命令**：

```bash
adb shell "ls /sdcard/Android/data/com.hazukieq.hkrime/files/rime/*.schema.yaml"
adb shell "ls /sdcard/Android/data/com.hazukieq.hkrime/files/rime/build" | grep hakka
```

> 这不是 JNI 问题，别去查 `.so`。当前 assets 已内置「客语12键」与 9 张词典（04 §5），
> 「无任何方案」的旧结论已过时。

### 4.2 `MANAGE_EXTERNAL_STORAGE` 未授权 → `setTheme`↔`init` 无限递归（**卡死调用方**）

**现象**：`adb shell am instrument ...` 一直不返回；或 App 启动后 ANR/CPU 占满。

**机制**（`data/Config.java`）：

```text
setTheme(theme)            :292  →  init(false)          :355
init() 写 sharedDataDir/主题 时因无存储权限抛异常
   catch (Exception e)     :395  →  setTheme(defaultName) :292  →  init() … 无限递归
```

**触发条件**：`MANAGE_EXTERNAL_STORAGE` 被拒（新装/重装后被系统重置），且 `prepareRime` 需要写外部目录。

**处置**：

```bash
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
# 或：系统设置 → 应用 → hkrime → 权限 → 所有文件访问 → 允许
```

**经验**：任何调用 `Config.get()` / `Rime.get(ctx,true)` 的自动化（仪器测试、脚本）**必须套 `timeout`**，否则权限问题会把调用方挂死。这是本轮真机测试踩到的坑，不是测试框架问题。

> `TODO:` 这是应用缺陷（递归无出口），修复方式是在 `catch` 里加递归深度/一次性标记。未做。

### 4.3 `UnsatisfiedLinkError`（装载层）

```text
java.lang.UnsatisfiedLinkError: No implementation found for void com.hazukieq.hkrime.core.Rime.setup(...)
```

| 检查 | 期望 |
|---|---|
| `verify-jni-symbols.sh` | `OK` |
| APK 内库哈希 | `unzip -p … \| sha256sum` == `fd437255…f03f` |
| 是否混入旧库 | `unzip -l apk \| grep 'lib/'` 应只有 1 行 |
| 设备 ABI | `adb shell getprop ro.product.cpu.abi` 必须是 `arm64-v8a` |

**历史对照**（不要再犯）：曾出现「85 MB 废库 + 13.5 MB 好库并存 + 两处 `loadLibrary` 名字不同」，
表现为「有时能用、有时崩」——详见 02 §2。

### 4.4 改名后「输入法消失」

`System.loadLibrary` 与 IME id 都跟包名走：

```bash
adb shell settings get secure enabled_input_methods
# 含 com.tumuyan.trime/... 或 com.osfans.trime/... → 系统认为是另一个 app
```

处置：卸载旧包 → 重装 hkrime → 设置里**重新勾选**输入法 → 选择它。

### 4.5 旧用户数据不继承

两个包名 = 两个数据目录（05 §7）。排查时不要假设「词库丢了是 bug」，
先确认 `files/rime` 是否为新建的空目录；旧数据可能仍在 `/sdcard/rime` 或旧包目录。

### 4.6 16 KB 页对齐弹窗（Android 15/16）

**修复前的原文**：

> 此应用不符合 16 KB 对齐要求。ELF 文件对齐检查失败。以下库未进行 16 KB 对齐：`lib/arm64-v8a/librime_jni.so: LOAD 区段未对齐`

**判定**：

```bash
llvm-readelf -l app/libs/arm64-v8a/librime_jni.so | awk '/LOAD/{print $NF}'
# 0x1000 → 4 KB（会被拒）；0x4000 → 16 KB（合规）
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 4 <apk>    # 退出码 0 = 通过
```

**根因/修法**：链接时缺 `-Wl,-z,max-page-size=16384`。`script/build-jni-so.sh` 已固定注入（`9dc8c32`），
重建后 4 个 LOAD 全 `0x4000`，真机弹窗消失。详见 [02 §7](02-jni-so.md)。

> 与「Android 应用兼容性…（可调试应用）」是两回事：后者是 Android 16 对 debug 包的常规提示，不影响运行。

### 4.7 部署不生效 / 改了 assets 没反应

`Config.prepareRime()` 的复制条件是「目录不存在」或「版本号变化」（`data/Config.java:178-212`）：

```bash
adb shell pm clear com.hazukieq.hkrime     # 最省事的强制重来
```

或提 `versionCode` 后重装。

---

## 5. 技术债与路线图

### 5.1 已完成（不要再当 TODO）

| 项 | 证据 |
|---|---|
| 重建与包名匹配的 `.so`（73 符号、16 KB 对齐） | `verify-jni-symbols.sh` OK；真机仪器测试通过 |
| 删除 85 MB 废库、`librime_jni_1_5.so`、`libs_backup/`，统一 `loadLibrary("rime_jni")` | 仓库内引用 = 0；`libs_backup/` 已不存在 |
| 整包改名 `com.hazukieq.hkrime` | 103 文件 / 391 行；dex & arsc 零残留 |
| OpenCC 资源恢复（29 个）+ 实测转换 | `2b50c1f`；真机 4 组转换断言通过 |
| 客家话数据并入（16 个文件、9 张词典） | `2b50c1f`；`hakka.table.bin` 生成 |
| 16 KB 页对齐 | `9dc8c32`；LOAD=0x4000；zipalign16 通过 |
| 签名密钥移出仓库 + 生成脚本 | `bebad9f`；新证书 `d9d7d91a…93cb` |
| 真机仪器测试 | `OK (1 test)`；`reports/DEVICE-TEST-REPORT.md` |
| 构建/校验/密钥脚本固化 | `script/{build-jni-so,verify-jni-symbols,gen-keystore}.sh` |
| 文档集（9 篇 + 报告） | 本目录 |

### 5.2 未完成（按优先级）

| 优先级 | 事项 | 现状 | 建议动作 |
|---|---|---|---|
| 🔴 P0 | **真机 IME 界面冒烟** | 仪器测试只验证数据链路；启用/选择/打字/上屏/主题切换/部署按钮未人工验证 | 按 03 §6.3 逐项记录；重点看主题 `run`/`broadcast` 是否生效 |
| 🟠 P1 | **补 `luna_pinyin` 词典** | `dependencies`/反查依赖它，仓库未带 | 放入 `luna_pinyin.schema.yaml` + 词典；补测试断言 |
| 🟠 P1 | **清理方案菜单缺口** | `default.custom.yaml` 列了 `hakka_qwerty`、`ipa_yunlong`，无对应 schema | 补文件或删两项（二选一） |
| 🟠 P1 | **补 `ywz.json` / `lenth.json`** | 「情」「联」开关依赖，仓库未带 | 从作者处取或删开关 |
| 🟠 P1 | **历史密钥清理** | 旧 `skeys.jks` + 明文口令仍在 git 历史 | `git filter-repo` + force push；需所有者决策（05 §6） |
| 🟠 P1 | **release lint 被绕过** | `assembleRelease` 必须 `-x lintVital*` | 定位 AGP 7.4.2 + JDK17 的 lint 问题 |
| 🟡 P2 | **`Config` 递归缺陷** | 权限被拒时 `setTheme`↔`init` 无限递归（§4.2） | catch 里加一次性标记/深度限制 |
| 🟡 P2 | **ABI 只有 arm64-v8a** | `abiFilters "arm64-v8a"` | 32 位/模拟器需另编 `.so` 并各跑契约校验 |
| 🟡 P2 | **旧用户数据不继承** | 改包名后词频/配置全丢 | 实现「首启检测旧目录并复制」（05 §7 方案 B） |
| 🟡 P2 | **无 CI** | 5 个 workflow 已删 | 重建最小 CI：`assembleDebug` + `verify-jni-symbols.sh` |
| 🟡 P2 | **无用户词库预置** | 首次使用无词频 | 视需要预置，权衡体积/隐私 |
| 🟢 P3 | **商店链接指向上游** | `about_preference.xml:116/121` | 有待发布渠道后替换 |
| 🟢 P3 | `Makefile` 已失效 | `opencc-data` 依赖不存在的 `jni/OpenCC` | 删掉/重写或标注「勿用」 |
| 🟢 P3 | `script/changelog.sh` 有 bug | 8 行，`$curent` 拼写错 | 修或删 |
| 🟢 P3 | `versionCode` 未自增 | 恒为 `20220701` | 改为递增整数（05 §5） |
| 🟢 P3 | `keystore.tar.enc` 遗留 | 10,256 B，上游加密包，口令未知 | 评估删除 |
| 🟢 P3 | `gradle.properties` 重复 `org.gradle.jvmargs` | 两段同名属性（内容已一致） | 合并为一行 |

### 5.3 技术债全景

```text
验证    ████████████████████  真机 IME 界面冒烟缺失（数据链路已验）   ← P0
功能    ██████████████████░░  luna_pinyin / 两个方案 / 两个 opencc 配置缺料  ← P1
安全    ████████████████░░░░  历史密钥未清（当前树已干净）            ← P1
工程    ████████████░░░░░░░░  lint 绕过 / 无 CI / Makefile 失效
稳定    ██████████░░░░░░░░░░  Config 递归缺陷 / 旧数据不继承
兼容    ██████░░░░░░░░░░░░░░  单 ABI
清洁    ████░░░░░░░░░░░░░░░░  上游商店链接 / keystore.tar.enc / 版本号
```

> 一句话结论：**「改名 + 新 `.so` + 数据 + 真机数据链路」已闭环；剩下的是「界面体验验收」与「补料让方案完整」。**
