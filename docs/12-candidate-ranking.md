# 候选排序规则（固定候选区 / 展开面板）

> 状态：2026-11-10（HEAD 见 `git log -1`）。
> 相关代码：`core/twelvekey/CandidateRanking.kt`（纯函数）、`core/twelvekey/TwelveKeySnapshotBuilder.kt`（数据装配）、
> `core/TwelveKeyPreedit.kt`（字表/音节/字频）、`core/twelvekey/SegmentMemory.kt`（用户记忆）。
> 相关测试：`app/src/test/java/com/hazukieq/hkrime/core/twelvekey/{CandidateRankingTest,SyllableGroupCountTest}.kt`。

**这份文档只讲「排序」**：哪些候选、按什么键、谁先谁后、怎么调、怎么验证。交互规格见 [10](10-twelve-key-input-design.md)。

---

## 0. 一页速览

| 版块 | 一句话规则 | 比较键（从前到后） | 代码 |
|---|---|---|---|
| **固定候选区**（顶部单行，最多 12 条） | 引擎/学习排好序之后，**词组整体提到单字前面** | 引擎词频 + 用户选择学习 → 稳定分区「词组 > 非词组」 → 截断 12 → 去空 | `CandidateRanking.rankBar()` |
| **展开面板**（左列读音 + 右格汉字） | 左列：**第一待选段的读音组优先**，再按音节数/读音；右格：**用户常用 → 权威字频 → 引擎序** | 组：`segment` 组 → 单音节 → 音节数升序 → reading；组内：`charScore` → `generalScore` → `index` | `CandidateRanking.groupCandidates()` / `rankPanelItems()` / `rankSegmentChars()` |

两条铁律（改动时别破坏）：

1. **排序改动必须落在纯函数里**（`CandidateRanking`），不要塞回 `TwelveKeySnapshotBuilder` 的装配流程——否则没法用 JVM 单测守住。
2. **任何一级比较键都是「降序优先、末尾用稳定次序兜底」**：候选条末尾用引擎原顺序（即 `index`），面板组内末尾用引擎下标 `index`，段组末尾用 `generalScore`。绝不引入随机、时间或 HashMap 迭代序。

---

## 1. 数据源

| 数据 | 来源 | 量级 | 用在哪 |
|---|---|---|---|
| 引擎候选 | `Rime.candidatesSnapshot(MAX_CANDIDATES=600)`（内部 `enumerate_candidates`），返回 `{text, comment}` | 上限 600 | 两个版块的原料；`index` = librime 全局下标（点击/上屏用它） |
| 选择学习 | `SelectionMemory`（用户点过的候选加权） | 内存 | 候选条前置排序（在 `TwelveKeySnapshotBuilder` 里，早于 `rankBar`） |
| 用户用字频 | `SegmentMemory.charScore`（`SharedPreferences("segment_memory")` 的 `char_freq`） | 上限 400 字 | 面板组内第 1 级、段组第 2 级 |
| 用户组词频 | `SegmentMemory.bigramScore`（`bigram_freq`，相邻两字一起上屏） | 上限 3000 对 | 段组**组间**排序、组内第 1 级 |
| 词表字频 | `assets/rime/hakka_char_freq.txt`（`字<TAB>权重`，由 `tools/gen-char-freq.py` 从 8 个客家词表统计，oftenwords×3 / mobilewords×2 / 其余×1） | 7990 字 | 与下表合并成「综合分」 |
| 权威字频 | `assets/rime/hakka_char_rank.txt`（`字<TAB>权重`，由 `tools/gen-char-rank.py` 从 rime-ice `cn_dicts/8105.dict.yaml` 抽取，25 亿字语料权重） | 8183 字（含扩展 B 区） | 与上表合并成「综合分」 |

**综合分** `TwelveKeyPreedit.generalScore(text)`：

```
percentile(table, ch) = (size - rank_in_table_desc) * 10000 / size
generalScore(ch) = max( percentile(hakka_char_freq, ch), percentile(hakka_char_rank, ch) )   // 缺省 0
```

