# 候选面板实现

## 数据与入口

TextInputManager → TwelveKeyController.refresh → TwelveKeySnapshotBuilder → 不可变快照 →
CandidateBarBinder / CandidatePanelController。面板只画快照并回调选择，不独立访问引擎。
`CandidateItem.index` 是全局引擎下标，`segment/restCode` 参与 App 候选点击分发。
普通引擎项 index ≥ 0；App 项 index=-1；合成学习词 segment=-2；推荐组 segment=-3。
PanelRow 的 0/1 表示单字组/词行，不能混同所有 CandidateItem 的点击语义。

面板关闭时 `groups` 为空；可选性通过 `hasCandidates = barEntries 非空或 groups 非空` 判断。
引擎空时可计算字典段组给候选条兜底，但发布 groups 仍遵守面板状态。
固定条最多 12 项、原料固定前 64 条；面板最多 600 条。全量快照的前 12 项须与限流快照一致。

## 六个 App 来源与引擎源

| 来源 | 函数 | 语义 |
|---|---|---|
| SYNTH_SINGLE | synthSingleRows | 整串恰好是一个完整音节的单字组 |
| SPLIT_SINGLE | splitSingleRows | 其它切法第一待选段的单字组 |
| ASSOC | assocRows | 两完整音节标题的联想词行 |
| INITIAL_COMBO | initialComboRows | 首字母组合与“简拼+完整末音节”的真实词行 |
| WHOLE_WORD | wholeWordRows | 整串码前缀命中的多音节词，第一音节须打全 |
| ENGINE | engineRows | 引擎 comment 分组，转成 PanelRow 后参与同一流水线 |
| ISOLATED | isolatedRows | 孤单联想字母行（`k`/`l`）：剩余码无完整音节时通配列出该字母下全部单音节 |

字表来自主词典；词表只读 hakka 的 import_tables。运行时音节集合只用主词典，不读标准集资产。
带读音词条任一音节越界则整条丢弃；PhraseMemory 的文字/读音索引与词表索引合并，
面板学习词和候选条同源，不另读 userdb 快照。

## 单字组与音节

splitParts 枚举首段切法。段码是完整音节时只取 exact：`4i → mh/mi/nh/ni`、
`ea → da/fa`、`74 → po/so`；只有前缀或 exact 为空才展开。
每码位普通上限 16 个音节，丰富键上限 24，备选切法配额减半；段组单字上限 1000。
`r` 只允许在无剩余码的词尾，不能成为第一段或中间段简拼。
简拼候选在面板必须具体化，不显示带星号或单字母通配读音。

## 联想行

行标题必须是两个完整音节，如 `than dung`；查询用 `(第一音节, 第二音节)` 精确索引。
有剩余码时按每段第一个码位的首字母集合选音节，元音自由：`ee → d/f`、`5 → k/l`。
剩余码开头已打成完整音节时，只取已完成的第二音节；第一段是完整音节则取 exact。
没有剩余码时按首段码前缀展开其全部后续音节；剩余码展开为空时也退回全部后续。

命中行按 `(第一音节首字母, 第二音节首字母)` 分桶。桶内学习词优先，再按行内词数降序、
读音升序；桶间与第一首字母组间轮转，取行预算 12，行内最多 20 词。
选行顺序与显示排序分开，最终显示仍经过统一流水线。

## 首字母组合与整串词

每键出一个合法首字母做笛卡尔积；aeiou 不作简拼首字母，r 只在末键。
另枚举前段简拼 + 完整末音节，完整性按音节集合判定。
两类组合总上限 64，行数上限 8；两轮各给每组合 1 行，有词才出行，标题仍为具体读音。
纯组合与“简拼+完整音节”**交错**排列：纯词表长词会让纯组合行占满 8 行，若纯组合全在前，
`b|cam` 这类混合模式永远轮不到；组合内某读音含学习词时优先出该行。
纯词表 3~6 字 CJK 逐字反推首读音后并入首字母组合索引；整串码索引仍只收带读音条目。
WHOLE_WORD 仅通过轻量整串码索引查询，不把无读音的大词表塞进该索引。

## 孤单联想（ISOLATED）

- 触发：剩余码 >=2 键，且整串码**无法完整切成音节**（既不是单个完整音节，也不是可完整切分的多音节）。
  判定用**运行时集合 ∪ 标准音集** `assets/rime/hakka_syllables.txt`（841）：
  `gia` 由 `hakka_zon` 的單字讀音併入運行時、標準集也有，码 `iia` 已算完整音節；
  `4a834 → ma jun`、`4a845 → ma jok` 这类多音節剩餘碼也能完整切分，都不出孤立鍵；
  `ea7 → dap`、`eai → dai` 同理不出。
