# 05 · 分支模型与发布

> 覆盖：分支职责、**为什么改名与换 `.so` 必须同批**、改名影响面全清单、发布流程、版本号、签名与密钥安全、旧数据迁移、回滚。
> 事实快照：`ebb18c1`（当前 HEAD 以 `git log -1` 为准），分支 `dev`。

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
| **`dev`** | **hkrime 开发线** | 原 `hakka` 的全部成果：改名 `com.hazukieq.hkrime` + 重编 `.so` + 脚本 + 数据 + 文档 | 日常开发 |
| `feature/*` / `fix/*` | 短分支 | 从 `dev` 切出，PR 回 `dev` | — |

```text
main   ──●──●──●  (without-CMake 稳定主线，com.tumuyan.trime)
         ╲
dev       ●──●──●  (hkrime：com.hazukieq.hkrime + 新 .so + 客家话数据)
```

> **现状（2026-11-08 已执行）**：远端为 `main`（=`14d365e`，默认分支）与 `dev`（迁移时 tip `68f876f`，其后新增 `b1d9b57`、`feb4310`、`2b50c1f`、`9dc8c32`、`bebad9f`、`63fd09e`、`726a209`、`ebb18c1`）；
> 旧 `develop`/`hakka` 已删除。迁移前快照：`develop=14d365ec`、`hakka=0c1e58ac`。
> 回滚：`git push origin 14d365ec:refs/heads/develop 0c1e58ac:refs/heads/hakka`，并把默认分支改回 `develop`。

### 迁移步骤（已执行；保留供复现）

GitHub 没有「分支改名」API，实际用「建新 ref → 改默认分支 → 删旧 ref」：

```bash
# 1) 用本地分支/sha 直接建远端分支
git push -u origin hakka:dev
git push origin origin/develop:refs/heads/main

# 2) 改默认分支
gh api -X PATCH repos/hazukieq/trime-without-CMake -f default_branch=main

# 3) 确认后删旧分支
git push origin --delete develop hakka

# 4) 本地改名与跟踪
git branch -m hakka dev
git branch --track main origin/main
git fetch --prune
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
④ dev 分支上：libs_backup/ 不存在；仓库根无 skeys.jks / release.properties
⑤ main 分支上：上述①-④都应是旧值（com.tumuyan.trime / com_osfans_trime / 旧库 / 旧路径）
⑥ dev 分支上：python3 script/verify-rime-assets.py 必须 53 passed, 0 failed
```

---

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

本轮**同批**完成的两件事，可以对照 `git log` 看：

```text
922a2ba  refactor: rename package to com.hazukieq.hkrime
7436b6d  build(jni): rebuild librime_jni.so for com.hazukieq.hkrime
```

### 发布纪律

| 规则 | 检查手段 |
|---|---|
| 改名与换库在**同一提交/同一 release** | `git log` 中 `922a2ba` 与 `7436b6d` 相邻 |
| release 前必跑符号契约 | `./script/verify-jni-symbols.sh` → `OK` |
| release 前必查 APK 内库哈希 | `unzip -p … \| sha256sum` 与 `app/libs/…` 一致（`fd437255…`） |
| 禁止单独 cherry-pick「改名」到 main | main 保持旧包名 |

---

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
| 7 | RIME 数据里的引用 | `assets/rime/*.trime.yaml` 的 `run`/`broadcast` 目标（`trime.yaml:940-941`、`:953-954`；另两个主题同理） | ✅ assets 内旧包名 0 处 |
| 8 | 存储目录 | `getExternalFilesDir(null)/rime` 随 `applicationId` 变化 | ⚠ 旧数据不继承，见 §7 |
| 9 | IME id | `ComponentName(appContext, TrimeImeService::class.java).flattenToShortString()` 随包名变 | ⚠ 系统里存的是旧 id → **必须重新启用输入法** |
| 10 | 遗留上游链接 | `res/xml/about_preference.xml:116`（Play 商店）、`:121`（Coolapk）仍指 `com.osfans.trime` | ⚠ **故意保留**：点「评价」会打开上游 app；如要改需换成 hkrime 自己的商店页（暂无）→ 已知残留 |

### 改名的三条禁忌（来自这次实战）