- 为什么取 `max` 而不是加权：两表领域不同（客家词表覆盖方言用字，权威表覆盖普通话高频与生僻分级），取 max 等于「任一来源认为它常见就算常见」。
- 为什么用**码点**做 key（`ch.codePointAt(0)`）：`substring(0,1)` 会把扩展 B 字（代理对）切成半个字符，导致 8183 个字只认 7978 个——这是修过的 bug。
- 权威表的权重是真实语料频次（例：`的 76,938,354` / `惹 125,358` / `婼 88` / `姞 26`），所以同音字里「惹」永远排在「婼」之前。

---

## 2. 固定候选区（候选条）

### 2.1 流水线

```
Rime.candidatesSnapshot(600)
   → TwelveKeySnapshotBuilder 组装 CandidateItem(text, comment, index)   // index = 全局下标
   → SelectionMemory 学习排序（只抬学过的词；同分按引擎 index；不再按「覆盖音节数」分层）
   → CandidateRanking.rankBar(ranked, BAR_ITEMS = 12)
        ① fullBarOrder : distinctBy(text) 去重，保留引擎/学习混排顺序
        ② filter { text.isNotEmpty() }
        ③ take(12)
   → barEntries
   → previewText = barEntries.first().text
   → inlineText  = librime 已选前缀 + previewText   （嵌入式预览「首选」模式）
```

- **2026-11-12 起取消「词组优先」**（用户原话：「取消词组优先，改为平等竞争，并尊重 engine 给出的候选，
  在此基础上只做少量添加或者删改 by app 现在的算法」）：词与单字同池竞争，顺序以引擎候选为基准；
  App 只做三件小事——学习词 boost（`SelectionMemory` 分数，同分引擎在前）、合成学习词混排
  （`mergePhraseCandidates`）、按文字去重。**没有**「词区 ≤ N 条」「全部词提前」这类分区规则。
- `isCjkWord(text)`：长度 ≥ 2 且每个字符码点落在 `0x3400..0x4DBF`（扩展 A）或 `0x4E00..0x9FFF`；
  面板/推荐组仍用它筛「整词」，但候选条排序不再用它分区。
- 候选条与「空格上屏」共用同一份 `currentSnapshot.barEntries`（`TextInputManager` 单一状态源），点第 N 条 = `barItems[N]`，用它的 `index` 交给引擎，所以排序只改**展示顺序**，不改点击语义。
- 段组（`segment = 0`）的候选**不进候选条**，只进面板：候选条只放引擎候选。

### 2.2 不变量（测试逐条守）

| 不变量 | 用例 |
|---|---|
| 保持传入顺序（引擎 → 学习调整后的顺序），**不按词/字分区** | `rankBar does not put words before single chars` |
| 同文去重，保留先出现的那条 | `rankBar dedupes identical texts but keeps engine order` |
| 截断到 12 条、空文字丢弃（先滤空再截） | `rankBar keeps engine order, truncates and drops empties` |
| 单字之间保持引擎原顺序（不许按自己喜好重排） | `rankBar keeps engine order among single chars` |
| 学习只抬分数字（同分引擎下標），不按覆盖深度分层 | `rankWithLearning keeps engine order when nothing learned` 等 |

---

## 3. 展开面板

### 3.1 左列分组的组成

> **2026-11-11 重構後以 `docs/17-candidate-panel.md` §6–§8 為準**：
> `buildPanelRows` 五源（`SYNTH_SINGLE / SPLIT_SINGLE / ASSOC / INITIAL_COMBO / WHOLE_WORD`）
> → `mergeRows`（唯一合併點）→ `collapsePrefixRows` → `tierAndSort` → `capRows` → 投影 `SyllableGroup`。
> 下文 A 節保留的是本節撰寫時的舊拆分說明，`segmentGroupsFor` 已不存在（現為 `splitParts` + 五源），
> 僅供理解「段組」概念；改代碼請直接看 docs/17。

```
remaining（剩余码串，可能含用户按过的 '）
   → TwelveKeySnapshotBuilder.buildPanelRows(...)   // 见 docs/17 §6
```

**A. 段組（現由 `splitParts` + `synthSingleRows` / `splitSingleRows` 產生）**