- 内容：只看剩余码**第一个码位**的首字母集合（`55 → k/l`），每字母一行；
  items 是該字母下的**全部**單音節（通配口徑：只看首字母，不受剩餘碼前綴限制；
  例如 `55` 列出所有 k/l 開頭音節，標準集音節也納入，按音節詞頻降序）。
- 显示：左列字母行（reading=字母，不带数量）；点字母行右列列出该字母下全部单音节；
  点音节进入钻取层，右列列出该音节的字，首项是「← 返回」。
- 选字：与左列单字组同一暂存语义——先进 `pendingPick` 留在 preedit、只消耗首键，
  剩余码重新喂回引擎继续组下一音节；`heldPicks` 记完整原码，退格整段反悔并还原；
  不写 PhraseMemory（单字不记词条）。
- 位置：固定排在推荐组之后、单字组之前（tierAndSort 给 -1 档），行数也计入 capRows。
- 与 `panel_probe.py` 的 `Data.isolated_syllables()` 同口径。

## 左列折叠（展示层）

- 多音节词行（联想 `man jiu`、组合 `jen cin kung ze`、整串词行）不再单独占左列一行，
  而是折进**第一个音节**的组：`man jiu` 的詞进 `man` 组，左列只看到 `man`。
- 只改展示：`snapshot.groups`、点击语义（`item.segment`）、推荐来源、golden 都不变；
  右格点组时先显示折进来的词、再显示该音节的字；词仍按原 segment/restCode 走部分选词语义。
- 推荐组（reading 空）与孤单联想字母行（segment -4..-6）**不折叠**，孤立音节 `d`/`f` 保持独立行。
- 连续选字时左列保持当前折后的 reading；新输入回第 0 组。
- 实现：`PanelRow.kt` 的 `foldGroupsForDisplay()`（纯函数，`PanelDisplayFoldTest` 覆盖）。

## 唯一合并流水线

```text
isolated + synth + split + engine + assoc + combo + wholeWord
→ mergeRows → collapsePrefixRows → tierAndSort → capRows
→ toSyllableGroup
```

- mergeRows 按小写 reading 合成一行，保留首行的 segment/restCode；合并 items 和 sources。
  顺序由上面的拼接顺序决定，段组在前，引擎项在后续联想/组词补集前。
  加入 WHOLE_WORD 行时按小写文字去重；其它追加按精确文字去重，以保留 J/j 等差异。
- collapsePrefixRows 只在多音节行间找最长已有读音前缀并入；单音节不吸收词行。
  跨行收敛按小写文字去重。
- tierAndSort 按 reading 的非空 token 数分单节/多节/空串档，档内读音字典序，再按首候选文字；
  ISOLATED 行单独给 -1 档，固定在单字组之前、推荐组之后。
  `…` 占位有一个 token，当前落单节档，以首候选反查读音作为排序键。
- capRows 全局上限 200，超额先裁引擎行（引擎只是窗口，App 源优先保留）；只有 App 源自身就超额时，
  才压缩带 INITIAL_COMBO 来源的行，再从尾部截断。
- sources/hasUserWord 是内部信息，投影后不进入 UI。

源函数只负责本源构造与选行轮转，不另起跨源去重或最终排序。
推荐组在流水线外生成，固定在左列首位：30 词按 2/3/4+ 字**各 10 个**（某档不足由其余档
依序补满，学习词行优先保留），另有 10 个单字。
推荐词直接沿用引擎与面板候选，App 只做字数配额、学习词优先与去重，**不按码组合二次拦截**
（引擎给什么就展示什么，多音节/长词是否合理属于引擎行为）。

## 点击、性能与验收

TextInputManager 先判断部分选词，pendingPick 保留在 preedit，heldPicks 支持退格整段反悔。
能交给引擎的完整选择保留全局 index；所有最终提交与学习都在统一输入层执行。
孤单联想行点字母 → 换右列（不改输入）；点音节 → 钻取字；点字 → 存 pendingPick 留在 preedit、
只消耗首键，剩余码重新喂回引擎；退格按 heldPicks 整段还原原码；「← 返回」只恢复快照右列。
全量构建走后台；ENGINE_LOCK 保证与按键互斥。首次无快照开面板的 refreshSync 是当前待关注入口。

修改规则前先更新文档并确认语义；改后同步 panel_probe.py 与 golden，运行 selftest、JVM、
真实语料、装机与性能门禁。不能把 UI 冒烟等同于语料覆盖。
诊断读 `<userDataDir>/logs/diag.log` 的 sources、groups、bar、prefixWords、prefixIdx、all，
结合 build/latency/enum/emit 和 bar drift；日志用于回答行是否产生、是否被合并/截断或没有渲染。
