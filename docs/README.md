# hkrime 开发文档

**hkrime** = RIME 安卓前端 [Trime](https://github.com/osfans/trime) 的客家话定制分支，
基于 [`tumuyan/trime-without-CMake`](https://github.com/tumuyan/trime-without-CMake)，
包名 `com.hazukieq.hkrime`，源码基准钉死上游 **v3.2.7**。

> 文档基线：事实快照提交 **`68f876f`**（2026-11-08）；当前 `dev` HEAD 以 `git log -1` 为准。
> 行号以该提交为准；工作树若有改动会偏移，**以内容为准**。

## 当前状态一览

| 维度 | 状态 |
|---|---|
| 改名 | ✅ `com.osfans.trime` → `com.hazukieq.hkrime`（103 文件 / 391 行） |
| native | ✅ 重建 `librime_jni.so`（13,587,824 B / 73 符号 / 新包名） |
| 脚本 | ✅ `script/build-jni-so.sh`、`script/verify-jni-symbols.sh` |
| APK | ✅ debug 11.4 MB / release 9.6 MB（静态验证通过） |
| 真机 | ⬜ **未冒烟** |
| 客家话方案 | ❌ **未实现**（无方案/词典/资源） |

## 阅读顺序

| # | 文档 | 一句话 | 什么时候读 |
|---|---|---|---|
| 1 | [00-overview.md](00-overview.md) | 定位、当前状态、谱系、仓库地图、关键事实速查 | **先读这个** |
| 2 | [01-architecture.md](01-architecture.md) | 分层图、包职责、按键链路、启动/部署时序、数据目录、线程模型 | 要改代码前 |
| 3 | [03-build.md](03-build.md) | 环境版本、编 APK/编 `.so` 两条链路、产物校验、常见报错 | 要动手构建时 |
| 4 | [02-jni-so.md](02-jni-so.md) | `.so` 定稿事实、历史崩溃根因、**怎么重建、怎么验证** | 动 native / 改名时 |
| 5 | [04-hakka.md](04-hakka.md) | 5 个 hakka 提交拆解、客家话现状、**接入方案指南** | 做定制/加方案时 |
| 6 | [05-branches-release.md](05-branches-release.md) | 分支模型（main/dev）、改名影响面 10 层、发布流程、签名安全 | 发布/改名/交接时 |
| 7 | [06-troubleshooting-roadmap.md](06-troubleshooting-roadmap.md) | 四层排障、症状表、诊断命令、技术债与 roadmap | 出问题时 |
| 8 | [reports/](reports/README.md) | 三份原始报告（构建/改名/集成）**原样存档** | 要查证据/细节时 |

**最短路径**：00 → 03（把包装上）→ 06 §4.1（为什么没候选）→ 04 §6（加客家话方案）。

## 约定

| 约定 | 说明 |
|---|---|
| 引用格式 | `path:line`（如 `core/Rime.java:238`）；路径省略 `app/src/main/java/com/hazukieq/hkrime/` 前缀时会在文中说明 |
| 未确认项 | 标 **`TODO:`**，不猜测、不编造 |
| 历史 vs 现状 | 02 §2、04 §3 是**历史**章节，其中的旧数值（85 MB 库等）仅作事故记录 |
| 命令环境 | 默认在仓库根执行，`JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9` |
| 敏感信息 | 文档**不复述**密钥口令；签名部分见 05 §6 |

## 关键数字（速查）

| 项 | 值 |
|---|---|
| `.so` 大小 / sha256 | 13,587,824 B / `d6b295b6f795cc14ba888c8d9e9b1a69a48ee20d9f772555e89e3db65ca098ec` |
| `.so` 符号 | 73（72 Java native + 1 Kotlin `openccDictConv`） |
| debug APK | 11,418,991 B / `8cc718eb…cce545` |
| release APK | 9,586,652 B / `9fe31e15…3575fe` |
| package / version | `com.hazukieq.hkrime` / `20220701` (`3.2.7`) |
| SDK | compile 33 / target 33 / min 21；ABI `arm64-v8a` |