| 禁忌 | 事故 | 正确做法 |
|---|---|---|
| 全局 `sed` 不设边界 | `github.com/osfans/trime` 被改成不存在的 `github.com/hazukieq/hkrime`；商店链接同理 | 先 `grep -n` 看命中，分三批 sed：`com\.osfans\.trime`（包名）、`com/osfans/trime`（JNI 路径）、`com.tumuyan.trime`（applicationId）；**改完 `git diff` 逐条复读** |
| 只改 Java 不改 XML / yaml | `method.xml` 的 `settingsActivity`、主题里的 `run`/`broadcast` 包名漏改 → 设置入口崩 / 部署按钮失效 | 用 `grep -rn 'com\.osfans' app/src/main` 兜底到 0（除有意保留的商店 URL） |
| 只改代码不改 `.so` | 见 §2 | 同批重编 + 契约校验 |

> 关于 `traits.app_name`：它不影响 JNI 符号，但会影响 librime 的日志/应用标识。改名时必须与包名一致（否则日志里仍叫旧名）。见 02 §4。

---

## 4. 发布流程

```bash
export JAVA_HOME="$HOME/opt/jdk17/jdk-17.0.9"

# 0) 前置：符号契约（改过 native 代码/换库时必做）
./script/verify-jni-symbols.sh

# 1) Debug 包（内部验证 + 仪器测试用）
./gradlew :app:assembleDebug
#    → app/build/outputs/apk/debug/trime-3.2.7-debug.apk

# 2) Release 包（对外；lint 绕过见 03 §3）
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
#    → app/build/outputs/apk/release/trime-3.2.7-release.apk
#    签名来源见 §6（~/.android-keys 或环境变量）

# 3) 静态校验
AAPT2=$ANDROID_HOME/build-tools/36.0.0/aapt2
$AAPT2 dump badging <apk> | head -3                       # package/version 正确
unzip -l <apk> | grep 'lib/'                              # 只有 1 个 librime_jni.so
unzip -p <apk> lib/arm64-v8a/librime_jni.so | sha256sum   # == fd437255…f03f
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 4 <apk>      # 16 KB 对齐
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs <apk>

# 4) 数据链路验证（真机仪器测试，见 03 §5）
timeout 300 adb shell am instrument -w -r -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner    # OK (1 test)
```

### 已产出交付物（2026-11-08 第三轮，`ebb18c1`）

| 产物 | 大小 | sha256 | 说明 |
|---|---|---|---|
| `artifacts/hkrime-debug.apk` | **13,437,603 B** | `fe6ae286697614a52afbe4ef1a8db75e3df8fbf167d404c2d22805ef3597e575` | 可安装（debug key） |
| `artifacts/hkrime-release.apk` | **11,405,775 B** | `62b63eb2555229a6df9e78bdc7719da857fd4db7cd26973b7e3533d760ddd0a5` | 新 keystore 签名，`apksigner verify` 通过；**未过 lint** |
| `artifacts/librime_jni.so` | 13,587,744 B | `fd4372553592830cc8af03a7cae10009eb1882f2eada957086513a7fbc60f03f` | Debug(-O0)，16 KB 对齐 |
| `artifacts/librime_jni-release-opt.so` | 6,065,152 B | `6b6173d10039262a57205efb321d195d72c1e71b568ec8ba0320beaf4696f920` | `-O2` 备选，未装入 app |

两者 package 均为 `com.hazukieq.hkrime`、versionCode `20220701`、versionName `3.2.7`、
minSdk 21 / targetSdk 33 / native-code `arm64-v8a`，`zipalign -c -P 16 4` 均通过。

> **口径**：上表 sha256 是**该次交付物**的指纹，用于校验收到的文件未被改动；
> **不要承诺「任意 clean build 得到同一 hash」**——APK 是 zip，条目时间戳/顺序会让字节变化。
> 可承诺的是尺寸稳定：clean 构建 debug ≈13.44 MB、release ≈11.41 MB。
>
> **体积变化**：debug 17,233,842 B → 13,437,603 B（**变小 3.8 MB，原因未核实，TODO**）；
> release 11,004,041 B → 11,405,775 B（+401 KB），来自并入 `luna_pinyin.dict.yaml`（889,946 B）与 3 个新方案/词典（`726a209`），**不是废数据**。
>
> `release` 在**没有 keystore** 时也能构建成功（`ebb18c1`），但产物名为 `trime-3.2.7-release-unsigned.apk`，
> `apksigner verify` 会报 `DOES NOT VERIFY: Missing META-INF/MANIFEST.MF`——发布前必须核验签名，见 03 §2.3。