1. `codes = remaining.replace("'", "")` —— 用户按的 `'` 只是显式边界，拆段算法自己也能拆，两者结果一致。
2. `TwelveKeyPreedit.splitFirstSyllable(codes)` 切出「第一段码」与「其余码」：
   - `decode(codes)` 能切出 ≥2 个音节 → 找第一个「单音节 head + 剩余 tail 音节数之和 == 总数」的切分；
   - `decode(codes)` 切不出音节（首字母相拼，如 `855`、`4i`）→ 直接取 `codes[0]` 与 `codes.substring(1)`；
   - 单音节输入 → 返回 null（不需要段组）。
3. `syllablesForSegment(head, SEGMENT_SYLLABLE_LIMIT=16 / _RICH=24)`：以该码/首字母开头的**高频音节**
   （精确码优先，其次前缀），按音节词频降序、去重；可用音节 >16 的「豐富鍵」放開到 24。
   **段碼剛好是一個完整音節**（`4i`、`ea`、`74`）時只列該音節，不再展開同前綴更長音節。
4. 每个音节一组：`charsForSyllable(syllable)` → `rankChars(chars, anchor)` → `CandidateItem(ch, comment=syllable, index=-1, segment=0, restCode=tail)`。
5. **组间排序**：若有 `anchor`（上一字），按 `SegmentMemory.bigramScore(anchor, 组内第一字)` 降序 —— 打完「區」之后，含「域」的组自动排最前。

`rankChars(chars, anchor)` 的比较键：`bigramScore(anchor,字)` → `SegmentMemory.charScore` → `generalScore`（都是降序，稳定排序）。

**B. 引擎组**（普通候选按 comment 分组）

- `groupCandidates(items, blankReading="…", maxGroups=200)`：
  - 按 `comment` 分组；**comment 为空 → 「…」组，且永远排最后**（它只是兜底分组）；
  - 组顺序：单音节组优先（用户要先能逐字选）→ 音节点数升序 → `reading.lowercase()`；
  - 组内顺序 = 传入顺序，分组不重排。
- 组内再 `rankPanelItems(items)`：`SegmentMemory.charScore` → `TwelveKeyPreedit.generalScore` → `index`（引擎下标，稳定兜底）。
  - 为什么需要 hard 排序：客家词典没有 `weight` 列（`sort: by_weight` 但没有权重），引擎对单字基本按**文件顺序**返回，生僻字会跑到「以」前面（用户 2026-11-10 反馈的原始问题）。

### 3.2 点击语义（排序必须与之配套）

| 点击的项 | 行为 |
|---|---|
| 段组项（`segment = 0`，`restCode` 非空） | 上屏该字 + 把 `restCode` 喂回引擎 → 面板接着列下一段（「按顺序推进」） |
| 段组项（最后一段，`restCode` 为空） | 上屏该字，结束组字 |
| 引擎项（`index >= 0`） | `Rime.select_candidate(index)` 归一流程 |

### 3.3 不变量（测试逐条守）

| 不变量 | 用例 |
|---|---|
| 面板组内：用户字频 > 综合频度 > 引擎下标 | `rankPanelItems prefers user char score then general score then engine index` |
| 全 0 分时严格按引擎顺序（稳定） | `rankPanelItems keeps single item and stable when all scores equal` |
| 单音节组在最前、空读音（…）永远最后 | `groupCandidates puts single syllable groups first and blank comment last` |
| 组内顺序不被分组打乱、`maxGroups` 生效 | `groupCandidates preserves item order inside a group and caps group count` |
| 段组内：与前字关联 > 用户常用 > 综合频度 | `rankSegmentChars anchors on previous char then user score then tie break` |
| 真实 `ji` 字表排序后综合分非递增、且「以」第一 | `real data - panel ordering is non increasing by general score for a ji group` |
| 面板音节组数 = min(8, 可用音节数)，且平均覆盖 ≥ 40% | `SyllableGroupCountTest` |

---

## 4. 调参位

