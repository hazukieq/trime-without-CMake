# 15 · librime 本地魔改清单 + 调试探针手册

> **这份文档存在的唯一理由**：`rime-jni` 里的 librime **不是官方 1.17.0**，我们在本地打了 5 个补丁。
> 12 键的很多「诡异现象」（词条消失、候选缺一截、按键卡顿、preedit 读音不对）**根子在补丁里**，
> 不在 App 代码里。排查任何「引擎给不出/多给出/很慢」的问题，App 侧看完之后**下一步就是查这份清单**。
>
> 事实快照：2026-11-11，仓库 `trime-workspace/rime-jni` + `trime-workspace/hkrime`。
> 补丁文件在 `rime-jni/patches/`，按序 `git am`。

## 目录

1. [魔改总表（按影响面排序）](#1-魔改总表按影响面排序)
2. [每处的行为与风险](#2-每处的行为与风险)
3. [症状 → 可疑补丁 对照表](#3-症状--可疑补丁-对照表)
4. [调试探针手册](#4-调试探针手册)
5. [排查顺序铁律](#5-排查顺序铁律)
6. [案例：`Linux` 词条消失（2026-11-11）](#6-案例linux-词条消失2026-11-11)

---

## 1. 魔改总表（按影响面排序）

### 1.1 当前构建里**生效**的

| 项 | 提交 | 改动位置 | 一句话 | 影响面 |
|---|---|---|---|---|
| `0001-12key-abbrev-perf` | librime `c3c5969` | `dict/table.cc`、`dict/dictionary.cc`、`gear/table_translator.cc`、`gear/script_translator.cc`、`algo/syllabifier.*` | 12 键简拼的性能补丁：table 展开预算、abbrev 时跳过 user_dict DFS、句子音节数上限 | **最大**：直接决定「哪些候选能够被枚举出来」 |
| `0003-table-query-full-path-fallback` | librime `7ad3d56` | `dict/table.cc::Table::Query` | 0001 漏掉「整串路径」时的兜底：DFS 全码路径 + `QueryPhrases` 精确补查 | **大**：决定「长词条（≥3 音節）能不能出现」 |
| `0002-switcher-empty-dir-guard` | librime `f5e1637` | `lever/switcher_settings.cc` | 空 `user_data_dir`（设置进程未初始化）不再 `directory_iterator(".")` 抛异常 → 不 abort | 中：只影响设置界面「选择方案」 |
| glog 日志（**非补丁，已进工程**） | rime-jni `e3b68ca` | `CMakeLists.txt`、`cmake/FindGlog.cmake`、`glue/rime_jni.cc`、`script/build.sh` | `ENABLE_LOGGING=ON`，librime 的 `LOG(...)` 写到 `<userDataDir>/logs/*.INFO.*`；部署时由 `DeployLog` **完整**抄进 `hkrime-deploy.log` | 无（只多写日志） |

### 1.2 只存在于 `patches/`、**当前构建未启用**的（排查时临时打上）

| 补丁 | 提交 | 提供的能力 |
|---|---|---|
| `0004-librime-debug-probes` | librime `a67fb21` + 后续 | `ProbeDictEntry`（表里有没有词条/weight/prism 拼写）、`ProbeSyllable`（音節图 `edges/indices` + 分段 `Lookup` 的 chunk 统计）、`Session::engine()` |
| `0005-rime-jni-debug-probes` | rime-jni 胶水 | 对应两个 JNI 入口 `probe_dict` / `probe_syllable` |

> 这两个是**按需**的诊断补丁：默认不装；遇到「候选缺词 / 路径对不上 / 想确认表里到底有没有」时，
> 再 `git am` 打上并重编 `.so`。用完可原样撤掉（不影响功能）。

## 2. 每处的行为与风险

### 0001 · 12 键简拼性能补丁（最需要警惕）

**为什么加**：12 键「全拼+简拼」让每个位置的拼写分支爆炸，句子模式（`MakeSentence`）会对用户词库做
DFS，长输入时主线程卡死 20s+（2026-11-08 真机 ANR）。

**改了什么**（详见 `docs/11-librime-upgrade-performance.md`）：

- `Table::Query`：每个 `end_pos` 最多 48 个 chunk、整体展开预算 12000 次 `Access/Advance`、
  分支按「该音节开头词条数」降序展开（高频音节先走）；
- `Dictionary::Lookup`：abbrev 拼写时跳过 `user_dict` 的 DFS；
- `script_translator`：句子模式音节数上限。

**风险（已实际发生）**：BFS 只把**部分**分支走到深，`interpreted_length` 终点桶可能空着 →
**该长度的词条整批消失**，而单字/短词一切正常。表现是「词库里明明有，候选里就是没有，重建有时好有时坏」。

### 0003 · 全码路径兜底

**规则**：仅当「`Table::Query` 结束后终点桶（`interpreted_length`）为空」时，才在音節图上 DFS 枚举
完整路径（总路径 ≤ 64、每个前缀 ≤ 16），逐条 `QueryPhrases(Code)` 精确补查。

**代价**：最多 64 次二分查表，且只在「本来会丢词」时触发。

**注意**：它**不是**修 BFS 本身，只是补「整串」这一类。如果以后发现**中间长度**（如 2 音節）也丢词，
说明 0001 的 `kMaxChunksPerEnd` / `kMaxExpansions` 需要单独调，而不是再加兜底。

### 0002 · switcher 空目录守卫

设置进程里 `Rime.get()` 未调用时 `shared/user_data_dir` 为空串，`fs::path("")` 等于 `.`，
Android 进程 cwd `/` 不可读 → `filesystem_error` 穿出 JNI → abort。
现在：`dir.empty()` 直接返回、`exists/is_directory` 用 `std::error_code`、整段 `try/catch` 只记 `LOG(ERROR)`。

### 0004 · 调试探针（只在被调用时执行）

- `ProbeDictEntry(engine, "li nak si")`：按音節名 → 表 id → `Table::QueryPhrases` 查词条与 weight，
  并打印 `prism.GetValue` / `ExpandSearch`；回答「表里到底有没有这个词」。
- `ProbeSyllable(engine, "5i4a57i")`：**直接跑 syllabifier**，打印 `consumed / edges / indices`，
  并对每个起点跑 `Dictionary::Lookup` 打印 `chunks start/end/n`；回答「引擎有没有看到这条音節路径、
  有没有把它翻成词条」。
- `Session::engine()`：让 JNI 能从 session 拿到 `Engine*`（`rime::Service::instance().GetSession(id)`）。

### 0005 · glog 日志

- `ENABLE_LOGGING=ON`（`script/build.sh` + `CMakeLists.txt` 默认 ON），glog 从 `deps/glog` 构建；
- `Rime_setup` 里 `rime::SetupLogging("hkrime", 0, "<userDataDir>/logs")`；
- 设备上落在 `/sdcard/hkrime/logs/com.hazukieq.hkrime.localhost.u0_a23.log.INFO.<时间>.<pid>.log`。

> 排查「部署/建表」问题的第一现场就是这些 INFO 日志（`building table:`、`collecting entries`、
> `num entries:`、`dropping entry`、`invalid syllable`）。

## 3. 症状 → 可疑补丁 对照表

| 症状 | 先查 |
|---|---|
| 词库里有的词，候选里没有；单字正常；重建后时好时坏 | **0003（不够就查 0001）** |
| 只有长词（≥3 音節）没有，短词一切正常 | **0003 未生效 / 0001 的 48 桶与 12000 预算** |
| 同码多音節（5i=ki｜li）只出其中一条路 | **0001 的分支排序 + 0003 兜底** |
| 设置里点「选择方案」直接崩 | **0002** |
| 部署日志 `build/` 没变，或 `hkrime-deploy.log` 里 `librime glog` 段为空 | 日志是否被编译进去（`ENABLE_LOGGING=ON`、`<userDataDir>/logs/` 有没有 INFO 文件） |
| 按键卡顿 / ANR | 0001 的预算；先跑 `docs/13` 的性能门禁 |
| 候选条上字母/符号在面板里找不到 | App 侧（`CandidateRanking.panelGroups`），与本清单无关 |

## 4. 调试探针手册

### 4.1 App 侧探针（默认**不编进构建**；需先打 `0004`+`0005` 补丁并重编 `.so`）

打完补丁后，`hkrime` 仓库里的 `CandidateProbe.kt`（`git log --all -- '*CandidateProbe.kt'` 可取回）
按下面的协议触发；不打补丁时这两个 JNI 符号不存在，探针代码也不在构建里。


请求文件：`/sdcard/hkrime/dbg_candidates.txt`（跑完改名 `.done`，结果写 `dbg_candidates.log`）。
触发点：`TrimeApplication.onCreate` 与 `TextInputManager.onInitializeInputUi`。指令：

| 指令 | 作用 |
|---|---|
| `!schema hakka_jiugong` | **先切方案**（新装/重启默认是 26 鍵 `hakka_qwerty`，不切就等于用错码表探针） |
| `!deploy` | 先做一次增量部署并等维护线程结束，再跑后面的指令 |
| `!feed 5i4a57i` | 逐键喂码，**每键取一次候选**（模拟真机），打印每步 `ok/input` 与最终候选条数、是否含指定词 |
| `@li nak si` | 词典级 `ProbeDictEntry`：表里有没有这条词条、weight 多少、prism 认不认拼写 |
| `%5i4a57i` | 音節图级 `ProbeSyllable`：`edges/indices/lookup chunks`，看引擎有没有看到路径 |

典型用法（本次 Linux 案例）：

```
!schema hakka_jiugong
%5i4a57i
!feed 5i4a57i
@li nak si
```

### 4.2 部署日志（**完整 librime 输出**）

`<userDataDir>/logs/hkrime-deploy.log`：每次部署**覆盖写**，含

1. 头部：模式（full/incremental）、pid、`data_dir`、所有 `*.dict.yaml` 的 mtime+size、`build/*.bin` 快照；
2. `build/*.bin` 前后对比（变化标 `<<< REBUILT`）；
3. **`--- librime glog (complete, this deploy) ---`**：这次部署期间 librime 写出的
   INFO/WARNING/ERROR 日志**全文**（按文件偏移只取本次新增部分，单文件上限 8MB），
   含 `building table:`、`collecting entries from ...`、`Pass 1: total ...`、`num entries:`、
   `dropping entry`、`invalid syllable` —— 判断「词条有没有进表」直接看这一段；
4. `--- app logcat (this deploy) ---`：本进程部署期间的 App 侧 logcat（补充，最多 4000 行）。

> 这是排查「改了词典不生效」的第一现场：先看 `sources (before)` 里 yaml 的 mtime 变没变，
> 再看 glog 段里 `collecting entries` 有没有读到那份文件、`num entries` 有没有变。

### 4.3 librime glog

`<userDataDir>/logs/*.INFO.*`：部署与建表全过程。**这是判断「词条有没有进表」的唯一可靠证据**，
不要用「候选里没有」倒推「词条不在表里」——中间还隔着 0001/0003。

## 5. 排查顺序铁律

```text
① App 侧（快照 / preedit / 排序 / 布局 / 主题）
        ↓ 候选内容层面的问题（缺词、少一截、多出来、慢）
② librime 本地魔改（本文档 §1 表；0001/0003 优先）
        ↓ 确认「表里也没有」
③ 词库/方案/部署（dict.yaml、import_tables、build/*.bin、hkrime-deploy.log、glog）
        ↓ 确认「表和路径都对，仍取不出」
④ librime 上游逻辑（真·引擎缺陷，加探针后再定）
```

**第 ② 步不能跳**：12 键的两处性能/覆盖面补丁（0001、0003）改变了「候选能不能被枚举出来」，
它们是「词条消失」类问题的第一嫌疑人。历史上就有一次把引擎缺陷误判成「建表丢词」，查了很久才发现是
我们自己的 BFS 预算/分支排序把长词条那条路饿死了。

## 6. 案例：`Linux` 词条消失（2026-11-11）

**现象**：`hakka_zon.dict.yaml` 里加了 `Linux<TAB>li nak si`，候选里永远没有；`chatgpt`、`猛劥` 都正常。

**排查链**（全程用 §4 的探针）：

1. `!deploy` + glog：`collecting entries from hakka_zon`、`num entries: 536082`、**无 `dropping entry`**
   → 词条进表了；
2. `@li nak si`：`entries_for_code=2`、`entry: 'Linux' weight=6.90776`、prism `5i/4a5/7i` 全命中
   → 表里确实有、拼写也认；
3. `%5i4a57i`：`edges: 0->2(ki,li) 2->5(mak,nak) 5->7(pi,si)`，`indices` 完整
   → **音節图没问题**，排除 syllabifier；
4. 同一份输出里 `Dictionary::Lookup` 的 chunks：`start=0 end=2 n=151`、`end=5 n=1`，**没有 `end=7`**
   → 整串三音節的桶是空的；
5. 结论：`Table::Query`（0001 的 BFS）没有把任何分支走到 `interpreted_length`
   → 补丁 **0003** 的全码路径兜底补上这一桶；
6. `!feed 5i4a57i` → `candidates=308 hasLinux=true`，`#0 Linux`。

**教训**：
- 「候选里没有」≠「词库里没有」，中间隔着 0001/0003；
- 探针必须**先切方案**（`!schema hakka_jiugong`），否则测的是 26 鍵；
- `Rime.onText` 整串喂时翻译器是惰性创建的，要用 `!feed`（逐键 + 每键取候选）才能复现真机行为。
