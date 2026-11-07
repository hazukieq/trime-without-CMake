# hkrime 开发文档

**hkrime** = RIME 安卓前端 [Trime](https://github.com/osfans/trime) 的客家话定制分支，
基于 [`tumuyan/trime-without-CMake`](https://github.com/tumuyan/trime-without-CMake)，
包名 `com.hazukieq.hkrime`，源码基准钉死上游 **v3.2.7**（v3.3.x Kotlin 化后 JNI 不兼容）。

> 文档基线：`dev` @ **`bebad9f`**（2026-11-08 第二轮：OpenCC + 客家话数据 + 16 KB 对齐 + 密钥外置 + 真机测试）。
> 行号以该提交为准；工作树若有改动会偏移，**以内容为准**。

## 当前状态一览

| 维度 | 状态 |
|---|---|
| 改名 | ✅ `com.osfans.trime` → `com.hazukieq.hkrime`（103 文件 / 391 行） |
| native | ✅ 重建 `librime_jni.so`（13,587,744 B / 73 符号 / 新包名 / **16 KB 对齐**） |
| 脚本 | ✅ `build-jni-so.sh`、`verify-jni-symbols.sh`、`gen-keystore.sh` |
| OpenCC | ✅ 29 个词典/配置恢复，部署生成 15 个 `.ocd2`，实测四组转换 |
| 客家话 | ✅ 内置「客语12键」+ 9 张词典 + 主题；⚠ 仍有缺料（见 04 §6） |
| 签名 | ✅ 密钥移出仓库；新证书 SHA-256 `d9d7d91a…93cb` |
| APK | ✅ debug 17,233,842 B / release 11,004,041 B（zipalign16 通过） |
| 真机 | ✅ 数据链路仪器测试 `OK (1 test)`；⬜ 键盘界面人工冒烟 |
| CI | ⬜ 无（5 个 workflow 已被 `0c1e58a` 删除） |

## 阅读顺序

| # | 文档 | 一句话 | 什么时候读 |
|---|---|---|---|
| 1 | [00-overview.md](00-overview.md) | 定位、当前状态、谱系、仓库地图、关键事实速查 | **先读这个** |
| 2 | [01-architecture.md](01-architecture.md) | 分层图、包职责、按键链路、启动/部署时序、数据目录、线程模型 | 要改代码前 |
| 3 | [03-build.md](03-build.md) | 环境版本、编 APK/编 `.so`、**签名配置**、**仪器测试**、常见报错 | 要动手构建时 |
| 4 | [07-opencc-and-rime-data.md](07-opencc-and-rime-data.md) | OpenCC 三文件关系、部署管线、客家话数据清单、缺料、**扩充指引** | 改数据/加方案时 |
| 5 | [02-jni-so.md](02-jni-so.md) | `.so` 定稿事实、历史崩溃根因、重建/验证、**16 KB 对齐** | 动 native / 改名时 |
| 6 | [04-hakka.md](04-hakka.md) | 提交史、**客家话数据清单**、缺口与下一步 | 做客家话定制时 |
| 7 | [05-branches-release.md](05-branches-release.md) | 分支模型（main/dev）、改名影响面 10 层、发布流程、**签名与密钥安全** | 发布/交接时 |
| 8 | [06-troubleshooting-roadmap.md](06-troubleshooting-roadmap.md) | 四层排障、症状表、诊断命令、技术债与 roadmap | 出问题时 |
| 9 | [reports/](reports/README.md) | 构建 / 改名 / 集成 / 真机四份原始报告（**原样存档**） | 要查证据/细节时 |

**最短路径**：00 → 03（把包装上）→ 06 §4.1（候选为空怎么办）→ 04 §6 / 07 §8（补料）。

## 约定

| 约定 | 说明 |
|---|---|
| 引用格式 | `path:line`（如 `core/Rime.java:238`）；路径省略 `app/src/main/java/com/hazukieq/hkrime/` 前缀时会在文中说明 |
| 未确认项 | 标 **`TODO:`**，不猜测、不编造 |
| 历史 vs 现状 | 02 §2、04 §3 是**历史**章节（85 MB 废库事故、5 个 hakka 提交），旧数值仅作记录 |
| 报告存档 | `reports/` 内的数字是**执行时刻快照**；与正文冲突时以正文为准（见 reports/README 的声明） |
| 命令环境 | 默认在仓库根执行，`JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9` |
| 敏感信息 | 文档**不复述**密钥口令；签名部分见 05 §6 |

## 关键数字（速查）

| 项 | 值 |
|---|---|
| `.so` 大小 / sha256 | 13,587,744 B / `fd4372553592830cc8af03a7cae10009eb1882f2eada957086513a7fbc60f03f` |
| `.so` 符号 / 页对齐 | 73 / 4 个 LOAD 均 `0x4000` |
| Release 备选库（未装） | 6,065,152 B / `6b6173d10039262a57205efb321d195d72c1e71b568ec8ba0320beaf4696f920` |
| debug APK | 17,233,842 B / `d0e6159c79844e465d22cf2b80834246e47d9c81dd344a0de226dd7b6917aac7` |
| release APK | 11,004,041 B / `b2e7b748d88b87eeded4aab2dac95724b19a264080196768795ae0f5b4b0426e` |
| package / version | `com.hazukieq.hkrime` / `20220701` (`3.2.7`) |
| SDK / ABI | compile 33 / target 33 / min 21；`arm64-v8a` |
| 证书（release） | `CN=hkrime, OU=hazukieq, …` / SHA-256 `d9d7d91ae5e7c789ff79b8eb9a1644461e5b7b2c48b52a184d993a90594693cb` |
| assets/rime | 19 项（16 客家话 + 2 主题 + `opencc/` 29 个）；运行期 `opencc/` 44 条目、15 个 `.ocd2` |
