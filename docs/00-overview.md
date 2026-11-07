# 00 · 项目总览

> 基线：`dev` @ `bebad9f`（2026-11-08 第二轮）。所有行号以该提交为准；工作树有未提交改动时以内容为准。

## 目录

1. [一句话定位](#1-一句话定位)
2. [当前状态（定稿）](#2-当前状态定稿)
3. [谱系与版本坐标](#3-谱系与版本坐标)
4. [仓库地图](#4-仓库地图)
5. [关键事实速查](#5-关键事实速查)
6. [已完成的工作（两轮）](#6-已完成的工作两轮)
7. [文档索引](#7-文档索引)

---

## 1. 一句话定位

**hkrime = RIME 安卓前端 Trime 的客家话定制分支**，基于 `tumuyan/trime-without-CMake`（去掉 CMake、直接提交预编译 `.so` 的 fork），
包名 `com.hazukieq.hkrime`，冻结在上游 **v3.2.7**（3.3.x 起 Kotlin 化、JNI 接口不兼容）。

- 它**不是**一个新输入法引擎：内核仍是 librime（`1.7.3-74-g43023e16`），hkrime 做「打包 + 数据 + 定制 + 改名」。
- 它**现在有客家话数据了**：内置方案「客语12键」+ 9 张词典 + OpenCC 资料，开箱可用；仍有缺料项，见 [04](04-hakka.md) §6。

## 2. 当前状态（定稿）

| 维度 | 状态 |
|---|---|
| 包名 / namespace | `com.hazukieq.hkrime`（**已完成整包改名**，103 文件 / 391 行） |
| 版本 | `versionName 3.2.7`、`versionCode 20220701`（沿用上游，未自增） |
| SDK | compileSdk 33 / targetSdk 33 / minSdk 21 |
| ABI | **仅 `arm64-v8a`** |
| native 库 | `app/libs/arm64-v8a/librime_jni.so`，**13,587,744 B**，73 符号，stripped，**16 KB 对齐**（LOAD=0x4000） |
| 数据 | assets/rime 19 项：16 个客家话文件 + 2 个主题 + `opencc/`（29 个：15 txt + 14 json） |
| 构建 | `:app:assembleDebug` 通过；release 需 `-x lintVitalAnalyzeRelease -x lintVitalRelease` |
| 签名 | 密钥**已移出仓库**，走 `~/.android-keys/` 或环境变量；新证书 SHA-256 `d9d7d91a…93cb` |
| 交付物 | debug 17,233,842 B；release 11,004,041 B（新 key 签名，verify 通过） |
| 验证 | 符号契约 73/73；**真机仪器测试 `OK (1 test)`**（部署 + ocd2 + 4 组转换 + hakka 词典编译）；IME 界面人工冒烟未做 |
| 客家话 | ✅ 方案/词典已内置；⚠ 缺 `luna_pinyin`、`hakka_qwerty`、`ipa_yunlong`、`ywz.json`、`lenth.json` |

```text
已完成  ── 改名 ✅  重编 .so ✅  16KB ✅  脚本固化 ✅  OpenCC 恢复 ✅  客家话数据 ✅  真机数据链路 ✅  密钥外置 ✅
未完成  ── IME 界面冒烟 ⬜  方案缺料 ⬜  release lint ⬜  历史密钥清理 ⬜  CI ⬜
```

## 3. 谱系与版本坐标

```text
osfans/trime                     上游 RIME 安卓前端，默认分支 develop
   └─ tag v3.2.7  ◄────────────── 本项目的源码与 native 权威基准（v3.3+ Kotlin 化，不可用）
        │
tumuyan/trime-without-CMake      去掉 CMake 的 fork（直接提交预编译 .so），默认分支 develop
        │
hazukieq/trime-without-CMake     本仓库
        ├─ main      （原 develop）：稳定主线（= without-CMake 原内容，com.tumuyan.trime）
        └─ dev       （原 hakka）  ：hkrime 开发线（本仓库全部成果所在）
```

**为什么钉死 v3.2.7**：JNI 是**符号名静态绑定**（无 `RegisterNatives`），
`Java_com_osfans_trime_core_Rime_*` 这类名字与 Java 侧包名/方法名逐一对应。
上游 3.3.x 把 `Rime.java` 改成 Kotlin `Rime.kt` 并重排 API，与 v3.2.7 的桥完全不兼容 → 只能用 v3.2.7 的 `app/src/main/jni/`。

## 4. 仓库地图

```text
hkrime/
├── app/
│   ├── build.gradle                    # applicationId/namespace/version/ABI/签名三级查找
│   ├── libs/arm64-v8a/
│   │   └── librime_jni.so              # 13,587,744 B —— 唯一 native 库（本地维护，非 Gradle 产物）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # Application / IME service / 4 个 activity / alias
│       │   ├── java/com/hazukieq/hkrime/  # 87 个源文件（Java + Kotlin 混合）
│       │   ├── assets/rime/            # 19 项：客家话方案/词典/主题/符号 + opencc/ 29 个
│       │   ├── res/xml/method.xml      # IME 子类型与 settingsActivity（改名易漏点）
│       │   └── jni/                    # ⚠ 不在仓库内（.gitignore:14），只在构建机上由脚本生成
│       └── androidTest/java/com/hazukieq/hkrime/RimeDataTest.kt  # 真机仪器测试
├── script/
│   ├── build-jni-so.sh                 # 415 行：clone→pin 校验→patch→cmake+ninja→strip→安装（含 16KB 链接参数）
│   ├── verify-jni-symbols.sh           # 237 行：73 符号契约校验
│   ├── gen-keystore.sh                 # 79 行：生成仓库外 release 密钥
│   └── changelog.sh                    # 上游遗留 8 行（含 $curent 拼写 bug）
├── docs/                               # 本套文档（+ reports/ 报告存档）
├── release.properties.example          # 占位模板（真实密钥在 ~/.android-keys/）
├── keystore.tar.enc                    # 10,256 B 上游遗留加密包（口令未知）
├── Makefile                            # 上游产物：build/release/opencc-data，依赖缺失的 jni 子模块
└── build.gradle / settings.gradle / gradle.properties / gradle/wrapper/
```

> `artifacts/` 在**仓库外**：`/home/hazukie/Projects/common/trime-workspace/artifacts/`（APK、`.so`、报告、构建日志、`SHA256SUMS`）。
> 已删除：`libs_backup/`、`app/libs/arm64-v8a/librime_jni_1_5.so`、仓库根 `skeys.jks` / `release.properties`。

## 5. 关键事实速查

| 主题 | 事实 | 位置 |
|---|---|---|
| native 加载 | `System.loadLibrary("rime_jni")`（两处已统一） | `core/Rime.java:238`、`data/opencc/OpenCCDictManager.kt:15` |
| native 声明 | Java 72 个 + Kotlin 1 个 = **73** | `core/Rime.java`、`OpenCCDictManager.kt` |
| 符号绑定 | 静态绑定，无 `RegisterNatives` | `.so` 的 `JNI_OnLoad` 只初始化全局引用 |
| 包名硬编码 | `.cc` 73 处 + `jni-utils.h` 的 `FindClass` 7 处 + `traits.app_name` | 见 [02](02-jni-so.md) §4 |
| IME 入口 | `TrimeImeService`（空壳）→ `Trime`（1517 行，逻辑全在此） | `ime/core/Trime.java:104` |
| 数据目录 | `getExternalFilesDir(null)/rime`，随 `applicationId` 变 | `data/AppPrefs.kt:346-353` |
| 部署触发 | 广播 `com.hazukieq.hkrime.deploy` / `.sync` | `ime/broadcast/IntentReceiver.kt:63-64` |
| 主题 | `trime.yaml`（66 KB）、`tongwenfeng.trime.yaml`（185 KB）、`hakka_theme.trime.yaml`（客家同文风，3,923 行） | `app/src/main/assets/rime/` |
| 签名 | 仓库外密钥；构建查找顺序：仓库根 → `~/.android-keys/` → `HKREME_*` 环境变量 | `app/build.gradle:45-79` |
| 仪器测试 | `RimeDataTest.deployAndOpenccWorks()`，必须 `timeout` + `appops` 授权 | `app/src/androidTest/...` |
| CI | **无**（5 个 workflow 已被 `0c1e58a` 删除） | `.github/` |

## 6. 已完成的工作（两轮）

### 第一轮（2026-11-08 上午）：改名 + 重建 `.so`

| # | 动作 | 产物 | 证据 |
|---|---|---|---|
| 1 | 用上游 v3.2.7 重建 JNI 库（新包名） | `librime_jni.so` | [reports/BUILD-REPORT-jni.md](reports/BUILD-REPORT-jni.md) |
| 2 | 整包改名 `com.osfans.trime` → `com.hazukieq.hkrime` | 103 文件 / 391 行 | [reports/RENAME-REPORT.md](reports/RENAME-REPORT.md) |
| 3 | 装新库、删 85 MB 废库与 `_1_5`、统一 `loadLibrary` | 符号契约 73/73 | [reports/INTEGRATION-REPORT.md](reports/INTEGRATION-REPORT.md) |
| 4 | 固化构建与校验脚本 | `build-jni-so.sh`、`verify-jni-symbols.sh` | [02](02-jni-so.md) §5-6 |
| 5 | 产出可安装 APK | debug / release | [05](05-branches-release.md) §4 |

### 第二轮（2026-11-08 下午）：数据 + 16 KB + 密钥 + 真机

| # | 动作 | 产物 | 证据 |
|---|---|---|---|
| 6 | 恢复 OpenCC 资源（29 个） | 简繁/台湾转换可用，ocd2 15 个 | [07](07-opencc-and-rime-data.md) |
| 7 | 并入客家话数据（16 个文件） | 方案「客语12键」+ 9 张词典 | [04](04-hakka.md) §5 |
| 8 | 16 KB 页对齐 | `.so` 13,587,744 B，LOAD=0x4000 | [02](02-jni-so.md) §7 |
| 9 | 签名密钥外置 + `gen-keystore.sh` | 新证书 `d9d7d91a…93cb` | [05](05-branches-release.md) §6 |
| 10 | 真机仪器测试 | `OK (1 test)` | [reports/DEVICE-TEST-REPORT.md](reports/DEVICE-TEST-REPORT.md) |

> 提交对照：`2b50c1f`（数据）、`9dc8c32`（16 KB）、`bebad9f`（密钥 + 测试）。

## 7. 文档索引

| 文档 | 读它解决什么问题 |
|---|---|
| [01-architecture.md](01-architecture.md) | 代码怎么分层、一次按键怎么走、启动/部署时序、数据目录与线程模型 |
| [02-jni-so.md](02-jni-so.md) | `.so` 是什么、为什么曾经崩、**怎么重建/验证/16 KB 对齐** |
| [03-build.md](03-build.md) | 环境版本、编 APK、编 `.so`、**签名配置**、**仪器测试步骤**、常见报错 |
| [04-hakka.md](04-hakka.md) | hakka 的提交史、**客家话数据清单**、缺口与下一步 |
| [05-branches-release.md](05-branches-release.md) | 分支模型、改名影响面、发布流程、**签名与密钥安全**、数据迁移 |
| [06-troubleshooting-roadmap.md](06-troubleshooting-roadmap.md) | 按症状查表、递归/16KB 详解、技术债与 roadmap |
| [07-opencc-and-rime-data.md](07-opencc-and-rime-data.md) | OpenCC 三种文件关系、部署管线、客家话数据、缺料清单、扩充指引 |
| [reports/](reports/README.md) | 原始构建/改名/集成/真机报告（证据存档；部分数值为历史快照） |

**推荐阅读顺序**：00 → 01 → 03（想动手）→ 07（改数据）→ 02（改 native 时）→ 04 → 05 → 06。