---

## 5. 版本号策略

| 字段 | 当前 | 建议 |
|---|---|---|
| `versionName` | `3.2.7`（沿用上游） | hkrime 自有版本需区分上游：建议 `3.2.7-hkrime.1` 或 `0.1.0`，并在设置页展示 |
| `versionCode` | `20220701`（上游日期式） | 改为递增整数（如 1,2,3…），避免与上游日期码冲突；**必须 > 20220701** 才能覆盖安装旧版 |
| `BUILD_INFO` | `app/build.gradle:16` buildConfigField，值由 `getBuildInfo()`（`:118`）拼出 Build/Date/Commit/Branch/Repository；其中 Commit 来自 `getCommitInfo()`（`:131`） | `ebb18c1` 起用 `git describe --tags --always`：仓库**无 tag** 时回退为**短 sha**，不再输出空串；格式 `<describe>, <commit date>`。`BUILD_VERSION`（`:17`）为 `"$versionName-g" + 短sha + "-" + 日期`。保留，便于定位「哪个提交出的包」 |

> ⚠ 同包名的旧安装包若 `versionCode` 不增则无法覆盖升级；跨包名（`com.tumuyan.trime` → `com.hazukieq.hkrime`）则是两个独立 app，可共存（同时装两个输入法会互相干扰，建议只留一个）。

---

## 6. 签名与密钥安全

### 6.1 现状：密钥已移出仓库

`bebad9f` 把签名材料从版本库移出：

| 项 | 现状 |
|---|---|
| 仓库根 `skeys.jks` | **已删除**（2,517 B → 0） |
| 仓库根 `release.properties` | **已删除**（74 B，曾含明文 `263909`） |
| `release.properties.example` | 新增，7 行占位模板（不含真实口令） |
| `~/.android-keys/hkrime-release.jks` | 2,744 B，`600`（构建机私有） |
| `~/.android-keys/hkrime-release.properties` | 147 B，`600` |
| `script/gen-keystore.sh` | 79 行，随机口令 + `chmod 700/600`，已存在则拒绝覆盖 |

### 6.2 新证书指纹（现行）

```text
DN      : CN=hkrime, OU=hazukieq, O=hkrime-fork, L=Guiping, ST=Guangxi, C=CN
别名    : hkrime
SHA-256 : d9d7d91ae5e7c789ff79b8eb9a1644461e5b7b2c48b52a184d993a90594693cb
SHA-1   : 6b105cf8df1761a7c2320dedb23661be10cd9abb
MD5     : 60a6c4ad49eb532090b2363c35d84f1c
有效期  : 10000 天（2048-bit RSA）
```

校验命令：

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs artifacts/hkrime-release.apk
```

### 6.3 旧密钥仍留在 git 历史（必须知道）

| 事实 | 后果 |
|---|---|
| `skeys.jks` 与明文 `release.properties` 由 `92c7aa5`/`4af2a94` 引入，仍可从历史检出 | 任何人拿到仓库历史都能签出「旧 key 的官方包」 |
| 旧证书指纹（上一版文档记录 `32208d47…`）已随换 key 作废 | 用旧 key 签的包**无法**覆盖安装新 key 的包 |
| 彻底清除需 `git filter-repo` 改写历史 + **force push** | 所有克隆/PR 需重新对齐；需所有者决策 |

**换签名后的兼容性**：Android 只认「同包名 + 同签名」为升级。新 key 签的 hkrime 与旧 key 签的
`com.tumuyan.trime` / 旧 `com.hazukieq.hkrime` 包互不覆盖——用户需卸载重装（会丢应用内数据，词库若在 `/sdcard/rime` 则不受影响）。

### 6.4 处置优先级

| 优先级 | 动作 | 状态 |
|---|---|---|
| 🔴 P0 | 密钥移出当前树、`.gitignore` 恢复忽略 | ✅ 已做（`bebad9f`） |
| 🔴 P0 | 新 keystore 生成 + 构建走三级查找 | ✅ 已做 |
| 🟠 P1 | 历史密钥清理（filter-repo + force push） | ⬜ 未做（需所有者拍板） |
| 🟠 P1 | 旧 key 泄露风险评估：若仓库曾公开，应视旧 key 为已泄露 | ⬜ 待确认仓库可见性 |
| 🟡 P2 | 密钥备份策略（离线介质/密码管理器） | ⬜ 未做 |

> 注：换 key 不是「更安全」的自动保证——**新 key 丢失同样致命**。`gen-keystore.sh` 已警告「一旦丢失，已发布版本无法升级」。

---

## 7. 旧用户数据迁移

包名变了 ⇒ 数据目录变了，**旧数据不会自动继承**：

| 版本 | 数据目录 |
|---|---|
| 旧（`com.tumuyan.trime` / `com.osfans.trime`） | `/sdcard/Android/data/<旧包名>/files/rime`，或用户自设的 `/sdcard/rime` |
| 新（`com.hazukieq.hkrime`） | `/sdcard/Android/data/com.hazukieq.hkrime/files/rime`（默认），用户可改 |

```bash
# 查看新包的数据目录（需 MANAGE_EXTERNAL_STORAGE）
adb shell ls /storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime

# 推荐做法：让用户在新版设置里把 shared_data_dir / user_data_dir 指回旧目录
#   （AppPrefs 的 conf__shared_data_dir / conf__user_data_dir，见 01 §6）
```

| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 用户手动把数据目录指回 `/sdcard/rime` | 零代码、数据完整 | 需用户操作；共享目录在新系统上权限受限 |
| B. 首启检测旧目录并复制 | 无感 | 需新增代码 + 处理 `MANAGE_EXTERNAL_STORAGE`；**未实现** |
| C. 不迁移 | 最简单 | 用户丢词频/配置，差评 |

> 当前是 **C**。`TODO:` 若要产品化，优先做 B 的最小版（只复制 `*.userdb`、`*.custom.yaml`、`installation.yaml`）。

**权限提醒**：`MANAGE_EXTERNAL_STORAGE`（「所有文件访问」）在**每次重装后都会被系统重置**为拒绝。
未授权时 `Config.prepareRime` 写 sharedDataDir 会失败，并可能触发 06 §4.2 的递归卡死。发布说明里应写清这一句。

---

## 8. 交付清单与回滚

### 一次完整发布应产出

```text
✅ app/libs/arm64-v8a/librime_jni.so       13,587,744 B  sha256 fd437255…f03f（16 KB 对齐）
✅ script/build-jni-so.sh(419) / verify-jni-symbols.sh(237) / gen-keystore.sh(79) / verify-rime-assets.py(183)
✅ 符号契约输出 OK（73/73，0 旧包名）
✅ rime 资产静态自检 53 passed, 0 failed（schema/词典/OpenCC 引用闭合）
✅ assets/rime 23 项；opencc 34（18 txt + 16 json）→ fresh 部署生成 18 ocd2 / 10 bin / 3 方案
✅ artifacts/hkrime-debug.apk              13,437,603 B  sha256 fe6ae286…
✅ artifacts/hkrime-release.apk            11,405,775 B  sha256 62b63eb2…（新 key，verify 通过）
✅ 真机仪器测试 OK (1 test)（fresh 部署/18 ocd2/10 bin/3 方案/四组转换/ywz+lenth 可加载）
⬜ 真机 IME 界面冒烟（启用/选择/打字/上屏/主题切换/部署按钮/多值候选）
⬜ release lint 通过（当前用 -x 绕过）
⬜ 历史密钥清理（需改写历史，未决策）
⬜ 用户词库（*.userdb）预置：**设计取舍，不做**
```

### 回滚

| 场景 | 操作 |
|---|---|
| 新 `.so` 有运行时问题 | `git checkout <上一提交> -- app/libs/arm64-v8a/librime_jni.so`，**同时确认 Java 侧包名与该库匹配**（旧库只配旧包名） |
| 改名引入崩溃 | 回退 `922a2ba` + `7436b6d`（**必须一起回退**） |
| 数据不见了 | 检查 `shared_data_dir` 是否被改；旧数据仍在旧包名目录或 `/sdcard/rime` |
| APK 装不上/签名冲突 | 卸载后重装；确认 `versionCode` 递增、签名一致（见 §6.3） |
| 16 KB 弹窗复发 | 确认 `.so` 的 LOAD 段 `0x4000`、APK `zipalign -P 16` 通过（02 §7） |
| 分支重命名搞乱 | 远端保留旧分支直到验证完成；本地 `git branch -m` 可随时改回 |
