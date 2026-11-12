# 十二键性能门禁

在 `hkrime/` 执行：

```bash
python3 tools/perf-gate.py --serial <serial>
python3 tools/perf-gate.py --serial <serial> --json
python3 tools/perf-gate.py --log /tmp/hkrime.log --no-trigger
```

脚本广播 `com.hazukieq.hkrime.debug.perfgate`，默认等 6 秒，读取
`/sdcard/hkrime/logs/hkrime-YYYYMMDD.log`。路径为工具当前约定，实际 userDataDir 不同则
拉取对应日志后离线分析。退出码 0=通过，1=超阈值，2=缺少有效数据。
可用 `--thr op=assoc=3` 等参数覆盖阈值，覆盖值须与结果一起记录。

| 指标 | 默认阈值 | 含义 |
|---|---|---|
| op=decode | 0.2ms | 编码解码 |
| op=segment | 1.5ms | 第一待选段音节桶 |
| op=assoc | 2.0ms | 联想行查询 |
| op=merge | 1.0ms | 合并/分档/截断 |
| op=engine | 60ms | 当前组合候选枚举 |
| build_p90 / build_max | 40 / 120ms | 缓存 miss 的真实快照构建 |
| latency_p90 / latency_max | 80 / 300ms | 按键到快照就绪，含排队与引擎 |
| assoc_load | 1.5s | 后台词表索引加载 |

App 实现在 `core/twelvekey/PerfGate.kt`。微基准只读，不喂键、不提交、不写用户词典；
引擎枚举仅在当前有组合时测量。日志结束时主动 flush。
脚本按进程开始标记隔离样本，真实按键延迟不足时不能把微基准 PASS 当作端到端验收。

分组、联想、排序、快照改动后及发布前运行。出现卡顿先看哪个 op 超阈值，再比较
`twelvekey:build`、`twelvekey:latency`、枚举和 emit 慢日志；Native 再用 simpleperf。
adb 连点会影响 latency，报告必须注明设备、版本、输入方式、缓存与样本范围。
当前首次开面板同步兜底也须单独验证，不能仅凭后台构建指标推断 UI 无阻塞。
