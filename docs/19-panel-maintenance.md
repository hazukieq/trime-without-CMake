# 面板实现与维护约定

当前实现采用六个 App 来源加引擎来源，最终只经过一条合并流水线。
具体产品规则和配额见 [17](17-candidate-panel.md)；本篇规定修改边界和一致性验证。

## 文件边界

| 文件 | 允许承担的职责 |
|---|---|
| TwelveKeyPreedit | 音节、解码、词表解析、不可变索引与查询 |
| TwelveKeySnapshotBuilder | 来源构造、引擎组装、快照投影和推荐 |
| CandidateInputBoundary / CandidateSelection | 构建时证实并绑定原码跨度；点击时校验原码与快照，禁止重算显示 comment |
| PreeditPresentation / PanelNavigation | 只读显示帧与左栏活跃组；不选择、不消费编码、不提交 |
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
脚本先合并全部合法单字读音，再加载词表，避免进口专有音节因载入顺序被过滤。
标准切分诊断通过前向/后向可达性收集所有完整路径涉及的音节，不把展平的音节列表当作切分路径。
PanelGoldenTest 读取 assets 数据生成的 41 码 golden（含太/go 各段、孤立键及标准集组合），
比较六个来源的读音、文字集合、行序与 merged 顺序。
改规则后按下列命令重生成，不手写修改预期来遮盖差异：

```bash
python3 tools/panel_probe.py --data app/src/main/assets/rime \
  json ia ia8 ia8i ia8i3 5i34i 5i34iwe 5i34iw 5i4a57 744ii we w e 4i 3a a3 855 455 74ii ee5 5e5e \
  798ea 5i 7 8 9 1 3 4 5 i y 5i34iwa 9aii48i5i73 i48i5i73 8i5i73 5i73 73 ea144 55 iia 35i \
  > app/src/test/resources/panel-golden.json
```

## 验收边界

1. 语义改动先写文档并向用户确认；实现和脚本一起修改。
2. selftest、PanelGoldenTest、排序与快照单测通过；真实语料重新生成并确认 RealCorpusTest 无跳过。
   `CandidateSelectionTest` 覆盖原码跨度与同长度异码；`PanelNavigationTest` 覆盖切组和渲染不消费编码；
   `NativeCompositionFlowTest` 在真实 PC 引擎上验证显示 comment 改变不影响扣码及跨界面撤销。
3. 安装后确认实际 IME，检查真实词表加载、diag 的各源与最终行、部分选词及撤销行为。
4. 性能门禁和首次开面板/连续输入验收通过，候选条开合前后不漂移。

不要为通过测试加入另一套手写数据模型。无引擎候选仍要保留字典兜底和六源查询；
单音节行不吞词行；文字大小写去重严格按来源规则处理；读音分档用 token 数，不用 syllables.size。

## 切分组合覆盖

```bash
python3 tools/panel_probe.py --data app/src/main/assets/rime coverage --max-keys 5 > /tmp/panel-coverage.json
```

报告包含数据路径与 SHA-256、运行时/标准音节数、失败样例和分类计数。覆盖范围：
12 键全部 1～指定长度组合（默认 4、最多 5）、运行时与标准音节编码的全部合法有序对、
运行时首音节选中后的全部有序码对边界、实际已收录带读音词条的每个选词边界，
以及 `tai go ji li pu` 选太后 `go` 的最终字典行。完整性以独立正则语言对照 DP；
`r` 在非末段的词条单独计数，不作为合法完整拼音。

这是有限穷举，不覆盖任意长度所有简拼，不代替设备快照、用户词库、Android UI 和性能门禁。
修改 preedit/分组后仍须按 docs/16 重新拉设备并生成真实语料；无设备时应明确记录未验收项。