| 想调什么 | 改哪里 | 现值 | 备注 |
|---|---|---|---|
| 候选条条数 | `TwelveKeySnapshotBuilder.BAR_ITEMS` | 12 | 与 UI 一屏宽度绑定，改完顺手看候选条是否截断 |
| 枚举上限 | `TwelveKeySnapshotBuilder.MAX_CANDIDATES` | 600 | 越小越省内存，越大面板越全 |
| 段组音节组数量 | `TwelveKeyTuning.SEGMENT_SYLLABLE_LIMIT` / `..._RICH` | 16 / 24（豐富鍵門檻 16） | **改前先跑 `SyllableGroupCountTest` 看覆盖表**（§6） |
| 面板组总数上限 | `TwelveKeySnapshotBuilder.MAX_GROUPS` | 200 | 防止巨型方案把分组拖慢 |
| 空读音组名 | `TwelveKeySnapshotBuilder.BLANK_READING` | `…` | 改了要同步 UI 文案 |
| 词表字频权重 | `tools/gen-char-freq.py`（oftenwords×3 / mobilewords×2 / 其余×1） | — | 改完重生成 `hakka_char_freq.txt` |
| 权威字频来源 | `tools/gen-char-rank.py`（默认拉 rime-ice `8105.dict.yaml`） | — | 换源就改脚本 URL，产物格式保持 `字<TAB>权重` |
| 用户记忆老化 | `SegmentMemory` 的容量常量 | 字 400 / 词 3000 | 溢出按频次裁剪 |

---

## 5. 调试

**DEBUG 包**（`BuildConfig.DEBUG`）里已有三处日志（Timber，同时落盘到 `/sdcard/hkrime/logs/hkrime-YYYYMMDD.log`）：

| 日志 | 位置 | 含义 |
|---|---|---|
| `twelvekey:build … ms=` | `TwelveKeySnapshotBuilder.build` | 一次快照构建耗时（含引擎枚举 + 分组 + 排序） |
| `twelvekey:panel order=… keys=…` | `rankPanelItems` | 面板某个组的前 8 条及**两个比较键**：`字:u用户分/g综合分`；核对时先看 `u` 降序、同 `u` 再看 `g` 降序（只看 `g` 会因为用户习惯插队而「看起来不降序」，属正常） |
| `twelvekey:topup split head=… rest=…` | `splitParts` | 首字母相拼时把码串切成 head/rest 的结果（日志名沿用旧名） |

抓日志：

```bash
adb pull /sdcard/hkrime/logs/hkrime-$(date +%Y%m%d).log /tmp/app.log
# 面板排序核对：每行先按 u（用户分）降序、同 u 再按 g（综合分）降序
grep "twelvekey:panel order" /tmp/app.log | tail -20
```

冒烟脚本（离线核对，不必装 UI）：`tools/rank-sanity.py` —— 读字表把某个读音的字按综合分排序打印，肉眼确认生僻字在最后。

---

## 6. 测试

```bash
export JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9
./gradlew :app:testDebugUnitTest        # 全部 JVM 单测（2026-11-11 时 140 个用例）
```

| 文件 | 用例数 | 覆盖什么 |
|---|---|---|
| `core/twelvekey/CandidateRankingTest.kt` | 12 | 候选条：词组分区/截断/去空/稳定性 + 面板：组内三级排序、分组顺序、组数上限、段组关联排序 + 两条真实数据校验 |
| `core/twelvekey/SyllableGroupCountTest.kt` | 数条 | 段組音節數上限（`SEGMENT_SYLLABLE_LIMIT`/`_RICH`）覆盖率守门 + 每首码组数 |
| `core/twelvekey/PanelGoldenTest.kt` | 1 | App 五源 vs `panel_probe.py json` 的 32 码 golden 对拍（改面板规则时必跑） |

设计原则：**能用纯函数测的绝不点 UI**。UI 只用来验证「装配是否把纯函数结果正确呈现」（例如嵌入式预览与候选条第 0 条是否一致）。

### 6.1 段組音節數上限（現值 16 / 豐富鍵 24）

2026-11-10 曾用 `MAX_SYLLABLE_GROUPS = 8`（下表是當時的覆蓋率依據）；2026-11-11 重構後改為
`SEGMENT_SYLLABLE_LIMIT=16`，可用音節 >16 的「豐富鍵」放開到 `SEGMENT_SYLLABLE_LIMIT_RICH=24`，
並且「段碼剛好是完整音節」時只列該音節。下表僅作歷史參考：

