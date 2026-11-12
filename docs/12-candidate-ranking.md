# 候选排序规则

排序纯函数在 `core/twelvekey/CandidateRanking.kt`；装配在 TwelveKeySnapshotBuilder，
面板行合并/分档在 PanelRow，音节/字频与索引在 TwelveKeyPreedit。

## 固定候选条

```text
前 64 条引擎候选，保留全局 index
→ SelectionMemory 分数降序，同分保持引擎 index
→ mergePhraseCandidates 混入 PhraseMemory 合成学习词
→ fullBarOrder 按文字去重并保序
→ 过滤空文字，取前 12 项
```

词与单字平等竞争，不按覆盖音节数、汉字词类型或词区名额重排。
未学习时保留引擎顺序。面板枚举全量 600 时，候选条仍从相同 64 条窗口单独计算，
使学习词去重/混排不受窗口外候选影响。空格首选与 previewText 使用同一 barEntries。
引擎无候选时可用字典段组填条；不能把这种兜底误认为引擎返回。

## 学习与字频

| 来源 | 用途 |
|---|---|
| SelectionMemory | 点选后的候选条微调，版本参与缓存键 |
| PhraseMemory | 逐段组成的学习词，包含文字/读音、曝光和点选统计；并入面板索引 |
| SegmentMemory.charScore | 用户用字偏好 |
| SegmentMemory.bigramScore | 与上一字的邻字偏好，影响段组组内选择 |
| hakka_char_freq / hakka_char_rank | 词表字频与通用字频，转成分位评分 |

`generalScore` 取两个字频表分位评分的最大值；以 Unicode 码点处理扩展汉字。
段组字排序按 bigramScore → charScore → generalScore；
普通面板引擎项按 charScore → generalScore → 引擎 index。
不要用 HashMap 迭代顺序、随机数或当前时间作为稳定显示顺序的兜底。

## 面板行

`mergeRows → collapsePrefixRows → tierAndSort → capRows` 是唯一最终行流水线。
档位按 reading 的非空音节 token 数判断：1 节、多节、空串；各档按读音字典序，
再按首候选文字稳定排序。高频与学习主要决定保留哪些行，不改变档内字母序。

当前 `BLANK_READING = "…"` 本身是一个非空 token，实际落单音节档；
其排序键用首候选反查读音。不要把“无读音占位”描述为实际一定排最后。
推荐组由流水线外生成，固定在前，最多 30 词 + 10 单字；不改变候选条顺序。

细节与行内去重见 [17](17-candidate-panel.md)。修改后运行 CandidateRankingTest、
SnapshotDecouplingTest、PanelGoldenTest、真实语料与性能门禁；辅助检查为
`python3 tools/rank-sanity.py`，以该工具实际用例范围解释结果。
