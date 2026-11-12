# 面板实现与维护约定

当前实现采用五个 App 来源加引擎来源，最终只经过一条合并流水线。
具体产品规则和配额见 [17](17-candidate-panel.md)；本篇规定修改边界和一致性验证。

## 文件边界

| 文件 | 允许承担的职责 |
|---|---|
| TwelveKeyPreedit | 音节、解码、词表解析、不可变索引与查询 |
| TwelveKeySnapshotBuilder | 来源构造、引擎组装、快照投影和推荐 |
| PanelRow | 行模型、唯一合并、前缀收敛、分档、总行数截断 |
| CandidateRanking | 候选条与组内排序纯函数 |
| TwelveKeyTuning | 配额和策略常量及其影响说明 |
| TextInputManager | 点击、部分选择、撤销、提交与学习 |
| CandidatePanelController / adapters | 展示、开合、活跃组与点击回调 |

新增来源必须进入 `mergeRows → collapsePrefixRows → tierAndSort → capRows`。
源内仅允许本源查词与选行轮转，不允许再创建跨源合并点。RowSource 不进入 UI。
调参只改 Tuning，并同步 Python 同名常量；注释描述当前作用与上下调影响。

## 规则镜像与 golden

selftest 默认读已有本地快照并运行固定断言，其中 Linux 样例要求数据集实际含该词，当前随包词典已收录。
它不自动刷新设备数据，也不穷举全词库。PanelGoldenTest 使用 assets 生成预期，二者验证口径不同。

Kotlin 与 `tools/panel_probe.py` 使用同一编码、音节集合、首字母、分桶、组合、合并和显示规则。
PanelGoldenTest 读取 assets 数据生成的 32 码 golden，比较每源的读音、文字集合、行序与 merged 顺序。
改规则后按测试类头部命令重生成，不手写修改预期来遮盖差异：

```bash
python3 tools/panel_probe.py --data app/src/main/assets/rime \
  json ia ia8 ia8i ia8i3 5i34i 5i34iwe 5i34iw 5i4a57 744ii we w e 4i 3a a3 \
  855 455 74ii ee5 5e5e 798ea 5i 7 8 9 1 3 4 5 i y 5i34iwa \
  > app/src/test/resources/panel-golden.json
```

## 验收边界

1. 语义改动先写文档并向用户确认；实现和脚本一起修改。
2. selftest、PanelGoldenTest、排序与快照单测通过；真实语料重新生成并确认 RealCorpusTest 无跳过。
3. 安装后确认实际 IME，检查真实词表加载、diag 的各源与最终行、部分选词及撤销行为。
4. 性能门禁和首次开面板/连续输入验收通过，候选条开合前后不漂移。

不要为通过测试加入另一套手写数据模型。无引擎候选仍要保留字典兜底和五源查询；
单音节行不吞词行；文字大小写去重严格按来源规则处理；读音分档用 token 数，不用 syllables.size。
