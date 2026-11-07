# 04 · hakka 分支与客家话定制

> 基线 `dev` @ `bebad9f`。本篇回答四件事：**hakka 相对上游改了什么**、**客家话数据现在有什么**、
> **哪些能用哪些还不能用**、**下一步往哪走**。

## 目录

1. [结论摘要](#1-结论摘要)
2. [提交时间线](#2-提交时间线)
3. [逐提交拆解](#3-逐提交拆解)
4. [定制清单（按主题）](#4-定制清单按主题)
5. [客家话现状：已内置的数据](#5-客家话现状已内置的数据)
6. [已知缺口与下一步](#6-已知缺口与下一步)
7. [与 main / 上游 v3.2.7 的关系](#7-与-main--上游-v327-的关系)

---

## 1. 结论摘要

| 问题 | 答案 |
|---|---|
| hakka 是「客家话版本」吗？ | **曾经只有意图，现在有数据了**。2023 年的 5 个提交只做了引导页/适配/瘦身/换库，没有任何客家话资源；2026-11-08 的 `2b50c1f` 才把 16 个客家话文件并入 assets |
| 现在开箱能用吗？ | **能**：方案「客语12键」可部署、可出候选，简繁转换可用；但方案菜单里有 2 项缺失方案，反查/「情」「联」开关缺料 |
| OpenCC 可用吗？ | **可用**。`92c7aa5` 删掉的 29 个文件已由 `2b50c1f` 恢复，实测 4 组转换正确 |
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
        │  ── 2026-11-08：hkrime 改造（本轮）──
        ├─ 922a2ba  refactor: rename package to com.hazukieq.hkrime
        ├─ 7436b6d  build(jni): rebuild librime_jni.so for com.hazukieq.hkrime
        ├─ 68f876f  docs: add hkrime developer documentation set
        ├─ b1d9b57  docs: record completed main/dev branch migration
        ├─ feb4310  docs: resolve verifier deviations DEV-1/2/3
        ├─ 2b50c1f  feat(rime): restore OpenCC dictionaries and bundle hakka rime data  ← ★ 客家话落地
        ├─ 9dc8c32  build(jni): require 16KB max-page-size                             ← 16 KB 对齐
        └─ bebad9f  build: move release signing out of the repo and add device tests   ← 密钥 + 仪器测试
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
| `assets/rime/opencc/**` | 删除 31 个文件 / 60,419 行 | ❌ 当时导致简繁功能失效；**已由 `2b50c1f` 恢复 29 个**（§4.4） |
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

### 3.6 `2b50c1f`（2026-11-08）— 数据补齐（本轮关键）

`feat(rime): restore OpenCC dictionaries and bundle hakka rime data`，**46 files / +269,435 行**：

| 内容 | 明细 |
|---|---|
| 恢复 OpenCC | 从 `92c7aa5^` 取回 `assets/rime/opencc/` **29 个**（15 txt + 14 json） |
| 并入客家话数据 | 16 个文件：9 个 `.dict.yaml`（主字典 + 8 张扩展表）、1 个 `.schema.yaml`、1 个主题 `.trime.yaml`、`symbols.yaml`、`default.yaml`、`default.custom.yaml`、`user.yaml`、`custom_phrase.txt` |
| 清理 | 删除 `libs_backup/arm64-v8a/librime_jni.so`（13,558,064 → 0） |

### 3.7 `9dc8c32`（2026-11-08）— 16 KB 页对齐

`script/build-jni-so.sh` +4 行，固定注入 `-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"`；`.so` 从 13,587,824 → 13,587,744 B，4 个 LOAD 段 `Align=0x4000`。见 [02 §7](02-jni-so.md)。

### 3.8 `bebad9f`（2026-11-08）— 密钥外置 + 真机测试

| 内容 | 明细 |
|---|---|
| 移出密钥 | 删 `release.properties`、`skeys.jks`；新增 `release.properties.example`、`script/gen-keystore.sh`、`.gitignore` 三条 |
| 签名改造 | `app/build.gradle:45-79` 三级查找（仓库根 → `~/.android-keys` → 环境变量），都没有则不签名 |
| 仪器测试 | 新增 `app/src/androidTest/java/com/hazukieq/hkrime/RimeDataTest.kt`（111 行）与两条 `androidTestImplementation` 依赖 |

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

### 4.4 OpenCC 资源：删了又恢复

| 时间 | 事件 |
|---|---|
| `92c7aa5`（2023-09-15） | 删除 `assets/rime/opencc/` 全部 31 个文件（15 txt + 14 json + 2 项） |
| 当时影响 | `OpenCCDictManager.internalDeploy()` 遍历数据目录无词典可转 → **内置简繁/异体转换能力消失**（不崩，静默失效） |
| `2b50c1f`（2026-11-08） | 从 `92c7aa5^` 恢复 29 个（15 txt + 14 json） |
| 现状 | 部署生成 15 个 `.ocd2`；实测 `s2t/t2s/s2tw/s2twp` 均正确；`ver.1.1.4` |

机制与验证细节见 [07-opencc-and-rime-data.md](07-opencc-and-rime-data.md)。

---

## 5. 客家话现状：已内置的数据

`app/src/main/assets/rime/` 共 **19 项**，其中 16 个是客家话相关文件。

### 5.1 方案与词典

| 文件 | 规模 | 说明 |
|---|---|---|
| `hakka_jiugong.schema.yaml` | 175 行 | 方案「**客语12键**」，`schema_id: hakka_jiugong` |
| `hakka.dict.yaml` | 10,545 行 | 主字典 `name: hakka`，`version: "20.20.20"`，`sort: by_weight` |
| `hakka_zon.dict.yaml` | 3,538 | 扩展表 |
| `hakka_website.dict.yaml` | 5,252 | 网络词 |
| `hakka_sougou_words.dict.yaml` | 157,336 | 搜狗词库（最大） |
| `hakka_poems.dict.yaml` | 17,871 | 诗词 |
| `hakka_places.dict.yaml` | 4,762 | 地名 |
| `hakka_oftenwords.dict.yaml` | 1,206 | 常用词 |
| `hakka_mobilewords.dict.yaml` | 2,949 | 手机词 |
| `hakka_cn_en.dict.yaml` | 1,681 | 中英混合 |

主字典通过 `import_tables` 引入上述 8 张扩展表（`hakka.dict.yaml:28-36`）。
方言点：**广西玉林兴业县高峰乡客家话**，作者 hazuki erii / 叶月绘梨依，发音取自高峰镇龙文村龙源坪（`hakka.dict.yaml:1-26`）。

### 5.2 配置与主题

| 文件 | 作用 |
|---|---|
| `default.custom.yaml` | 方案列表 `hakka_jiugong / hakka_qwerty / ipa_yunlong`，`"menu/page_size": 5` |
| `default.yaml` | RIME 默认配置（其 `schema_list` 被 custom 覆盖） |
| `hakka_theme.trime.yaml` | 主题「客家同文风」（3,923 行，`config_version: "3.0"`） |
| `symbols.yaml` | 标点/符号表 |
| `custom_phrase.txt` | 自定义短语表头 |
| `user.yaml` | 用户状态残留（旧方案访问时间） |

主题里的 `run`/`broadcast` 已指向新包名（`trime.yaml:940-941`、`:953-954`；`tongwenfeng.trime.yaml:3966-3967`；`hakka_theme.trime.yaml:3767-3768`），assets 内 `com.osfans`/`com.tumuyan` 引用 **0 处**。

### 5.3 实测已能做到

| 能力 | 状态 | 证据 |
|---|---|---|
| 首次部署 assets → sharedDataDir | ✅ | `RimeDataTest` 断言 5 个关键文件存在 |
| 编译客家话词典 → `hakka.table.bin/reverse.bin/prism.bin` | ✅ | 真机 `build/` 实测 |
| 客家话方案出候选、可上屏 | ✅（候选逻辑层面） | `testInstrumentationRunner` 部署通过；UI 冒烟未做 |
| 简繁 / 台湾转换 | ✅ | `s2t`、`t2s`、`s2tw`、`s2twp` 四组断言 |
| 主题「客家同文风」可选 | ✅（资源在） | 未做 UI 目视验证 |
| 反查（`` ` `` 普通话拼音） | ❌ | 依赖 `luna_pinyin` 词典，仓库未带 |
| 「情」「联」开关 | ❌ | 依赖 `ywz.json` / `lenth.json`，仓库未带 |

---

## 6. 已知缺口与下一步

### 6.1 缺口清单

| # | 缺口 | 影响 | 补法 |
|---|---|---|---|
| 1 | `luna_pinyin` 词典缺失 | `dependencies: [luna_pinyin]` 警告；反查 `` ` `` 无结果 | 放入 `luna_pinyin.schema.yaml` + `luna_pinyin.dict.yaml`（含 `essay.txt` 等依赖） |
| 2 | `hakka_qwerty` 方案缺失 | 方案菜单里选中后无方案可部署 | 补 `hakka_qwerty.schema.yaml`，或从 `default.custom.yaml` 移除该项 |
| 3 | `ipa_yunlong` 方案缺失 | 同上 | 补 `ipa_yunlong.schema.yaml`，或移除 |
| 4 | `ywz.json` / `lenth.json` 缺失 | 「情」「联」两个开关无效 / 部署告警 | 从方案作者处取这两个 OpenCC 配置，或删掉对应 `switches`/`filters` |
| 5 | 未预置用户词库 | 无个人词频（设计取舍） | 如需预置，放 `*.userdb`（体积与隐私需权衡） |
| 6 | 无真机 IME 界面冒烟 | 只知道数据链路 OK，不知键盘实际体验 | 手工：启用输入法 → 选择 → 打字 → 看候选/主题 |
| 7 | 无 CI | 回归靠本地 | 见 05 §8 / 06 §5 |

### 6.2 下一步（按优先级）

1. **补 `luna_pinyin`**（让反查与 `dependencies` 正常）——最影响「像不像完整 RIME」。
2. **清理方案菜单**：给 `hakka_qwerty`/`ipa_yunlong` 补文件，或从 `default.custom.yaml` 删掉；二选一，不要留「菜单有、点开空」。
3. **补 `ywz.json`/`lenth.json`** 或去掉这两个开关。
4. **真机 IME 冒烟**：键盘布局、主题、候选翻页、简繁切换、部署/同步按钮（主题里的 `broadcast` 目标）。
5. 视需要预置词库，并评估 APK 体积（当前 debug 17.2 MB / release 11.0 MB）。

> 原则：**方案是数据，不是代码**。以上 1–4 都可只改 `assets/rime/` 完成，不需要动 Java/Kotlin。

### 6.3 如何验证补料成功

```bash
# 装新 APK 后（或清应用数据）
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow
timeout 300 adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
# 断言会检查 ocd2 数量、hakka.table.bin、四组转换、schema 列表
```

补 `luna_pinyin` 后建议在 `RimeDataTest` 里加一条「schema 列表含 `luna_pinyin`」断言（`TODO:` 未做）。

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
