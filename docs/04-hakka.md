# 客家话方案与数据

## 方案

`default.custom.yaml` 的 schema_list 启用：

| ID | 名称 | 用途 |
|---|---|---|
| `hakka_jiugong` | 客语12键 | 12 键拼写、简拼、App 侧分组与学习 |
| `hakka_qwerty` | 客语26键 | 全字母客家话输入 |
| `ipa_yunlong` | 雲龍国际音标 | 音标输入 |

`luna_pinyin.schema.yaml` / `.dict.yaml` 随包提供反查依赖，不属于默认启用的三个方案。
运行时可以启用用户目录中的其它方案，`SchemaCompat` 为全字母方案补充精确读音 comment 等适配。

## 十二键编码

`hakka_jiugong.schema.yaml` 使用原生多键 `multi_key: true`（0011 补丁），直接将按键语义音素集送入 prism 切分，不再依赖 `xlit`/`xform` 键位替换 hack 或冗余 `derive` 规则；`alphabet`/`initials` 严格限定为客语实际字母表 `abcdefghijklmnopqrstuvz`（不含 `w/x/y`），`abbrev` 仅覆盖客语 20 个标准声母（3 个双字母 `th/nz/ng` + 17 个单字母 `b c d f g h j k l m n p q s t v z`，`r` 为末段儿化后缀）。

方案使用 `script_translator`，`enable_sentence: true`、`max_homophones: 7`、`spelling_hints: 100`；
全拼长句由 0012 补丁走完整音节子图组句（最长 20 音节 / 64 键），并由双层马尔可夫语言模型（静态 `hakka-zh-hans.gram` + 动态 `hakka.bigram.tsv`，满 2 次自动升格整词进 `hakka.userdb`）引导排序。
`custom_phrase` 通过 tabledb 提供自定义编码短语。`ywz`、`emoji` 开关对应 OpenCC 扩展（`lenth` 联想过滤已移除）；
普通话反查使用反引号前缀（依赖 `rime_ice_qwerty`）。

## 词典

`hakka.dict.yaml` 是主词典（`w` 韵母已全部并入 `u`，不再区分 `cw/zw` 与 `cu/zu`）；App 运行时音节集合只取主词典字表。
扩展词表按其 `import_tables` 递归读取，不能把其它方案词典混入 12 键索引。
带读音行只取第二列作为读音；任一音节不属于运行时集合则整条过滤。
无读音词表可通过字表反推联想读音，整串码索引仅收录自带读音的词条。

添加词条：在实际使用的词典或 import 表写 `文字<TAB>音节序列<TAB>可选权重`，
确认音节合法，再静态校验、同步设备、部署并重启输入法加载 App 索引。
不要预置个人 userdb。学习词通过 `PhraseMemory` 与候选条、面板共享；
用户词库 sync 导出用于测试语料，不作为运行时面板的额外数据源。

音节约束见 [18](18-syllables.md)，部署见 [07](07-opencc-and-rime-data.md)。
