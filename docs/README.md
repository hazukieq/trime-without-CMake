# hkrime 开发文档

**hkrime** = RIME 安卓前端 [Trime](https://github.com/osfans/trime) 的客家话定制分支，
基于 [`tumuyan/trime-without-CMake`](https://github.com/tumuyan/trime-without-CMake)，
包名 `com.hazukieq.hkrime`，源码基准钉死上游 **v3.2.7**（v3.3.x Kotlin 化后 JNI 不兼容）。

> 事实快照：`dev` @ **`ebb18c1`**（2026-11-08 第三轮：缺口修复 + 构建健壮性）。
> 当前 HEAD 以 `git log -1` 为准；行号以该提交为准，工作树若有改动会偏移，**以内容为准**。

## 当前状态一览

| 维度 | 状态 |
|---|---|
| 改名 | ✅ `com.osfans.trime` → `com.hazukieq.hkrime`（103 文件 / 391 行） |
| native | ✅ **librime 1.17.0**（独立工程 `rime-jni` 构建，Release）：`librime_jni.so` 6,175,752 B / **75 符号** / 新包名 / 16 KB 对齐 / 含 12 键简拼性能补丁（见 **11**） |
| 脚本 | ✅ `build-jni-so.sh`(419) / `verify-jni-symbols.sh`(237) / `verify-rime-assets.py`(183) / `gen-keystore.sh`(79) |
| OpenCC | ✅ **34 个**资源（18 txt + 16 json），fresh 部署生成 **18 个 `.ocd2`**，实测四组转换 |
| 客家话 | ✅ **3 个方案**（客语12键 / 客语26键 / 雲龍國際音標）+ 11 个方案/词典文件；反查/情联依赖已备 |
| 签名 | ✅ 密钥移出仓库；新证书 SHA-256 `d9d7d91a…93cb`；无密钥也能构建（产物 `-unsigned.apk`） |
| APK | ✅ debug **13,437,603 B** / release **11,405,775 B**（zipalign16 通过） |
| 真机 | ✅ 数据链路仪器测试 `OK (1 test)`（fresh 部署 / 18 ocd2 / 10 bin / 3 方案）；⬜ 键盘界面人工冒烟 |
| CI | ⬜ 无（5 个 workflow 已被 `0c1e58a` 删除） |

## 阅读顺序

| # | 文档 | 一句话 | 什么时候读 |
|---|---|---|---|
| 1 | [00-overview.md](00-overview.md) | 定位、当前状态、谱系、仓库地图、关键事实速查 | **先读这个** |
| 2 | [01-architecture.md](01-architecture.md) | 分层图、包职责、按键链路、启动/部署时序、数据目录、线程模型 | 要改代码前 |
| 3 | [03-build.md](03-build.md) | 环境版本、编 APK/编 `.so`、**签名配置**、**仪器测试**、常见报错 | 要动手构建时 |
| 4 | [07-opencc-and-rime-data.md](07-opencc-and-rime-data.md) | OpenCC 三文件关系、部署管线、客家话/方案数据清单、**扩充指引** | 改数据/加方案时 |
| 5 | [02-jni-so.md](02-jni-so.md) | `.so` 定稿事实、历史崩溃根因、重建/验证、**16 KB 对齐** | 动 native / 改名时 |
| 6 | [04-hakka.md](04-hakka.md) | 提交史、**客家话数据清单**、缺口状态与下一步 | 做客家话定制时 |
| 7 | [05-branches-release.md](05-branches-release.md) | 分支模型（main/dev）、改名影响面 10 层、发布流程、**签名与密钥安全** | 发布/交接时 |
| 8 | [06-troubleshooting-roadmap.md](06-troubleshooting-roadmap.md) | 四层排障、症状表、诊断命令、技术债与 roadmap | 出问题时 |
| 9 | [08-theme-trime-yaml.md](08-theme-trime-yaml.md) | **trime.yaml 主题全解**（style/配色/按键/键盘/悬浮键盘/坑）+ **主题预览器用法** | 改主题、调键盘布局、查配色时 |
| 10 | [09-candidate-ui-evaluation.md](09-candidate-ui-evaluation.md) | **候选区改造评估**（单行+展开面板 / 九宫格，参考搜狗等实现，含可交互 mock） | 改候选栏、做展开页、评估成本时 |
| 11 | [10-twelve-key-input-design.md](10-twelve-key-input-design.md) | **12 键（九宫格）客家话输入设计与实现方案**：librime 能力实证、comment 分组架构、JNI 候选枚举、交互规格、P0/P1/P2 | 要动手实现/评审 12 键时 |
| 12 | [11-librime-upgrade-performance.md](11-librime-upgrade-performance.md) | **librime 1.7.3→1.17 升级 + rime-jni 独立工程 + 12 键简拼性能补丁**（三处补丁、simpleperf 数据、升级检查清单、踩坑表） | 动 JNI / 升级 librime / 排查按键卡顿 |
| 14 | [13-perf-gate.md](13-perf-gate.md) | **12 键性能门禁**：一条命令触发微基准并判定（decode/segment/assoc/merge/engine/按键延迟/索引载入），阈值与排查顺序 | 改分组/联想词/排序/快照之后，或用户报「卡顿」时 |
| 14 | [14-preedit-reading-fix-plan.md](14-preedit-reading-fix-plan.md) | 12 鍵 preedit 拼音與候選讀音對齊（**修復方案，待實施**） | 接這個 bug / 改 preedit 前 |
| 13 | [12-candidate-ranking.md](12-candidate-ranking.md) | **候选排序规则**：固定候选区「词组优先」+ 展开面板「用户常用→权威字频→引擎序」、数据源与公式、调参位、调试日志、单测映射、历史决策与排查清单 | 改候选排序 / 出现「生僻字靠前」「词组被单字挤掉」时 |
| 14 | [reports/](reports/README.md) | 构建 / 改名 / 集成 / 真机报告（**原样存档**） | 要查证据/细节时 |

**最短路径**：00 → 03（把包装上）→ 05 §4（发版）→ 07（改数据）→ 06（出问题）。
改候选排序：读 **12**（两块排序的比较键 + `./gradlew :app:testDebugUnitTest` + `tools/rank-sanity.py`）。
改主题/键盘：直接读 **08**，再开 `tools/theme-previewer/` 实时看效果；改候选区：读 **09**，开 `tools/candidate-mock/` 点一遍三种形态。
实现 12 键输入：读 **11**（含 host 实测数据与 JNI/App 改动清单），再对照 `artifacts/12key/{engine,data,app}` 三份证据报告。

## 约定

| 约定 | 说明 |
|---|---|
| 引用格式 | `path:line`（如 `core/Rime.java:238`）；路径省略 `app/src/main/java/com/hazukieq/hkrime/` 前缀时会在文中说明 |
| 未确认项 | 标 **`TODO:`**，不猜测、不编造 |
| 历史 vs 现状 | 02 §2、04 §3 是**历史**章节（85 MB 废库事故、2023 年 5 个提交），旧数值仅作记录 |
| 报告存档 | `reports/` 内的数字是**执行时刻快照**；与正文冲突时以正文为准（见 reports/README 的声明） |
| 命令环境 | 默认在仓库根执行，`JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9` |
| 敏感信息 | 文档**不复述**密钥口令；签名部分见 05 §6 |
| 主题预览 | `tools/theme-previewer/`：浏览器内实时渲染 `trime.yaml`，不用装机（见 08 §12） |
| 候选区 mock | `tools/candidate-mock/`：A 现状 / B 单行+展开面板 / C 九宫格，三种形态可点（见 09） |
| APK hash 口径 | sha256 是**该次交付物**的指纹，**不承诺**「任意 clean build 同 hash」（zip 时间戳/条目顺序）；可承诺尺寸稳定 |

## 关键数字（速查）

| 项 | 值 |
|---|---|
| `.so` 大小 / sha256 | 13,587,744 B / `fd4372553592830cc8af03a7cae10009eb1882f2eada957086513a7fbc60f03f` |
| `.so` 符号 / 页对齐 | 73 / 4 个 LOAD 均 `0x4000` |
| Release 备选库（未装） | 6,065,152 B / `6b6173d10039262a57205efb321d195d72c1e71b568ec8ba0320beaf4696f920` |
| debug APK | 13,437,603 B / `fe6ae286697614a52afbe4ef1a8db75e3df8fbf167d404c2d22805ef3597e575` |
| release APK | 11,405,775 B / `62b63eb2555229a6df9e78bdc7719da857fd4db7cd26973b7e3533d760ddd0a5` |
| package / version | `com.hazukieq.hkrime` / `20220701` (`3.2.7`) |
| SDK / ABI | compile 33 / target 33 / min 21；`arm64-v8a` |
| 证书（release） | `CN=hkrime, OU=hazukieq, …` / SHA-256 `d9d7d91ae5e7c789ff79b8eb9a1644461e5b7b2c48b52a184d993a90594693cb` |
| assets/rime | **23 项**（22 文件 + `opencc/`）；`opencc/` **34 个**（18 txt + 16 json） |
| fresh 部署产物 | **18 个 `.ocd2`** + **10 个 `.bin`** + **3 个方案**；运行期 `opencc/` 共 52 条目（34 + 18） |
| 资产自检 | `python3 script/verify-rime-assets.py` → **53 passed, 0 failed** |
