# 04 · hakka 分支与客家话定制

> 事实快照：`ebb18c1`（当前 HEAD 以 `git log -1` 为准）。本篇回答四件事：
> **hakka 相对上游改了什么**、**客家话数据现在有什么**、**哪些能用哪些还不能用**、**下一步往哪走**。

## 目录

1. [结论摘要](#1-结论摘要)
2. [提交时间线](#2-提交时间线)
3. [逐提交拆解](#3-逐提交拆解)
4. [定制清单（按主题）](#4-定制清单按主题)
5. [客家话现状：已内置的数据](#5-客家话现状已内置的数据)
6. [缺口状态与下一步](#6-缺口状态与下一步)
7. [与 main / 上游 v3.2.7 的关系](#7-与-main--上游-v327-的关系)

---

## 1. 结论摘要

| 问题 | 答案 |
|---|---|
| hakka 是「客家话版本」吗？ | **曾经只有意图，现在完整了**。2023 年的 5 个提交只做引导页/适配/瘦身/换库，无任何客家话资源；`2b50c1f` 并入数据，`726a209` 补齐全部被引用的方案/词典/滤镜 |
| 现在开箱能用吗？ | **能**：3 个方案（客语12键 / 客语26键 / 雲龍國際音標）都有实体、可部署；反查与「情」「联」开关已具备依赖 |
| OpenCC 可用吗？ | **可用**：`92c7aa5` 删掉的 29 个已恢复，`726a209` 又补 5 个（`ywz`/`lenth`/`JPVariantsRev`），共 34 个；实测 4 组转换正确 |
| 还差什么？ | 用户词库（设计取舍）、`ywz`/`lenth` 多值候选的**界面**验证、键盘界面人工冒烟 |
| 与上游的关系 | 上游 v3.2.7 是**冻结的源码基准**，3.3+ 已 Kotlin 化不兼容；本仓库不跟踪上游 |

---

## 2. 提交时间线

```text
f5638bb  chore(gradle): get git branch info from ci     ← merge-base（旧 main 与旧 hakka 分叉点）
   │
   │  ── 2023 年：原作者的一次性定制（5 个提交）──
   ├─ 4af2a94  2023-03-12  add lauchact                  ← 引导页 + 权限 + Android13 适配（最大一笔）
   ├─ 0dfcc31  2023-03-31  q                             ← 重写 LauchAct，换启动图
   ├─ 92c7aa5  2023-09-15  close log                     ← 换 .so(85MB 废库) + proguard + 删 opencc 资源
   ├─ c96f92b  2023-09-15  close log                     ← 补入 13.5MB 库 + libs_backup
   └─ 0c1e58a  2023-09-15  rm key,workflow               ← 删 5 个 CI workflow，密钥入库
        │
        │  ── 2026-11-08：hkrime 改造 ──
        ├─ 922a2ba  refactor: rename package to com.hazukieq.hkrime
        ├─ 7436b6d  build(jni): rebuild librime_jni.so for com.hazukieq.hkrime
        ├─ 68f876f  docs: add hkrime developer documentation set
        ├─ b1d9b57  docs: record completed main/dev branch migration
        ├─ feb4310  docs: resolve verifier deviations DEV-1/2/3
        ├─ 2b50c1f  feat(rime): restore OpenCC dictionaries and bundle hakka rime data  ← ★ 客家话落地
        ├─ 9dc8c32  build(jni): require 16KB max-page-size                             ← 16 KB 对齐
        ├─ bebad9f  build: move release signing out of the repo and add device tests   ← 密钥 + 仪器测试
        ├─ 63fd09e  docs: document OpenCC restore, hakka data, 16KB alignment and signing
        ├─ 726a209  feat(rime): ship the schemas, dicts and OpenCC filters …           ← ★ 缺口全修
        └─ ebb18c1  fix(build): keep release buildable without a signing key …          ← 构建健壮性
```

> 分支模型：旧 `develop`→`main`（保留 without-CMake 主线），旧 `hakka`→`dev`（hkrime 开发线）。详见 [05](05-branches-release.md)。

---

## 3. 逐提交拆解

### 3.1 `4af2a94 add lauchact`（2023-03-12）— 引导与适配

| 类别 | 文件 | 改动 |
|---|---|---|
| 新功能 | `setup/LauchAct.kt`（+174 行，后被重写）、`res/layout/activity_lauch.xml`、`drawable/launchbg.jpeg`（768,467 B） | 首次启动的权限引导页 |
| 新功能 | `setup/SetupActivity.kt`（+28）、`util/SpvalueStorage.kt`（+52） | 两步式引导（启用输入法 → 选择输入法），引导完成态存 SharedPreferences |
| 入口挂接 | `AndroidManifest.xml`（81 行改动）、`PrefMainActivity.kt`（+90） | 注册 `LauchAct`；进设置页时检查 `LauchAct.shouldSetup()` |
| Android 13 | `AboutActivity.kt`、`TrimeApplication.kt`、`ime/core/Trime.java`（45 行） | `OnBackInvokedCallback`、`enableOnBackInvokedCallback="false"`、targetSdk 33 相关空安全 |
| 数据/主题 | `data/Config.java`（28）、`core/Rime.java`（2）、`util/{AppVersionUtils,StringUtils}.kt` | 版本比较、首次部署逻辑微调 |
| 其他 | `settings/*` 若干 fragment、`DialogSeekBarPreference.kt` | 空安全与偏好项修整 |

### 3.2 `0dfcc31 q`（2023-03-31）— 引导页返工

`LauchAct.kt` 重写为 127 行（当前形态）；启动图从 `drawable/launchbg.jpeg` 换成 `mipmap-hdpi/launchbg.jpeg`（318,674 B）；
`activity_lauch.xml` 改 2 行；三套 `strings.xml` 各改 2 行。**净效果：启动页更简单，只做权限申请。**

### 3.3 `92c7aa5 close log`（2023-09-15）— 瘦身与事故

| 改动 | 内容 | 评价 |
|---|---|---|
| `app/build.gradle`（32 行） | compileSdk 31→33、targetSdk 29→33、新增 `namespace`、签名配置改读 `release.properties`、依赖升级 | 合理（现代 SDK 适配） |
| `app/proguard-rules.pro`（+92 行） | 新增 ProGuard 规则并启用 | 合理 |
| `app/libs/arm64-v8a/librime_jni.so` | 13,558,064 B → **85,786,600 B** | ❌ **事故**：换成 librime 1.8.5 调试构建，JNI 接口与 Java 完全不匹配（见 02 §2） |
| `core/Rime.java` | `loadLibrary("rime_jni")` → `"rime_jni_1_5"` | ❌ 与新库配套的半成品改动 |
| `assets/rime/opencc/**` | 删除 **29 个文件 / 59,772 行**（`git show --stat 92c7aa5 -- app/src/main/assets/rime`） | ❌ 当时导致简繁功能失效；`2b50c1f` 恢复 29 个，`726a209` 再补 5 个（§4.4） |
| `AndroidManifest.xml`（4 行） | 删 `WRITE_MEDIA_STORAGE`、加 `MANAGE_EXTERNAL_STORAGE` | 与引导页权限申请配套 |
| `release.properties`、`skeys.jks` | 加入版本库 | ❌ 安全欠债；**已由 `bebad9f` 移出当前树**（05 §6） |

### 3.4 `c96f92b close log`（2023-09-15）— 补库

补入 `app/libs/arm64-v8a/librime_jni_1_5.so`（13,558,064 B），并复制到 `libs_backup/arm64-v8a/librime_jni.so`。
**结果**：仓库里同时存在「废库（`rime_jni.so`）」+「好库（`_1_5.so`）」，且两处 `loadLibrary` 各加载一个 → 双路径随机崩溃（02 §2.1）。
两个库与 `libs_backup/` 均已在 2026-11-08 移除（`922a2ba` / `2b50c1f`）。

### 3.5 `0c1e58a rm key,workflow`（2023-09-15）— 关灯

删除 `.github/workflows/` 全部 5 个 workflow（464 行：`build-jni/build-my-trime/commit-ci/pull-request-ci/release-ci`），
`.gitignore` +4/-3（放开 `*.jks`、`release.properties` 的忽略，把密钥正式纳入版本库）。

> **代价**：从此没有任何 CI —— 在线编译定制 APK（`build-my-trime.yml` 的参数化打包）能力一并消失。
> 至今未恢复（见 06 路线图）。

### 3.6 `2b50c1f`（2026-11-08）— 数据补齐

`feat(rime): restore OpenCC dictionaries and bundle hakka rime data`，**46 files / +269,435 行**：

| 内容 | 明细 |
|---|---|
| 恢复 OpenCC | 从 `92c7aa5^` 取回 `assets/rime/opencc/` **29 个**（15 txt + 14 json） |
| 并入用户 rime 数据 | 从用户 `rime.zip` 导入 **17 个文件**：`trime.yaml`（**保留仓库已改名的版本，未被覆盖**）+ **11 个客家话方案/词典** + **4 个通用配置**（`default.yaml`、`default.custom.yaml`、`custom_phrase.txt`、`symbols.yaml`）+ `user.yaml`（后删除） |
| 清理 | 删除 `libs_backup/arm64-v8a/librime_jni.so`（13,558,064 → 0） |

### 3.7 `9dc8c32`（2026-11-08）— 16 KB 页对齐

`script/build-jni-so.sh` +4 行，固定注入 `-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"`；`.so` 从 13,587,824 → 13,587,744 B，4 个 LOAD 段 `Align=0x4000`。见 [02 §7](02-jni-so.md)。

### 3.8 `bebad9f`（2026-11-08）— 密钥外置 + 真机测试

| 内容 | 明细 |
|---|---|
| 移出密钥 | 删 `release.properties`、`skeys.jks`；新增 `release.properties.example`、`script/gen-keystore.sh`、`.gitignore` 三条 |
| 签名改造 | `app/build.gradle:26-75` 三级查找（仓库根 → `~/.android-keys` → 环境变量），都没有则不签名 |
| 仪器测试 | 新增 `app/src/androidTest/java/com/hazukieq/hkrime/RimeDataTest.kt` 与两条 `androidTestImplementation` 依赖 |

### 3.9 `726a209`（2026-11-08）— 缺口全修（13 files / +72,463 行）

| 内容 | 明细 |
|---|---|
| 新方案 | `hakka_qwerty.schema.yaml`（客语26键，134 行）、`ipa_yunlong.schema.yaml`（42 行）+ `ipa_yunlong.dict.yaml`（660 行）、`luna_pinyin.schema.yaml`（95 行）+ `luna_pinyin.dict.yaml`（70,728 行 / 889,946 B） |
| OpenCC 补料 | 新增 `ywz.{json,txt}`（141 词条）、`lenth.{json,txt}`（31 词条）、`JPVariantsRev.txt`（366 行）；两份 json 重写为规范格式（segmentation=`ocd2`、conversion_chain=`text`） |
| 删除残留 | 删 `assets/rime/user.yaml`（24 行，旧机 `schema_access_time` 引用本仓库不存在的方案） |
| 测试增强 | `RimeDataTest.kt` +38 行：清空 sharedDataDir + `userDataDir/build` 强制 fresh 部署；断言扩到 3 个 schema 名、10 个 bin、18 个 ocd2、`ywz`/`lenth` 可加载 |
| 静态自检 | 新增 `script/verify-rime-assets.py`（183 行，PyYAML），实跑 **53 passed, 0 failed** |

### 3.10 `ebb18c1`（2026-11-08）— 构建健壮性

| 内容 | 明细 |
|---|---|
| 无密钥也能编 release | 只在真的找到 keystore 时才赋 `android.buildTypes.release.signingConfig`（`:67`）；否则产出 `trime-3.2.7-release-unsigned.apk`，而不是报 `SigningConfig "release" is missing required property "storeFile"` |
| `BUILD_INFO` 带提交 | `getCommitInfo()` 改用 `git describe --tags --always`，无 tag 时回退短 sha，格式仍为 `<describe>, <commit date>` |

---

## 4. 定制清单（按主题）

### 4.1 启动 / 权限引导（hakka 的「功能」增量）

```text
冷启动 → TrimeApplication → (点图标) PrefMainActivity
             └─ shouldSetup()==false → LauchAct
                    ├─ requestPermission()（M~Q: 读写存储+悬浮窗；>Q: MANAGE_EXTERNAL_STORAGE+悬浮窗）
                    ├─ 全授予 → SpvalueStorage 写 isAll=true → SetupActivity（启用/选择输入法）
                    └─ 拒绝 → Toast → 递归重试
                 shouldSetup()==true → finish()
```

| 点 | 说明 |
|---|---|
| 权限动机 | RIME 需读写共享数据目录（`/sdcard/rime` 或 App 外部目录），targetSdk 33 下需 `MANAGE_EXTERNAL_STORAGE` |
| 状态存储 | `SpvalueStorage`（`util/SpvalueStorage.kt:8`，基于 `PreferenceManager.getDefaultSharedPreferences`）的 `isAll` |
| 已知瑕疵 | 拒绝后**无限递归重试**，没有退出出口；`isAll` 一旦 true，权限被回收也不会重新申请（真机上外加重装会重置授权，见 06 §4.2） |

### 4.2 构建 / 打包

`namespace` 新增、targetSdk 33、release 启用 proguard、签名配置外置、依赖整体升级、git 辅助函数改 `static def`、新增 androidTest 依赖。
详见 [03-build.md](03-build.md)。

### 4.3 Android 13 适配

`android:enableOnBackInvokedCallback="false"`（禁预测性返回，避免与输入法返回键冲突）、
`AboutActivity` 的 `OnBackInvokedCallback` 判断、`exported` 显式声明、`tools:targetApi="tiramisu"`。

### 4.4 OpenCC 资源：删了又恢复，再补齐

| 时间 | 事件 |
|---|---|
| `92c7aa5`（2023-09-15） | 删除 `assets/rime/opencc/` **29 个文件 / 59,772 行** |
| 当时影响 | `OpenCCDictManager.internalDeploy()` 遍历数据目录无词典可转 → **内置简繁/异体转换能力消失**（不崩，静默失效） |
| `2b50c1f`（2026-11-08） | 从 `92c7aa5^` 恢复 29 个（15 txt + 14 json） |
| `726a209`（2026-11-08） | 补 `ywz.{json,txt}`、`lenth.{json,txt}`、`JPVariantsRev.txt`，共 **34 个（18 txt + 16 json）** |
| 现状 | fresh 部署生成 **18 个 `.ocd2`**；实测 `s2t/t2s/s2tw/s2twp` 均正确；`ver.1.1.4` |

机制与验证细节见 [07-opencc-and-rime-data.md](07-opencc-and-rime-data.md)。

---

## 5. 客家话现状：已内置的数据

`app/src/main/assets/rime/` 共 **23 项** = 22 个文件 + `opencc/` 目录。

### 5.1 方案与词典

| 文件 | 规模 | 说明 |
|---|---|---|
| `hakka_jiugong.schema.yaml` | 175 行 | 方案「**客语12键**」，`schema_id: hakka_jiugong`，`script_translator` |
| `hakka_qwerty.schema.yaml` | 134 行 | 方案「**客语26键**」，作者 叶月绘梨依，`prism: hakka_qwerty`，反查 `luna_pinyin` |
| `ipa_yunlong.schema.yaml` | 42 行 | 方案「**雲龍國際音標**」，`table_translator` |
| `luna_pinyin.schema.yaml` | 95 行 | 方案「**朙月拼音**」，供 `dependencies` 与反查使用 |
| `hakka.dict.yaml` | 10,545 行 | 主字典 `name: hakka`，`version: "20.20.20"`，`sort: by_weight` |
| `hakka_zon.dict.yaml` | 3,538 | 扩展表 |
| `hakka_website.dict.yaml` | 5,252 | 网络词 |
| `hakka_sougou_words.dict.yaml` | 157,336 | 搜狗词库（最大） |
| `hakka_poems.dict.yaml` | 17,871 | 诗词 |
| `hakka_places.dict.yaml` | 4,762 | 地名 |
| `hakka_oftenwords.dict.yaml` | 1,206 | 常用词 |
| `hakka_mobilewords.dict.yaml` | 2,949 | 手机词 |
| `hakka_cn_en.dict.yaml` | 1,681 | 中英混合 |
| `ipa_yunlong.dict.yaml` | 660 行 | IPA 码表 |
| `luna_pinyin.dict.yaml` | 70,728 行 / 889,946 B | 朙月拼音词典（自包含） |

主字典通过 `import_tables` 引入上述 8 张 `hakka_*` 扩展表（`hakka.dict.yaml:28-36`）。
方言点：**广西玉林兴业县高峰乡客家话**，作者 hazuki erii / 叶月绘梨依，发音取自高峰镇龙文村龙源坪（`hakka.dict.yaml:1-26`）。

### 5.2 配置与主题

| 文件 | 作用 |
|---|---|
| `default.custom.yaml` | 方案列表 `hakka_jiugong / hakka_qwerty / ipa_yunlong`（**三项均有实体**），`"menu/page_size": 5` |
| `default.yaml` | RIME 默认配置（其 `schema_list` 被 custom 覆盖） |
| `hakka_theme.trime.yaml` | 主题「客家同文风」（3,923 行，`config_version: "3.0"`） |
| `symbols.yaml` | 标点/符号表 |
| `custom_phrase.txt` | 自定义短语表头 |
| ~~`user.yaml`~~ | **已删除**（`726a209`），旧机方案访问时间残留 |

主题里的 `run`/`broadcast` 已指向新包名（`trime.yaml:940-941`、`:953-954`；`tongwenfeng.trime.yaml:3966-3967`；`hakka_theme.trime.yaml:3767-3768`），assets 内 `com.osfans`/`com.tumuyan` 引用 **0 处**。

### 5.3 实测已能做到（第二轮真机）

| 能力 | 状态 | 证据 |
|---|---|---|
| 首次部署 assets → sharedDataDir | ✅ | `RimeDataTest` 断言关键文件存在 |
| 编译客家话词典 → `hakka.table.bin/reverse.bin/prism.bin` | ✅ | fresh 部署 `build/` 实测 10 个 bin |
| 三个方案都可部署、出现在方案列表 | ✅ | 断言「客语12键/客语26键/雲龍」三项；`hakka_qwerty.prism.bin`、`ipa_yunlong.*` 存在 |
| 简繁 / 台湾转换 | ✅ | `s2t`、`t2s`、`s2tw`、`s2twp` 四组断言 |
| 反查（`` ` `` 普通话拼音） | ✅（依赖已备） | `luna_pinyin` 词典随包；实际反查候选需界面冒烟 |
| 「情」「联」开关 | ✅（配置已备） | `ywz.ocd2` / `lenth.ocd2` 生成、`openccConvert` 可加载；多值候选需界面冒烟 |
| 主题「客家同文风」可选 | ✅（资源在） | 未做 UI 目视验证 |
| 键盘界面打字/上屏/翻页 | ⬜ | **未人工冒烟**（仅数据链路） |

---

## 6. 缺口状态与下一步

### 6.1 原缺口：全部修复（`726a209`）

| # | 原缺口 | 现状 | 证据 |
|---|---|---|---|
| 1 | `luna_pinyin` 词典缺失 | ✅ 已提供 | `luna_pinyin.schema.yaml` + `luna_pinyin.dict.yaml`（889,946 B） |
| 2 | `hakka_qwerty` 方案缺失 | ✅ 已提供 | `hakka_qwerty.schema.yaml`（134 行） |
| 3 | `ipa_yunlong` 方案缺失 | ✅ 已提供 | `ipa_yunlong.schema.yaml` + `.dict.yaml` |
| 4 | `ywz.json` / `lenth.json` 缺失 | ✅ 已提供并重写 | `opencc/ywz.{json,txt}`、`opencc/lenth.{json,txt}` |
| 5 | `user.yaml` 旧机残留 | ✅ 已删除 | `726a209` |
| — | `jp2t.json` 反向依赖缺失 | ✅ 已补 | `opencc/JPVariantsRev.txt`（366 行） |

静态自检：`python3 script/verify-rime-assets.py` → **53 passed, 0 failed**。

### 6.2 仍需保留的「未验证 / 设计取舍」

| 项 | 性质 | 说明 |
|---|---|---|
| 未预置 `*.userdb` | **设计取舍** | 用户词频属个人数据，librime 首次部署自建；预置会把「别人的词频」带进新装环境 |
| `ywz`/`lenth` 多值候选 | **未验证（界面）** | 多值语义依赖 librime `simplifier` 的 `Opencc::ConvertWord`（取全部 Values），而 JNI `openccConvert` 只取首值；测试只断言配置可加载 |
| 无键盘界面冒烟 | **未验证** | 启用/选择/打字/上屏/翻页/简繁切换/主题切换/部署按钮均未人工验证 |
| 单 ABI | 设计取舍 | 只编 `arm64-v8a`，32 位设备/模拟器不可用 |
| 无 CI | 欠债 | 5 个 workflow 已删，回归靠本地脚本 |
| `default.yaml` 仍列 `bopomofo_tw`/`luna_pinyin_simp` | 设计取舍 | 被 `default.custom.yaml` 覆盖，用户不可见；保留以贴合上游 |

### 6.3 下一步（按优先级）

1. **真机 IME 冒烟**（P0）：键盘布局、主题、候选翻页、简繁切换、「情」「联」多值候选、部署/同步按钮（主题里的 `broadcast` 目标）。
2. **把静态自检接进流程**（P1）：改 assets 后跑 `verify-rime-assets.py`；有条件时加进 CI 草案。
3. **评估体积**（P2）：debug 13.44 MB / release 11.41 MB，其中 `luna_pinyin.dict.yaml` 占 890 KB；如体积敏感可考虑裁词。
4. 视需要预置词库并权衡隐私/体积。
5. 补 CI（最小：`assembleDebug` + 两个校验脚本）。

> 原则：**方案是数据，不是代码**。以上 1–4 都可只改 `assets/rime/` 完成，不需要动 Java/Kotlin。

### 6.4 如何验证补料成功

```bash
# 静态（无需设备）
python3 script/verify-rime-assets.py      # 期望 53 passed, 0 failed

# 装新 APK 后（测试会先清空数据目录强制 fresh 部署）
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
timeout 300 adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
# 断言：3 个 schema 名、10 个 bin、18 个 ocd2、ywz/lenth 可加载、四组转换
```

---

## 7. 与 main / 上游 v3.2.7 的关系

| 项 | 状态 |
|---|---|
| 分支模型 | 旧 `develop` → **`main`**（without-CMake 稳定主线）；旧 `hakka` → **`dev`**（hkrime 开发线） |
| `main` 与 `dev` 分叉点 | `f5638bb`；`main` 仅比 dev 的历史多一个 CI 文件改动 `14d365e` |
| 上游 v3.2.7 | 冻结的源码基准（annotated tag → commit `8f12815`，2022-07-02）；子模块 pin 记录在 `script/build-jni-so.sh` |
| 能否直接 merge 上游更新 | **不能**。v3.3+ 已 Kotlin 化、包结构与 JNI 均变，只能作参考实现选择性回移 |
| 未来若想跟上游 | 需重做包名/JNI patch 与数据迁移，成本等同一次重移植；不如维持「数据在 assets、代码冻结」策略 |

> 一句话：**hkrime 是「v3.2.7 的方言化分支 + 自维护 JNI 构建」**，不是上游的 follower。
