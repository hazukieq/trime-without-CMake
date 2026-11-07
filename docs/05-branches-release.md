# 05 · 分支模型与发布

> 覆盖：分支职责、**为什么改名与换 `.so` 必须同批**、改名影响面全清单、发布流程、签名与密钥安全、旧数据迁移。
> 基线 HEAD `68f876f`（分支 `dev`）。

## 目录

1. [分支模型](#1-分支模型)
2. [改名与换 .so 为什么必须同批](#2-改名与换-so-为什么必须同批)
3. [改名影响面清单（10 层）](#3-改名影响面清单10-层)
4. [发布流程](#4-发布流程)
5. [版本号策略](#5-版本号策略)
6. [签名与密钥安全](#6-签名与密钥安全)
7. [旧用户数据迁移](#7-旧用户数据迁移)
8. [交付清单与回滚](#8-交付清单与回滚)

---

## 1. 分支模型

### 目标形态

| 分支 | 角色 | 内容 | 谁可以推 |
|---|---|---|---|
| **`main`** | **稳定主线** | 原 `develop` 的内容**原样不动**（= `tumuyan/trime-without-CMake` 的 without-CMake 主线，`com.tumuyan.trime` + 旧 `.so`） | 仅同步上游/修 CI 类改动 |
| **`dev`** | **hkrime 开发线** | 原 `hakka` 的全部成果：改名 `com.hazukieq.hkrime` + 重编 `.so` + 脚本 + 文档 | 日常开发 |
| `feature/*` / `fix/*` | 短分支 | 从 `dev` 切出，PR 回 `dev` | — |

```text
main   ──●──●──●  (without-CMake 稳定主线，com.tumuyan.trime)
         ╲
dev       ●──●──●  (hkrime：com.hazukieq.hkrime + 新 .so)
```

> **现状（2026-11-08 已执行）**：远端只剩 `main`（=`14d365e`，默认分支）与 `dev`（=`68f876f`）；
> 旧 `develop`/`hakka` 已删除。迁移前快照：`develop=14d365ec`、`hakka=0c1e58ac`。
> 回滚：`git push origin 14d365ec:refs/heads/develop 0c1e58ac:refs/heads/hakka`，并把默认分支改回 `develop`。

### 迁移命令（2026-11-08 已执行；以下为可复现步骤）

```bash
# 1) 本地改名
git branch -m develop main
git branch -m hakka dev

# 2) 推新分支并设跟踪
git push -u origin main
git push -u origin dev

# 3) GitHub 上把默认分支切到 main（Settings → Branches）
# 4) 确认无误后删除旧远端分支
git push origin --delete develop hakka
```

实际执行记录（GitHub 没有「分支改名」API，实际用「建新 ref → 改默认分支 → 删旧 ref」）：

```bash
# 用本地分支/sha 直接建远端分支
git push -u origin hakka:dev
git push origin origin/develop:refs/heads/main
git push origin --delete develop hakka            # 或 gh api -X DELETE .../git/refs/heads/develop
gh api -X PATCH repos/hazukieq/trime-without-CMake -f default_branch=main
# 本地：git branch -m hakka dev; git branch --track main origin/main; git fetch --prune
```

注意事项：

| 点 | 说明 |
|---|---|
| 改名≠合并 | 两个分支内容完全不同（main 是旧包名，dev 是新包名），**绝不能互 merge** |
| `main` 保持原样 | 不要把改名成果合回 `main`，否则「稳定主线」失去意义 |
| 保护分支 | 给 `main` 开保护（禁直推），`dev` 允许直推 |
| 默认分支 | 必须是 `main`，否则 clone 下来的是旧包名的 without-CMake 版，容易混淆 |

### 不变式（写进 review checklist）

```text
① dev 分支上：applicationId == namespace == com.hazukieq.hkrime
② dev 分支上：app/libs/arm64-v8a/ 只有 librime_jni.so，且 73 符号前缀为 com_hazukieq_hkrime
③ dev 分支上：仓库内 "rime_jni_1_5" 出现次数 == 0
④ main 分支上：上述三条**都应是旧值**（com.tumuyan.trime / com_osfans_trime）
```

## 2. 改名与换 .so 为什么必须同批

这是本项目**最容易犯的发布错误**，单独成节。

### 原因链

```text
改名（com.osfans.trime → com.hazukieq.hkrime）
   ⇒ Java 包/类名变 ⇒ JNI 符号名要求变（Java_com_hazukieq_hkrime_core_Rime_*）
   ⇒ 旧 .so 只有 Java_com_osfans_trime_* ⇒ 全部 native 调用 UnsatisfiedLinkError
```

| 若只做改名 | 若只换 `.so` | 同批做 |
|---|---|---|
| 启动即崩（第一个 native 调用 `setup`） | 新库符号对不上旧 Java 包名 → 同样崩 | 唯一自洽状态 |

**关键事实**：不仅是 85 MB 废库，**连 13.5 MB「正确库」也是按 `com.osfans.trime` 编译的**——
它内嵌 10 处旧包名、73 个符号带 `com_osfans_trime` 前缀。所以「用旧库 + 新包名」= 必崩，
不存在「先改名、库以后再换」的中间可用态。

### 发布纪律

| 规则 | 检查手段 |
|---|---|
| 改名与换库在**同一提交/同一 release** | `git log` 中 `922a2ba`（改名）与 `7436b6d`（重编）相邻 |
| release 前必跑符号契约 | `./script/verify-jni-symbols.sh` → `OK` |
| release 前必查 APK 内库哈希 | `unzip -p … \\| sha256sum` 与 `app/libs/…` 一致 |
| 禁止单独 cherry-pick「改名」到 main | main 保持旧包名 |

## 3. 改名影响面清单（10 层）

`com.osfans.trime` → `com.hazukieq.hkrime`，实际改动 **103 文件 / 391 行**。逐层清单（✅=已改，⚠=注意）：

| # | 层 | 位置 | 现状 |
|---|---|---|---|
| 1 | Gradle | `app/build.gradle:9 applicationId`、`:66 namespace` | ✅ `com.hazukieq.hkrime` |
| 2 | 源码包路径 | `app/src/main/java/com/hazukieq/hkrime/`（87 文件）、package/import、`R`/`BuildConfig`/`databinding` 引用 | ✅ |
| 3 | Manifest | `:18 com.hazukieq.hkrime.TrimeApplication`、`:59 …PrefLauncherAlias`（activity-alias 全类名） | ✅ |
| 4 | IME 子类型配置 | `res/xml/method.xml:24 android:settingsActivity` | ✅ **最易漏**，漏了则点输入法设置崩 |
| 5 | 广播 action | `IntentReceiver.kt:63-64` → `com.hazukieq.hkrime.deploy` / `.sync` | ✅ **外部脚本（Tasker/adb）需同步改** |
| 6 | native `.so` | 73 个 `Java_com_*` 符号 + `jni-utils.h` 的 `FindClass` 7 处 + `traits.app_name` | ✅ 已重编（见 02） |
| 7 | 存储目录 | `getExternalFilesDir(null)/rime` 随 `applicationId` 变化 | ⚠ 旧数据不继承，见 §7 |
| 8 | IME id | `ComponentName(appContext, TrimeImeService::class.java).flattenToShortString()` 随包名变 | ⚠ 系统里存的是旧 id → **必须重新启用输入法** |
| 9 | 主题内引用 | `assets/rime/trime.yaml`、`tongwenfeng.trime.yaml`（改名时曾被误改成 hkrime 链接，已还原） | ⚠ 只应改「应用自身」标识，**不要改作者/商店 URL** |
| 10 | 遗留上游链接 | `res/xml/about_preference.xml:116`（Play 商店）、`:121`（Coolapk）仍指 `com.osfans.trime` | ⚠ **故意保留**：点「评价」会打开上游 app；如要改需换成 hkrime 自己的商店页（暂无）→ 已知残留 |

### 改名的三条禁忌（来自这次实战）

| 禁忌 | 事故 | 正确做法 |
|---|---|---|
| 全局 `sed` 不设边界 | `github.com/osfans/trime` 被改成不存在的 `github.com/hazukieq/hkrime`；商店链接同理 | 先 `grep -n` 看命中，分三批 sed：`com\.osfans\.trime`（包名）、`com/osfans/trime`（JNI 路径）、`com.tumuyan.trime`（applicationId）；**改完 `git diff` 逐条复读** |
| 只改 Java 不改 XML | `method.xml` 的 `settingsActivity` 漏 → 设置入口崩溃 | 用 `grep -rn 'com\.osfans' app/src/main` 兜底到 0（除有意保留的商店 URL） |
| 只改代码不改 `.so` | 见 §2 | 同批重编 + 契约校验 |

## 4. 发布流程

```bash
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"

# 0) 前置：符号契约（改过 native 代码/换库时必做）
./script/verify-jni-symbols.sh

# 1) Debug 包（内部验证）
./gradlew :app:assembleDebug
#    → app/build/outputs/apk/debug/trime-3.2.7-debug.apk

# 2) Release 包（对外；lint 绕过见 03 §2）
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
#    → app/build/outputs/apk/release/trime-3.2.7-release.apk

# 3) 静态校验
AAPT2=$ANDROID_HOME/build-tools/36.0.0/aapt2
$AAPT2 dump badging <apk> | head -3         # package/version 正确
unzip -l <apk> | grep 'lib/'                # 只有 1 个 librime_jni.so
unzip -p <apk> lib/arm64-v8a/librime_jni.so | sha256sum   # == d6b295b6…ca098ec
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs <apk>

# 4) 真机冒烟（**当前未执行**，见 06 §5）
adb install -r <apk>
```

### 已产出交付物

| 产物 | 大小 | sha256 | 说明 |
|---|---|---|---|
| `artifacts/hkrime-debug.apk` | **11,418,991 B** | `8cc718eb0b90071e2bb514c71dc999baca4de2a48d3a54deb598865a09cce545` | 可安装（debug key 签名） |
| `artifacts/hkrime-release.apk` | **9,586,652 B** | `9fe31e156084e275612cce1ee285900f1a5fde0033eadb9bd098cfb7353575fe` | 已用 `skeys.jks` 签名；**未过 lint**，仅静态验证 |

两者 package 均为 `com.hazukieq.hkrime`、versionCode `20220701`、versionName `3.2.7`、
minSdk 21 / targetSdk 33 / native-code `arm64-v8a`。

## 5. 版本号策略

| 字段 | 当前 | 建议 |
|---|---|---|
| `versionName` | `3.2.7`（沿用上游） | hkrime 自有版本需区分上游：建议 `3.2.7-hkrime.1` 或 `0.1.0`，并在设置页展示 |
| `versionCode` | `20220701`（上游日期式） | 改为递增整数（如 1,2,3…），避免与上游日期码冲突；**必须 > 20220701** 才能覆盖安装旧版 |
| `BUILD_INFO` | 构建时由 `app/build.gradle:94-143` 通过 git 命令注入 | 保留，便于定位「哪个提交出的包」 |

> ⚠ 若 `versionCode` 不增，同包名的旧安装包无法覆盖升级；跨包名（com.tumuyan.trime → com.hazukieq.hkrime）则是两个独立 app，可共存。

## 6. 签名与密钥安全

### 现状（**高风险**）

| 项 | 内容 |
|---|---|
| `skeys.jks` | 2,517 B，**已提交到版本库**（`.gitignore:71-73` 本就忽略 `*.jks`，但 `92c7aa5` 用 `git add -f` 强行加入） |
| `release.properties` | 74 B，4 行：`storeFile=skeys.jks`、`storePassword=263909`、`keyAlias=key0`、`keyPassword=263909` —— **口令明文入库** |
| `keystore.tar.enc` | 10,256 B，上游遗留的加密密钥包（口令未知） |
| `.gitignore` | 曾在 `0c1e58a` 被改成放开 `*.jks`/`release.properties`，等于**主动允许密钥入库** |

**风险**：任何拿到仓库的人都能签出「官方」更新包（供应链投毒）；密钥一旦泄露，
在 Play/应用商店分发的版本必须换 key（会丢升级链）。

### 建议处置顺序

| 优先级 | 动作 |
|---|---|
| 🔴 P0 | **停止**继续提交新密钥；把 `skeys.jks` 与 `release.properties` 从**当前树**移除（`trash`，不 `rm`），改用 `local.properties` / `~/.gradle/gradle.properties` / CI secret 注入 |
| 🔴 P0 | 从 `.gitignore` 恢复对 `*.jks`、`*.keystore`、`release.properties` 的忽略；`keystore.tar.enc` 一并评估 |
| 🟠 P1 | 历史清理（`git filter-repo` 抹除历史中的密钥）**需要强推**，会影响所有克隆 → 单独决策、通知协作者 |
| 🟠 P1 | 生成**新的** release keystore（口令随机、离线保存），旧 key 仅用于给已装机用户出最后一版迁移包 |
| 🟡 P2 | 若要公开发布，确认 debug key 不用于任何对外包（当前 `hkrime-release.apk` 已用 skeys 签，符合预期） |

> `TODO:` 决策「是否重签/换 key」需要所有者拍板；本文只给方案，未执行任何历史改写。

## 7. 旧用户数据迁移

包名变了 ⇒ 数据目录变了，**旧数据不会自动继承**：

| 版本 | 数据目录 |
|---|---|
| 旧（`com.tumuyan.trime` / `com.osfans.trime`） | `/sdcard/Android/data/<旧包名>/files/rime` 或用户自设的 `/sdcard/rime` |
| 新（`com.hazukieq.hkrime`） | `/sdcard/Android/data/com.hazukieq.hkrime/files/rime` |

```bash
# 迁移用户词库/配置（需 adb 或文件管理器；注意 Android 11+ 限制）
adb shell "run-as com.hazukieq.hkrime ls files/rime" 2>/dev/null
# 推荐做法：让用户在新版设置里把 shared_data_dir / user_data_dir 指回旧目录
#   （AppPrefs 的 conf__shared_data_dir / conf__user_data_dir，见 01 §6）
```

| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 用户手动把数据目录指回 `/sdcard/rime` | 零代码、数据完整 | 需用户操作；共享目录在新系统上权限受限 |
| B. 首启检测旧目录并复制 | 无感 | 需新增代码 + 处理 `MANAGE_EXTERNAL_STORAGE`；**未实现** |
| C. 不迁移 | 最简单 | 用户丢词频/配置，差评 |

> 当前是 **C**。`TODO:` 若要产品化，优先做 B 的最小版（只复制 `*.userdb`、`*.custom.yaml`、`installation.yaml`）。

## 8. 交付清单与回滚

### 一次完整发布应产出

```text
✅ app/libs/arm64-v8a/librime_jni.so       13,587,824 B  sha256 d6b295b6…
✅ script/build-jni-so.sh / verify-jni-symbols.sh   （可复现凭据）
✅ 符号契约输出 OK（73/73，0 旧包名）
✅ artifacts/hkrime-debug.apk              11,418,991 B  sha256 8cc718eb…
✅ artifacts/hkrime-release.apk            9,586,652 B   sha256 9fe31e15…
⬜ 真机冒烟记录（启动/切换/打字/上屏/主题切换/部署）
⬜ release lint 通过（当前用 -x 绕过）
```

### 回滚

| 场景 | 操作 |
|---|---|
| 新 `.so` 有运行时问题 | `git checkout <上一提交> -- app/libs/arm64-v8a/librime_jni.so`，同时确认 Java 侧包名与该库匹配（旧库只配旧包名） |
| 改名引入崩溃 | 回退 `922a2ba` + `7436b6d` 两个提交（**必须一起回退**） |
| APK 装不上/签名冲突 | 卸载后重装；确认 `versionCode` 递增 |
| 分支重命名搞乱 | 远端保留旧分支直到验证完成；本地 `git branch -m` 可随时改回 |
