# 12 键性能门禁（perf gate）

> 目标：任何一次「分组 / 联想词 / 排序 / 快照」改动，都能用一条命令确认**没有把热路径变慢**。
> 背景：2026-11-10 加「联想词」后出现按键卡顿，排查发现两处退化（每次查询遍历 8 千个键、
> 2MB 词表同步读盘），修完后用户要求「把它做成 gate，方便时刻精确排查」。

## 一条命令

```bash
cd hkrime
python3 tools/perf-gate.py          # 触发一次门禁并判定（默认阈值）
python3 tools/perf-gate.py --json   # 机器可读
python3 tools/perf-gate.py --log /tmp/hkrime.log --no-trigger   # 离线复算
```

- 脚本先广播 `com.hazukieq.hkrime.debug.perfgate`，App 在后台线程跑固定微基准并写日志；
- 再拉 `/sdcard/hkrime/logs/hkrime-<今天>.log` 解析 `perfgate:` / `twelvekey:latency` 行；
- 退出码：`0` 通过 / `1` 超阈值 / `2` 没数据。

## 门禁测什么

| 指标 | 含义 | 基准（vivo V2520A） | 默认阈值 |
| --- | --- | --- | --- |
| `op=decode` | 码串 → 音节（每次按键） | 0.02ms | 0.2ms |
| `op=segment` | 第一待选段的音节桶（每键） | 0.21ms | 1.5ms |
| `op=assoc` | 联想词查询（面板展开时每键） | 0.29ms | 2.0ms |
| `op=merge` | 分组合并/排序（面板展开时每键） | 0.08ms | 1.0ms |
| `op=engine` | 引擎候选枚举（每键） | 设备相关 | 60ms |
| `latency_p90` | 最近 200 条按键快照耗时 90 分位 | 30–35ms | 45ms |
| `latency_max` | 最慢一次 | — | 250ms |
| `assoc_load` | 联想词索引导入（后台线程） | 0.6–0.8s | 1.5s |

阈值给约 5 倍余量：容忍设备差异，但能抓住「实现退化成 O(n) 全表扫描」这类回归。
按需覆盖：`--thr op=assoc=3 --thr latency_p90=60`。

## App 侧实现

- `app/src/main/java/com/hazukieq/hkrime/core/twelvekey/PerfGate.kt`
  - **只读**：不喂键、不改组字、不写用户词典，随时可跑；
  - 固定输入集：`744ii`（son gi / song g）、`5e5e`、`855`、`i44yi`、`nz`、`7777777777`、`455`；
  - 输出固定格式：`perfgate: op=assoc avg=0.29ms n=1400 rows=6000`、`perfgate: ready …`、`perfgate: done total=…ms`；
  - 引擎枚举只在「当前正在组字」时测（不会为了跑门禁去动引擎状态）。
- 广播入口：`IntentReceiver.COMMAND_DEBUG_PERF_GATE = com.hazukieq.hkrime.debug.perfgate`。
- `TrimeApplication` 启动时写 `perfgate: process-start` —— 脚本只统计**本次进程**的按键延迟，
  避免历史版本慢样本把门禁拖成永久 FAIL。

## 什么时候跑

- 改 `TwelveKeySnapshotBuilder` / `CandidateRanking` / `TwelveKeyPreedit` / `CandidatePanelController` 之后；
- 发版前；
- 用户报「卡顿」时：先跑门禁定位是哪个 op 退化，再看 `twelvekey:build … ms=` 的慢输入。

## 排查顺序（与 AGENTS.md 一致）

1. `python3 tools/perf-gate.py` —— 哪个 op 红了；
2. `adb logcat | grep -E "twelvekey:latency|twelvekey:build"` —— 具体慢输入；
3. simpleperf（`--app com.hazukieq.hkrime`）—— 需要到 native 层时。
