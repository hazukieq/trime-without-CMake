# 00 · 项目总览

> 基线：`dev` 分支 HEAD `68f876f`（2026-11-08，`docs: add hkrime developer documentation set`）。
> 所有行号以该提交为准；若工作树有未提交改动，行号可能偏移，以内容为准。

## 目录

1. [一句话定位](#1-一句话定位)
2. [当前状态（定稿）](#2-当前状态定稿)
3. [谱系与版本坐标](#3-谱系与版本坐标)
4. [仓库地图](#4-仓库地图)
5. [关键事实速查](#5-关键事实速查)
6. [本轮做了什么](#6-本轮做了什么)
7. [文档索引](#7-文档索引)

---

## 1. 一句话定位

**hkrime = RIME 安卓前端 Trime 的客家话定制分支**，基于 `tumuyan/trime-without-CMake`（去掉 CMake、直接提交预编译 `.so` 的 fork），
包名 `com.hazukieq.hkrime`，冻结在上游 **v3.2.7**（3.3.x 起 Kotlin 化、JNI 接口不兼容）。

- 它**不是**一个新输入法引擎：输入法内核仍是 librime（`1.7.3-74-g43023e16`），hkrime 只做「打包 + 定制 + 改名」。
- 它**目前还没有客家话方案**（本仓库无任何 `.schema.yaml` / 词典），见 [04-hakka.md](04-hakka.md) §4。

## 2. 当前状态（定稿）

| 维度 | 状态 |
|---|---|
| 包名 / namespace | `com.hazukieq.hkrime`（**已完成整包改名**，103 文件 / 391 行） |
| 版本 | `versionName 3.2.7`、`versionCode 20220701`（沿用上游，未自增） |
| SDK | compileSdk 33 / targetSdk 33 / minSdk 21 |
| ABI | **仅 `arm64-v8a`** |
| native 库 | `app/libs/arm64-v8a/librime_jni.so`，**13,587,824 B**，73 符号，已 strip |
| 构建 | `:app:assembleDebug` 24 s 通过；release 需 `-x lintVitalAnalyzeRelease -x lintVitalRelease` |
| 交付物 | `artifacts/hkrime-debug.apk`（11,418,991 B）+ `artifacts/hkrime-release.apk`（已签名，未过 lint） |
| 验证 | 符号契约 73/73、APK 静态校验通过、**真机冒烟未做** |
| 客家话 | ❌ 无方案/无词典/无词库（仅有未启用的 `hks/rime.zip` 解压代码） |

```
已完成  ── 改名 ✅   重编 .so ✅   脚本固化 ✅   删废库 ✅   静态验证 ✅
未完成  ── 真机冒烟 ⬜   客家话方案 ⬜   release lint ⬜   密钥治理 ⬜
```

## 3. 谱系与版本坐标

```text
osfans/trime                     上游 RIME 安卓前端，默认分支 develop
   └─ tag v3.2.7  ◄────────────── 本项目的源码与 native 权威基准（v3.3+ Kotlin 化，不可用）
        │
tumuyan/trime-without-CMake      去掉 CMake 的 fork（直接提交预编译 .so），默认分支 develop
        │
hazukieq/trime-without-CMake     本仓库
        ├─ main      （原 develop）：稳定主线（= without-CMake 原内容，不含客家话定制）
        └─ dev       （原 hakka）  ：hkrime 开发线（本次全部成果所在）
```

**为什么钉死 v3.2.7**：JNI 是**符号名静态绑定**（无 `RegisterNatives`），
`Java_com_osfans_trime_core_Rime_*` 这类名字与 Java 侧包名/方法名逐一对应。
上游 3.3.x 把 `Rime.java` 改成 Kotlin `Rime.kt` 并重排 API，与 v3.2.7 的桥完全不兼容 → 只能用 v3.2.7 的 `app/src/main/jni/`。

## 4. 仓库地图

```text
hkrime/
├── app/
│   ├── build.gradle                    # applicationId/namespace/version/ABI/signing
│   ├── libs/arm64-v8a/
│   │   └── librime_jni.so              # 13,587,824 B —— 唯一 native 库（本地维护，非 Gradle 产物）
│   └── src/main/
│       ├── AndroidManifest.xml         # Application / IME service / 4 个 activity / alias
│       ├── java/com/hazukieq/hkrime/   # 87 个源文件（Java + Kotlin 混合）
│       ├── assets/rime/                # trime.yaml(66 KB)、tongwenfeng.trime.yaml(185 KB)、opencc 词典
│       ├── res/xml/method.xml          # IME 子类型与 settingsActivity（改名易漏点）
│       └── jni/                        # ⚠ 不在仓库内（.gitignore:14），只在构建机上由脚本生成
├── script/
│   ├── build-jni-so.sh                 # 415 行：clone→pin 校验→patch→cmake+ninja→strip→安装
│   ├── verify-jni-symbols.sh           # 237 行：73 符号契约校验
│   └── changelog.sh                    # 上游遗留 8 行（含 $curent 拼写 bug）
├── libs_backup/arm64-v8a/librime_jni.so  # 13,558,064 B 旧库（旧包名，不参与构建，建议清理）
├── docs/                               # 本套文档（+ reports/ 三份原始报告存档）
├── skeys.jks / release.properties      # ⚠ 签名密钥与明文口令**已入库**
├── Makefile                            # 上游产物：build/release/opencc-data，依赖缺失的 jni 子模块
└── build.gradle / settings.gradle / gradle.properties / gradle/wrapper/
```

> `artifacts/` 在**仓库外**：`/home/hazukie/Projects/common/trime-workspace/artifacts/`（APK、`.so`、三份报告、构建日志）。

## 5. 关键事实速查

| 主题 | 事实 | 位置 |
|---|---|---|
| native 加载 | `System.loadLibrary("rime_jni")`（两处已统一） | `core/Rime.java:238`、`data/opencc/OpenCCDictManager.kt:15` |
| native 声明 | Java 72 个 + Kotlin 1 个 = **73** | `core/Rime.java`、`OpenCCDictManager.kt` |
| 符号绑定 | 静态绑定，无 `RegisterNatives` | `.so` 的 `JNI_OnLoad` 只初始化全局引用 |
| 包名硬编码 | `.cc` 73 处 + `jni-utils.h` 的 `FindClass` 7 处 + `traits.app_name` | 见 02 §4 |
| IME 入口 | `TrimeImeService`（空壳）→ `Trime`（1517 行，逻辑全在此） | `ime/core/Trime.java:104` |
| 数据目录 | `getExternalFilesDir(null)/rime`，随 `applicationId` 变 | `data/AppPrefs.kt:346-353` |
| 部署触发 | 广播 `com.hazukieq.hkrime.deploy` / `.sync` | `ime/broadcast/IntentReceiver.kt:63-64` |
| 主题 | `assets/rime/trime.yaml`（66224 B）+ `tongwenfeng.trime.yaml`（184703 B） | `app/src/main/assets/rime/` |
| 签名 | `skeys.jks` + `release.properties`（明文 `263909`）已提交 | 仓库根目录 |
| CI | **无**（5 个 workflow 已被 `0c1e58a` 删除） | `.github/` |

## 6. 本轮做了什么

| # | 动作 | 产物 | 证据 |
|---|---|---|---|
| 1 | 用上游 v3.2.7 重建 JNI 库（新包名） | `librime_jni.so` 13,587,824 B | [reports/BUILD-REPORT-jni.md](reports/BUILD-REPORT-jni.md) |
| 2 | 整包改名 `com.osfans.trime` → `com.hazukieq.hkrime` | 103 文件 / 391 行 | [reports/RENAME-REPORT.md](reports/RENAME-REPORT.md) |
| 3 | 装新库、删 85 MB 废库与 `_1_5`、统一 `loadLibrary` | 符号契约 73/73 | [reports/INTEGRATION-REPORT.md](reports/INTEGRATION-REPORT.md) |
| 4 | 固化构建与校验脚本 | `script/build-jni-so.sh`、`verify-jni-symbols.sh` | [02-jni-so.md](02-jni-so.md) §5-6 |
| 5 | 产出可安装 APK | debug 11.4 MB / release 9.6 MB | 05 §4 |

## 7. 文档索引

| 文档 | 读它解决什么问题 |
|---|---|
| [01-architecture.md](01-architecture.md) | 代码怎么分层、一次按键怎么走、启动/部署时序、数据目录与线程模型 |
| [02-jni-so.md](02-jni-so.md) | `.so` 是什么、为什么曾经崩、**怎么重建、怎么验证** |
| [03-build.md](03-build.md) | 本机怎么把 APK 编出来、环境版本、常见报错 |
| [04-hakka.md](04-hakka.md) | hakka 相对上游改了哪 5 个提交、客家话现状与接入指南 |
| [05-branches-release.md](05-branches-release.md) | 分支模型、改名影响面清单、发布流程、签名与安全 |
| [06-troubleshooting-roadmap.md](06-troubleshooting-roadmap.md) | 出问题按症状查表；技术债与 roadmap |
| [reports/](reports/README.md) | 三份原始构建/改名/集成报告（证据存档） |

**推荐阅读顺序**：00 → 01 → 03（想动手）→ 02（改 native 时）→ 04 → 05 → 06。
