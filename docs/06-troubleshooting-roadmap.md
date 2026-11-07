# 06 · 排障与路线图

> 排障按「**先分层定位，再对症下药**」组织。所有命令都在仓库根或 adb shell 中可直接执行。
> 基线 HEAD `7436b6d`。

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
L2 装载层   .so 能不能加载？      → UnsatisfiedLinkError / dlopen / 符号
L3 运行层   键盘能不能出？        → IME 启用/选择、service 绑定、权限
L4 数据层   能不能出字？          → 方案、词典、OpenCC、部署、用户目录
```

从下往上排除，**不要跳层**：候选为空是 L4，但如果是 L2 崩了也会表现成「键盘空白」。

| 层 | 一句话判据 |
|---|---|
| L1 | `./gradlew :app:assembleDebug` 成功 |
| L2 | `./script/verify-jni-symbols.sh` → `OK`，且 logcat 无 `UnsatisfiedLinkError` |
| L3 | 系统设置 → 语言和输入法 → 已启用列表里有 hkrime，且当前输入法是它 |
| L4 | `Rime.get()` 后有 session，`get_schema_list` 非空 |

## 2. 症状速查表

| 症状 | 最可能层 | 原因 | 处置 |
|---|---|---|---|
| 装了但「输入法」列表里没有 hkrime | L3 | 包名变化后系统仍记旧 IME id；或未声明 service | 卸载重装 → 设置里重新启用；查 `dumpsys input_method` |
| 能启用、点输入框弹出「hkrime 已停止运行」 | L2 | `.so` 与 Java 包名不匹配（装错库/没重编） | `verify-jni-symbols.sh`；`logcat | grep UnsatisfiedLink` |
| `dlopen failed: library "libc++_shared.so" not found` | L2 | 用了 shared STL 且未打包 | 当前库是 `c++_static`（NEEDED 无 libc++_shared），若出现说明换过库 → 重编或补打包 |
| 键盘出得来，候选永远空 | L4 | **没有输入方案**（最常见） | 见 §4.1 |
| 只有英文/数字，切不到中文 | L4 | 方案列表空 / `default.custom.yaml` 缺失 | 见 §4.1 |
| 打字无候选但能上屏字母 | L4 | 方案 `speller` 不匹配按键，或按键全被判 ascii | `adb logcat` 看 `process_key` 返回；检查 `ascii_mode` |
| 首次启动卡在权限页，拒绝后循环弹 | L3 | `LauchAct.requestPermission()` 无退出路径 | 手动去系统设置授予「所有文件访问」+「悬浮窗」，或清 App 数据重启 |
| 词库不生效 / 部署没反应 | L4 | `Config` 只在包版本变化时复制 assets；或广播 action 变了 | 清 App 数据；或 `adb shell am broadcast -a com.hazukieq.hkrime.deploy` |
| 点输入法「设置」崩溃 | L3 | `res/xml/method.xml:24` 的 `settingsActivity` 与真实类名不符 | 应为 `com.hazukieq.hkrime.settings.PrefMainActivity` |
| 点「评价」跳到别的 app | — | `about_preference.xml:116/121` 仍是上游 `com.osfans.trime` 商店链接（故意保留） | 已知残留，非 bug |
| `assembleRelease` 在 lint 阶段失败 | L1 | 环境 lint 问题 | 加 `-x lintVitalAnalyzeRelease -x lintVitalRelease` |
| `make opencc-data` 失败 | L1 | `app/src/main/jni/OpenCC` 不存在（被 gitignore） | 别用 Makefile，见 03 §5 |

## 3. 诊断命令集

### 3.1 装载层（L2）

```bash
SO=app/libs/arm64-v8a/librime_jni.so
./script/verify-jni-symbols.sh                      # 契约：73/73
nm -D --defined-only $SO | grep -c ' Java_com_hazukieq_hkrime_'   # 73
nm -D --defined-only $SO | grep -c ' Java_com_osfans_trime_'      # 0
readelf -d $SO | grep -E 'NEEDED|SONAME'            # 无 libc++_shared.so；SONAME=librime_jni.so
sha256sum $SO                                       # d6b295b6…ca098ec

