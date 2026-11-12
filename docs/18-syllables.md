# 客家话音节集合

## 两种集合

| 集合 | 来源 | 用途 |
|---|---|---|
| 运行时集合 | 主词典 + 進口詞典（`import_tables`，尤其 `hakka_zon`）的單字讀音，经標準集過濾 | App 认可有字的音节（含方言字 `渠 gia`） |
| 标准集 | `assets/rime/hakka_syllables.txt` | 约束诊断脚本与测试，须覆盖主词典音节 |

主词典 694 个音節；运行时集合再并入进口词典的单字读音，但只保留标准集里有的音节
（`hakka_zon` 的 `gia` 要收，方案外拼写 `bon/hop` 不要）。标准集为 841 个，
生成源为 Hkdict 的 `neo-frontend/src/data/hakkaSyllables.js` 与主词典集合的并集，可带显式补遗。
App 加载标准集仅用于：進口詞典讀音過濾、完整音節判定（孤單聯想互斥）、孤單聯想的通配音節列表；
不扩大小节搜索的其它口徑。实际设备的主词典可不同，计数以实际数据为准。

## 规则

- 只读 hakka.dict 的 import_tables；不因资源目录里有其它方案就读取其词表。
- 带读音词条每个音节都须属于运行时集合，任一不合法则整条丢弃。
- `r` 是儿化后缀，只能在输入末段，不能作为前段/中段首字母简拼。
- 完整段码只列 exact 音节；没有 exact 或只有前缀时才展开。
- 所有面板标题使用具体客家话音节，不生成通配标题。

## 生成与验证

在 `hkrime/` 执行，明确使用随包主词典，避免默认真实快照影响资产生成：

```bash
python3 tools/panel_probe.py --data app/src/main/assets/rime build-syllables
python3 tools/panel_probe.py --data app/src/main/assets/rime selftest
```

JS 默认路径为 `/home/hazukie/Projects/Hkdict/neo-frontend/src/data/hakkaSyllables.js`，
其它环境将实际 JS 路径作为 build-syllables 的位置参数；`--dict`、`--extra`、`--out` 可显式覆盖。
selftest 默认读取 `/tmp/hkrime-real-data` 中已有的本地词库快照，不自动连接设备或刷新数据。
它运行 69 个固定断言，检查音节、索引和面板参考算法；不是全词库穷举，也不执行 Android 界面。
其中多个断言预设词库含 `Linux\tli nak si`。使用不含该词的数据集会失败，不能直接归因于算法回归。
随包 hakka_zon.dict.yaml 包含 Linux/linux 的 li nak si 读音，可直接用 assets 运行这些断言。
验证前仍须明确数据集与样例前提。资源引用由 verify-rime-assets.py 检查，
规则镜像由 PanelGoldenTest 验证，完整真实语料测试见 [16](16-librime-coverage-test.md)。
改规则需同步 App、脚本、golden 和真实语料测试，不通过修改标准集掩盖运行时词典问题。
