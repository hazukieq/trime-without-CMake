# 04 · hakka 分支定制说明

> 基线 `hakka` @ `7436b6d`。本篇回答三件事：**hakka 相对上游到底改了什么**、
> **「客家话定制」现在到哪一步**、**要真正支持客家话还需要做什么**。

## 目录

1. [结论摘要](#1-结论摘要)
2. [提交时间线](#2-提交时间线)
3. [逐提交拆解](#3-逐提交拆解)
4. [定制清单（按主题）](#4-定制清单按主题)
5. [客家话现状：what exists](#5-客家话现状what-exists)
6. [接入客家话方案（待办指南）](#6-接入客家话方案待办指南)
7. [与上游 develop 的关系](#7-与上游-develop-的关系)

---

## 1. 结论摘要

| 问题 | 答案 |
|---|---|
| hakka 是「客家话版本」吗？ | **意图是，但尚未实现**。代码/资源里没有任何客家话方案、词典或配置 |
| 那一共改了什么？ | 5 个提交：启动引导页 + 权限申请、Android 13 适配、资源瘦身（删 OpenCC 词典）、换 native 库（引入废库事故）、删 CI 与密钥入库 |
| 现在能用吗？ | 能用，但等于「改了名的 Trime + 引导页」，输入方案需用户自备（内置只有 2 个主题 yaml） |
| 客家话相关的唯一痕迹 | `LauchAct.kt:110-129` 里**被注释掉**的 `Hks2local()`，原本打算把 `assets/hks/rime.zip` 解压到 `/sdcard/rime` |

## 2. 提交时间线

```text
f5638bb  chore(gradle): get git branch info from ci     ← merge-base（develop 与 hakka 分叉点）
   │
   ├─ 4af2a94  2023-03-12  add lauchact                  ← 引导页 + 权限 + Android13 适配（最大一笔）
   ├─ 0dfcc31  2023-03-31  q                             ← 重写 LauchAct，换启动图
   ├─ 92c7aa5  2023-09-15  close log                     ← 换 .so(85MB 废库) + proguard + 删 opencc 资源
   ├─ c96f92b  2023-09-15  close log                     ← 补入 13.5MB 库 + libs_backup
   └─ 0c1e58a  2023-09-15  rm key,workflow               ← 删 5 个 CI workflow，放开 jks 忽略
        │
        ├─ 922a2ba  2026-11-08  refactor: rename package to com.hazukieq.hkrime
        └─ 7436b6d  2026-11-08  build(jni): rebuild librime_jni.so  ← 当前 HEAD
```

`git merge-base origin/develop hakka` = `f5638bb`；develop 另有 1 个 hakka 未含的提交 `14d365e "Update build-my-trime.yml"`
（即 develop 只比 merge-base 多一个 CI 文件改动）。

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
| `assets/rime/opencc/**` | 删除 31 个文件 / 60,419 行 | ⚠ 见 §4.4 |
| `AndroidManifest.xml`（4 行） | 删 `WRITE_MEDIA_STORAGE`、加 `MANAGE_EXTERNAL_STORAGE` | 与引导页权限申请配套 |
| `release.properties`、`skeys.jks` | 加入版本库 | ❌ 安全欠债（见 05 §6） |

### 3.4 `c96f92b close log`（2023-09-15）— 补库

补入 `app/libs/arm64-v8a/librime_jni_1_5.so`（13,558,064 B），并把同一文件复制到 `libs_backup/arm64-v8a/librime_jni.so`。
**结果**：仓库里同时存在「废库（`rime_jni.so`）」+「好库（`_1_5.so`）」，且两处 `loadLibrary` 各加载一个 → 双路径随机崩溃（02 §2.1）。

### 3.5 `0c1e58a rm key,workflow`（2023-09-15）— 关灯

删除 `.github/workflows/` 全部 5 个 workflow（464 行：`build-jni/build-my-trime/commit-ci/pull-request-ci/release-ci`），
`.gitignore` +4/-3（放开 `*.jks`、`release.properties` 的忽略，把密钥正式纳入版本库）。

> **代价**：从此没有任何 CI —— 在线编译定制 APK（`build-my-trime.yml` 的参数化打包）能力一并消失。

## 4. 定制清单（按主题）

### 4.1 启动 / 权限引导（hakka 的主要「功能」增量）

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
| 权限动机 | RIME 需要读写共享数据目录（`/sdcard/rime` 或 App 外部目录），targetSdk 33 下需 `MANAGE_EXTERNAL_STORAGE` |
| 状态存储 | `SpvalueStorage`（`util/SpvalueStorage.kt:8`，基于 `PreferenceManager.getDefaultSharedPreferences`）的 `isAll` 布尔 |
| 已知瑕疵 | 拒绝后**无限递归重试**，没有「退出」出口；`isAll` 一旦为 true，权限被回收也不会重新申请 |

### 4.2 构建 / 打包

`namespace` 新增、targetSdk 33、release 启用 proguard、签名配置改为读 `release.properties`、依赖整体升级、git 辅助函数改 `static def`。
详见 [03-build.md](03-build.md) 与 02 §4。

### 4.3 Android 13 适配

`android:enableOnBackInvokedCallback="false"`（禁预测性返回，避免与输入法返回键冲突）、
`AboutActivity` 的 `OnBackInvokedCallback` 判断、`exported` 显式声明、`tools:targetApi="tiramisu"`。

### 4.4 资源瘦身：删掉 OpenCC 词典（影响需知）

`92c7aa5` 删除了 `assets/rime/opencc/` 全部 31 个文件（`HKVariants*`、`TW*`、`s2t/t2s/hk2s/s2hk` 等 .txt 与 .json）。

| 影响 | 说明 |
|---|---|
| APK 体积 | 显著减小（这是原作者的动机；也可能与 85 MB 库导致包体膨胀有关） |
| OpenCC 功能 | `OpenCCDictManager.internalDeploy()` 只遍历**数据目录**里的 text 词典（`OpenCCDictManager.kt:49-58`），没有内置词典 → **不崩，但内置繁简/异体转换能力消失** |
| 用户方案 | 需要 OpenCC 的方案（如带 `simplification` 的配置）需用户自备 `.ocd2`，否则转换静默不生效 |
| `Makefile opencc-data` | 依赖 `app/src/main/jni/OpenCC`（已被 `.gitignore:14` 忽略、仓库内不存在）→ **该目标必然失败** |

`TODO:` 若要恢复 OpenCC，从上游 v3.2.7 取 `app/src/main/assets/rime/opencc/` 原样放回即可（体积约 +几 MB）；
需实测「放回后 APK 体积 / 转换是否生效」，本仓库当前无此验证。

## 5. 客家话现状：what exists

> 用 `grep -rn '客家\|hakka\|Hakka' --include=*.kt --include=*.java --include=*.xml --include=*.yaml .`（排除 `.git`）
> 全仓库 **0 命中**。

| 期待的东西 | 实际 |
|---|---|
| 客家话输入方案（`.schema.yaml`） | ❌ 无 |
| 客家话词典（`.dict.yaml`） | ❌ 无 |
| 客家话主题/键盘布局 | ❌ 无（只有上游 `trime.yaml` + `tongwenfeng.trime.yaml`） |
| 客家话资源包 | ❌ `assets/` 下只有 `rime/`（2 个 yaml）与 `license/`（2 个 html），**没有 `hks/`** |
| 代码层痕迹 | ✅ 仅 `LauchAct.kt:110-129` 注释掉的 `Hks2local()`：`unzipFile(destFile, "hks/rime.zip")` → 解压到 `/sdcard/rime` |

**唯一已落地的「地方化」**：应用显示名改为「方言同文输入法」/「方言同文輸入法」
（`res/values-zh-rCN/strings.xml:23`、`values-zh-rTW/strings.xml:23`；英文 `values/strings.xml:22` 为 `Ftrime`）。

### 为什么是「方言」而不是「客家」

作者显然留了更大的口子：引导页解压 `hks/rime.zip` 的设计，只要换 zip 内容就能装任意方言方案。
但**资源包缺失**，且 `Hks2local()` 被注释、`unzipFile()` 成为死代码 → 该机制从未启用。

`TODO:` 确认原作者的 `hks/rime.zip` 是否还有副本（历史提交中不存在；可能只在本机/网盘）。**目前无据可查，不要臆造**。

## 6. 接入客家话方案（待办指南）

目标：让 hkrime 开箱即用客家话。**全部可在不动代码的前提下完成**（方案即数据）。

### 6.1 最小可用：内置一套方案

```text
app/src/main/assets/rime/
├── trime.yaml                      # 主题（已有）
├── default.custom.yaml             # ← 新增：声明方案列表（否则方案列表为空）
├── hk.schema.yaml                  # ← 新增：客家话方案
├── hk.dict.yaml                    # ← 新增：词典（或引用 .txt 词表）
└── opencc/                         # ← 可选：若方案用到繁简/异体转换
```

`default.custom.yaml` 最小内容：

```yaml
patch:
  schema_list:
    - schema: hk        # ← 与 hk.schema.yaml 的 schema_id 一致
```

**打包路径**：放进 `assets/` 后，`Config.prepareRime()`（`data/Config.java:178-212`）会在首次启动时
递归复制 `assets/rime/**` 到 `sharedDataDir`（默认 `getExternalFilesDir(null)/rime`），
并按版本号决定是否覆盖；复制后会补建空的 `default.custom.yaml`（缺它方案列表为空）。

### 6.2 关键约束（容易踩）

| 约束 | 说明 |
|---|---|
| **方案列表必须有 `default.custom.yaml`** | 代码会「补空文件」，但空的 `schema_list` 仍然没方案 → 必须自带内容 |
| **OpenCC 词典已删** | 若 `hk.schema.yaml` 用了 `simplification` / `opencc_config`，需同时放回 `opencc/` 资源（§4.4） |
| **不能依赖仓库内不存在的词典** | 本仓库不含 `luna_pinyin` 等基础词库，客家话方案需**自带全部**依赖（`*.dict.yaml` + 引用的 `*.txt`） |
| **ABI 只有 arm64-v8a** | 内置方案与 ABI 无关，但装机设备必须是 arm64 |
| **首次部署时机** | `Config.prepareRime` 只在版本变化或目录不存在时复制；开发期反复改 assets 需清 App 数据或提版本号 |

### 6.3 客家话方案的工程要点（供写方案的人）

| 项 | 建议 |
|---|---|
| 拼音/白话字 vs 客家话拼音 | 依据目标方言（梅县/台湾四县/海陆）定 `speller/alphabet` |
| 声调 | 客家话 6 声调，`speller/algebra` 里需处理声调字母（如 `á à â` 或数字 1-6） |
| 词库来源 | 需自备授权允许分发的词表；**注意许可证**，不要直接把闭源词库打进 APK |
| 简繁 | 若面向台湾/香港用户，用 OpenCC 做 `s2t`/`s2hk`，需恢复 opencc 资源 |
| 键盘 | 复用 `trime.yaml` 的 26 键布局即可，客家话无需新键位（除非要声调专用键） |

`TODO:` 选定方言点、确认词库授权、产出 `hk.schema.yaml` + `hk.dict.yaml` 并做真机验证 —— 均**未开始**。

## 7. 与上游 develop 的关系

| 项 | 状态 |
|---|---|
| merge-base | `f5638bb` |
| develop 独有 | `14d365e Update build-my-trime.yml`（仅 CI 文件） |
| hakka 独有 | 5 个提交（§2）+ 本次 2 个（改名、重编 `.so`） |
| 能否直接 merge develop 进 hakka | 能（只涉及 workflow 文件，已不存在 → 会显示删除冲突），**但没必要**：CI 已被主动移除 |
| 分叉后的定位 | `develop`→`main` 保留为 without-CMake 稳定主线；`hakka`→`dev` 成为 hkrime 开发线（见 05 §1） |

> 结论：hakka 不是「跟踪上游的分支」，而是**一次性定制分支**；上游 v3.2.7 是冻结的源码基准，
> 后续上游更新（3.3+）**不能**平滑合并，只能作为「参考实现」选择性回移。