# 设备侧：库是否真被装上
adb shell "run-as com.hazukieq.hkrime ls -l lib/" 2>/dev/null
adb logcat -c && adb logcat | grep -iE 'dlopen|UnsatisfiedLink|JNI|rime'
```

### 3.2 运行层（L3）

```bash
# IME 是否被系统识别（看 mId / service 名）
adb shell dumpsys input_method | grep -iE 'hazukieq|mCurMethodId|mEnabledInputMethods'

# 当前输入法
adb shell settings get secure default_input_method
adb shell settings get secure enabled_input_methods

# 手动触发部署/同步（action 已改名）
adb shell am broadcast -a com.hazukieq.hkrime.deploy
adb shell am broadcast -a com.hazukieq.hkrime.sync
```

### 3.3 数据层（L4）

```bash
# 数据目录（默认 getExternalFilesDir(null)/rime）
adb shell "run-as com.hazukieq.hkrime ls -l files/rime" 2>/dev/null
adb shell "ls -l /sdcard/Android/data/com.hazukieq.hkrime/files/rime"

# 关键文件：方案列表由 default.custom.yaml 决定
adb shell "cat /sdcard/Android/data/com.hazukieq.hkrime/files/rime/default.custom.yaml"

# 清数据重来（等价于首次安装）
adb shell pm clear com.hazukieq.hkrime
```

## 4. 典型故障详解

### 4.1 候选为空 / 方案列表空（**最高频**）

**根因**：hkrime **不内置任何输入方案**（`assets/rime/` 只有 2 个主题 yaml，见 04 §5）。
`Config.prepareRime()` 会补一个**空** `default.custom.yaml`，但空 `schema_list` = 没有方案可选。

**判定**：

```bash
adb shell "ls /sdcard/Android/data/com.hazukieq.hkrime/files/rime/*.schema.yaml"
# 空 → 确认无方案
```

**处置**（任选）：

| 方案 | 做法 |
|---|---|
| 用户自备 | 把 `*.schema.yaml` + `*.dict.yaml` 放进 shared 数据目录，并在 `default.custom.yaml` 写 `patch: schema_list: [{schema: xxx}]` |
| 内置（开发） | 放进 `app/src/main/assets/rime/`，提 `versionCode` 或清数据触发重新复制（04 §6） |

> 这不是 JNI 问题，别去查 `.so`。

### 4.2 `UnsatisfiedLinkError`（装载层）

```text
java.lang.UnsatisfiedLinkError: No implementation found for void com.hazukieq.hkrime.core.Rime.setup(...)
```

| 检查 | 期望 |
|---|---|
| `verify-jni-symbols.sh` | `OK` |
| APK 内库哈希 | `unzip -p … | sha256sum` == `d6b295b6…` |
| 是否混入旧库 | `unzip -l apk | grep 'lib/'` 应只有 1 行 |
| 设备 ABI | `adb shell getprop ro.product.cpu.abi` 必须是 `arm64-v8a` |

**历史对照**（不要再犯）：曾出现「85 MB 废库 + 13.5 MB 好库并存 + 两处 `loadLibrary` 名字不同」，
表现为「有时能用、有时崩」——详见 02 §2。

### 4.3 改名后「输入法消失」

`System.loadLibrary` 与 IME id 都跟包名走：

```bash
# 系统里可能还留着旧 id
adb shell settings get secure enabled_input_methods
# 含 com.tumuyan.trime/... 或 com.osfans.trime/... → 系统认为是另一个 app
```

处置：卸载旧包 → 重装 hkrime → 设置里**重新勾选**输入法 → 选择它。

### 4.4 旧用户数据不继承

两个包名 = 两个数据目录，`applicationId` 一变即丢（05 §7）。
排查时不要假设「词库丢了是 bug」，先确认 `files/rime` 是否为新建的空目录。

### 4.5 部署不生效 / 改了 assets 没反应

`Config.prepareRime()` 的复制条件是「目录不存在」或「版本号变化」（`data/Config.java:178-212`）：

```bash
adb shell pm clear com.hazukieq.hkrime     # 最省事的强制重来
```

或提 `versionCode` 后重装。

## 5. 技术债与路线图

### 5.1 已完成（不要再当 TODO）

| 项 | 证据 |
|---|---|
| 重建与包名匹配的 `.so`（73 符号） | 02 §1；`verify-jni-symbols.sh` OK |
| 删除 85 MB 废库与 `librime_jni_1_5.so`，统一 `loadLibrary("rime_jni")` | 02 §1；仓库内 `rime_jni_1_5` 引用 = 0 |
| 整包改名 `com.hazukieq.hkrime` | 103 文件 / 391 行；dex & arsc 零残留 |
| 构建/校验脚本固化 | `script/build-jni-so.sh`、`verify-jni-symbols.sh` |
| 产出可安装 APK（debug + release） | 05 §4 |
| 文档集 | 本目录 |

### 5.2 未完成（按优先级）

| 优先级 | 事项 | 现状 | 建议动作 |
|---|---|---|---|
| 🔴 P0 | **真机冒烟** | 从未在真机跑过：安装、启用、打字、上屏、主题切换、部署、同步全未验证 | `adb install -r artifacts/hkrime-debug.apk`，按 [03 §4.3](03-build.md) 清单逐项记录 |
| 🔴 P0 | **签名密钥入库** | `skeys.jks` + `release.properties`（明文口令 263909）已在版本库 | 移出当前树、恢复 `.gitignore`、换新 key、评估历史清理（05 §6） |
| 🟠 P1 | **release lint 被绕过** | `assembleRelease` 必须 `-x lintVitalAnalyzeRelease -x lintVitalRelease` | 定位 lint 环境问题后去掉绕过；已产出的 release 包未经 lint |
| 🟠 P1 | **`libs_backup/` 残留旧库** | 13,558,064 B，旧包名，不参与构建但**极易被误装** | `trash libs_backup`（可恢复），并在 README 注明历史；若保留则加 `README` 警告 |
| 🟠 P1 | **客家话方案缺失** | 全仓库 0 个客家话方案/词典/资源；`hks/rime.zip` 不存在 | 选方言点 → 写 `hk.schema.yaml` + 词典 → 内置（04 §6） |
| 🟡 P2 | **ABI 只有 arm64-v8a** | `abiFilters "arm64-v8a"` | 覆盖 32 位/模拟器需为 armeabi-v7a/x86_64 另编 `.so` 并各跑契约校验 |
| 🟡 P2 | **旧用户数据不继承** | 改包名后词频/配置全丢 | 实现「首启检测旧目录并复制」（05 §7 方案 B） |
| 🟡 P2 | **无 CI** | 5 个 workflow 已删，无自动构建/校验 | 重建最小 CI：`assembleDebug` + `verify-jni-symbols.sh`（不含 JNI 编译，用已提交的 `.so`） |
| 🟡 P2 | **商店链接指向上游** | `about_preference.xml:116/121` 指向 `com.osfans.trime` | 有待发布渠道后替换 |
| 🟢 P3 | `Makefile` 已失效 | `opencc-data` 依赖不存在的 `jni/OpenCC`；`spotless` 目标可用但 `make debug` 链路不成立 | 删掉/重写 Makefile，或只在文档标注「勿用」 |
| 🟢 P3 | `script/changelog.sh` 有 bug | 8 行，变量名拼写 `$curent` | 修或删 |
| 🟢 P3 | `versionCode` 未自增 | 恒为 `20220701` | 改为递增整数（05 §5） |
| 🟢 P3 | `gradle.properties` 重复 `org.gradle.jvmargs` | 两段同名属性，虽已同步内容但可读性差 | 合并为一行 |
| 🟢 P3 | 无 `docs` 之外的开发者入口 | 新贡献者需读 8 篇文档 | 可在 README 顶部加「开发文档」表（已加） |

### 5.3 技术债全景

```text
安全    ████████████████████  skeys.jks + 明文口令入库          ← P0
验证    ██████████████████░░  真机冒烟缺失（全链路未验证）      ← P0
功能    ████████████████░░░░  客家话方案缺失（项目初衷未落地）  ← P1
工程    ████████████░░░░░░░░  lint 绕过 / 无 CI / Makefile 失效
兼容    ██████████░░░░░░░░░░  单 ABI / 旧数据不继承
清洁    ██████░░░░░░░░░░░░░░  libs_backup 残留 / 上游商店链接
```

> 一句话结论：**「能编出来、能装上、符号对得上」已成立；「真的能用」还差一次真机冒烟，
> 「真的是客家话输入法」还差方案与词库。**