| N | 最低覆盖 | 平均覆盖 | 最高覆盖 |
|---|---|---|---|
| 4 | 16.6% | 27.2% | 45.6% |
| 6 | 22.8% | 36.5% | 64.2% |
| **8** | **28.8%** | **44.2%** | **76.0%** |
| 12 | 39.6% | 55.9% | 87.5% |

字表/音節規則大改後重跑 `SyllableGroupCountTest` 看覆蓋表再調。

---

## 7. 历史决策（为什么是现在这样）

| 决策 | 起因 | 结论 |
|---|---|---|
| 词组必须永远在单字前 | 用户 2026-11-09：「候选栏应该永远是组词先，再单字的排序（根据所有因素排序后的列表再做分区）」 | 稳定分区，不重排两级内部 |
| 面板单字按频度排序 | 用户 2026-11-10：词典无 weight，生僻字挤掉「以」 | 用户字频 → 权威字频 → 引擎序 |
| 权威字频用 rime-ice 8105 | 用户提示「雾凇可能包含已发布的高频排序」 | 25 亿字语料权重，比自造规则可信 |
| 段组放面板、不放候选条 | 用户 2026-11-10：「只在固定候选栏上做改变」后被否决，改回面板承担「逐段选字」 | 候选条只放引擎候选（2026-11-12 起按引擎顺序，不再词组优先），面板负责拆段选字 |
| 拆段不再依赖 `'` | 用户 2026-11-10：「普通输入也要能逐段选」 | `splitFirstSyllable` 自动拆，`'` 仅作显式边界 |
| 空读音组排最后 | 原先它排在单音节组之后、多音节组之前，位置尴尬 | 兜底分组永远最后 |
| 首字母相拼只列高频音节 | 用户：「不可能每个首字母的所有组合都罗列，那不科学也没必要」 | `syllablesForSegment(head, 16/24)`；完整音節段只列該音節 |

---

## 8. 排查清单

| 症状 | 先看哪里 |
|---|---|
| 候选条里词组排序不对 | `candidateBarBinder.bind` 收到的 `snapshot.barEntries` 对不对 → `rankBar` 是否被绕过 |
| 候选条出现生僻单字挤掉常用字 | 这不是候选条的责任（候选条不做单字频度排序），看引擎候选本身 / `SelectionMemory` 学习结果 |
| 面板某组生僻字靠前 | `twelvekey:panel order` 日志里 `u`/`g` 是否按比较键降序（`u` 不同时不必看 `g`）；不够就查 `hakka_char_rank.txt` 是否被正确加载（启动日志 `charRank=8183`、`scores=9379`） |
| 面板没有单字组、只有词组 | `twelvekey:topup split` 是否出现；`splitFirstSyllable` 对当前码串返回 null 的话就不会有段组 |
| 面板左列读音顺序乱 | `groupCandidates` 的排序键（空读音是否排最后、音节数是否升序） |
| 段组点击后没「按顺序推进」 | `pickSegmentedCandidate` 的 `restCode` 是否透传（`CandidatePanelAdapter.Item.restCode`） |
| 嵌入式预览与候选条第 0 条不一致 | `TwelveKeySnapshot.previewText` / `inlineText` 是否仍由 `onTwelveKeySnapshot` 单点发布（见 10 §15.44） |
| 词表/权威表更新后排序没变 | 资源名/格式（`字<TAB>权重`）与构建是否真的打进去（`apk` 内 `assets/rime/hakka_char_rank.txt`） |

---

## 9. 已知取舍 / TODO

- **用户记忆容量有限**（字 400 / 词 3000），溢出按频次裁剪；长期使用后极端偏好会被稀释，需要时可改成「频次 + 最近使用时间」双因子。
- **权威表不覆盖方言专用字**：这类字只能靠客家词表字频，若两表都没有权重则综合分为 0，只能靠引擎顺序。
- **`syllablesForSegment` 的「高频」= 音节在词表里出现的字频总和**，不是音节本身的读音频率；若日后有客家话音节频率表可以替换。
- **面板组数上限 200** 对 rime-ice 这类巨型方案可能不够（当前客家 3 方案远低于），若接入大方案需要改成「虚拟化列表 + 分组惰性构建」。
