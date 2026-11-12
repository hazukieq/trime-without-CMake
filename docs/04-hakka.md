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

`hakka_jiugong.schema.yaml` 的 algebra 定义声母缩写、音节别名和按键映射。
`th → w`、`nz → y`、`ng → 1`；其它字母经 xform/xlit 合并到对应键。
App 的 `TwelveKeyPreedit.codeOf`、PC 测试的 CodeOf 与诊断脚本必须同口径。

方案使用 `script_translator`，`enable_sentence: false`，`spelling_hints: 100`；
用户词典开启，但简拼路径受本地性能补丁约束。候选 comment 是面板具体读音与 preedit 对齐的数据源。
`custom_phrase` 通过 tabledb 提供自定义编码短语。`ywz`、`lenth` 开关对应 OpenCC 扩展；
普通话反查使用反引号前缀。

## 词典

`hakka.dict.yaml` 是主词典；App 运行时音节集合只取主词典字表。
扩展词表按其 `import_tables` 递归读取，不能把其它方案词典混入 12 键索引。
带读音行只取第二列作为读音；任一音节不属于运行时集合则整条过滤。
无读音词表可通过字表反推联想读音，整串码索引仅收录自带读音的词条。

添加词条：在实际使用的词典或 import 表写 `文字<TAB>音节序列<TAB>可选权重`，
确认音节合法，再静态校验、同步设备、部署并重启输入法加载 App 索引。
不要预置个人 userdb。学习词通过 `PhraseMemory` 与候选条、面板共享；
用户词库 sync 导出用于测试语料，不作为运行时面板的额外数据源。

音节约束见 [18](18-syllables.md)，部署见 [07](07-opencc-and-rime-data.md)。
