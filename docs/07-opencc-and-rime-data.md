# 07 · OpenCC 与 RIME 数据：从 assets 到能用的输入法

> 本篇讲「数据」而不是「代码」：`app/src/main/assets/rime/` 里的文件怎么变成用户手机上能用的词典、方案与简繁转换。
> 读完应能回答三个问题：**这些文件为什么必需？它们被复制到哪里、又被谁加工？少一个会怎样？**
>
> 事实快照：`ebb18c1`（当前 HEAD 以 `git log -1` 为准）。缺口修复提交：`726a209`。

## 目录

1. [速查](#1-速查)
2. [数据分层与部署管线](#2-数据分层与部署管线)
3. [OpenCC：txt / json / ocd2 三种文件的关系](#3-opencctxt--json--ocd2-三种文件的关系)
4. [管线细节：谁在什么时候做什么](#4-管线细节谁在什么时候做什么)
5. [简繁与地区转换：s2t ≠ s2tw ≠ s2twp](#5-简繁与地区转换s2t--s2tw--s2twp)
6. [客家话数据清单](#6-客家话数据清单)
7. [方案结构解读](#7-方案结构解读)
8. [原缺口：已全部修复（726a209）](#8-原缺口已全部修复726a209)
9. [扩充与自定义指引](#9-扩充与自定义指引)
10. [怎么验证](#10-怎么验证)

---

## 1. 速查

| 项 | 值 |
| --- | --- |
| assets 内 rime 数据 | `app/src/main/assets/rime/`，共 **23 项** = **22 个文件 + `opencc/` 目录** |
| OpenCC 资源 | `assets/rime/opencc/` 下 **34 个**：**18 个 `.txt` 词典 + 16 个 `.json` 转换配置** |
| 运行期 sharedDataDir | 真机实测 `/storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime` |
| 运行期 userDataDir | 默认同 sharedDataDir；真机实测该机为 `/storage/emulated/0/rime`（用户既有偏好） |
| ocd2 产物 | 部署时由 `.txt` 生成，fresh 部署实测 **18 个**，与 18 个 `.txt` 一一对应 |
| opencc 目录条目 | 运行期 **52** = 34 个 assets 文件 + 18 个 `.ocd2` |
| librime 构建产物 | `userDataDir/build/` 下 fresh 部署实测 **10 个 `.bin`**（见 §4.3） |
| 方案列表 | **3 项**：「客语12键」「客语26键」「雲龍國際音標」 |
| OpenCC 版本 | `ver.1.1.4`（`Rime.get_opencc_version()`） |

> 四个数字常被混淆：**23 = assets/rime 条目数；34 = opencc 子目录内文件数；18 = ocd2 数；52 = 运行期 opencc 目录条目数**。

---

## 2. 数据分层与部署管线

```
┌─────────────────────────── APK（只读）───────────────────────────┐
│ app/src/main/assets/rime/                                        │
│   主题：trime.yaml  tongwenfeng.trime.yaml  hakka_theme...        │
│   方案：hakka_jiugong / hakka_qwerty / ipa_yunlong / luna_pinyin  │
│   词典：hakka.dict + 8 张 hakka_*.dict + luna_pinyin / ipa_yunlong│
│   配置：default.yaml  default.custom.yaml  symbols.yaml  ...       │
│   opencc/  18×*.txt（词典源） + 16×*.json（转换配置）              │
└───────────────────────────────┬──────────────────────────────────┘
                                │ Config.prepareRime() 首次/换版本时整目录复制
                                ▼
┌──────────────── sharedDataDir（可写，assets 的镜像）─────────────┐
│ .../files/rime/  ← 与 assets 同名同结构                           │
│   opencc/ 34 个源文件  ──OpenCCDictManager.internalDeploy()──┐   │
│                                                              │   │
│                    每个 *.txt → 同名 *.ocd2（18 个）  ◀──────┘   │
└───────────────────────────────┬──────────────────────────────────┘
                                │ Rime.get(ctx,true) → setup/initialize/deploy
                                ▼
┌──────────────── userDataDir（用户数据，可跨版本保留）────────────┐
│ build/  10 个 *.bin（hakka.* / luna_pinyin.* / hakka_qwerty.prism │
│         / ipa_yunlong.*）                                        │
│ *.userdb（用户词频，librime 首次部署自建；本仓库不预置）           │
└──────────────────────────────────────────────────────────────────┘
```

要点：

- **assets 是只读源**，不是运行时目录；改 assets 必须重装 APK 才会生效。
- sharedDataDir 是 assets 的**整目录副本**（首次安装或版本变化时复制，见 `Config.java:178-212`）。
- userDataDir 里是 librime 部署的**二进制产物**与用户词库；`build/` 只在 userDataDir，不在 sharedDataDir。
- **同版本覆盖安装不会重拷 assets**（版本未变时只补 `trime.yaml`）——所以仪器测试会先清空两个目录强制 fresh 部署。

---

## 3. OpenCC：txt / json / ocd2 三种文件的关系

| 文件 | 角色 | 谁读它 |
| --- | --- | --- |
| `*.txt`（18 个） | 词典**源文本**（如 `STCharacters.txt` 简体→繁体字表；一行一个词条，多值用空格分隔） | 部署时由 `OpenCCDictManager` 读，转成 `.ocd2` |
| `*.json`（16 个） | 一次转换任务的**配方**：分词方式、用哪些词典、是否带词汇替换 | librime 的 simplifier / `openccConvert()` |
| `*.ocd2`（运行期 18 个） | 已编译的二进制词典（OpenCC 的 marisa-trie 格式） | librime / OpenCC 运行时 |

**简记：json 是「怎么转」，txt 是「依据什么转」，ocd2 是把 txt 加工后的运行时形态。**
librime 侧的 simplifier 只认 `.ocd2`，所以**只有 txt 没有部署＝转换静默失效**；反过来只留 ocd2 也能跑，但换 OpenCC 版本/改词典后无法再生成。

### 3.1 多值词条与两条不同的读取路径（重要）

`ywz` / `lenth` 这类「一个词 → 多个结果」的词典依赖 OpenCC 的**多值语义**，但 librime 与 JNI 的读法不同：

| 路径 | 读取方式 | 结果 |
| --- | --- | --- |
| librime `simplifier` 滤镜 | `librime/src/rime/gear/simplifier.cc` → `Opencc::ConvertWord`，取 `DictEntry::Values()` **全部值** | 多个值都能成为候选（如 `不屑` → 不屑 / 😒） |
| JNI `Rime.openccConvert()` | 直接走 OpenCC `SimpleConverter`，**只取第一个值** | 只返回首值 |

因此仪器测试对 `ywz`/`lenth` **只断言「配置可加载、转换不崩溃」**，不断言多值输出；
多值正确性由静态脚本 `script/verify-rime-assets.py` 抽查（见 §10.3），界面上的实际候选需人工冒烟。

### 3.2 历史事故：OpenCC 资源曾被删光

`92c7aa5`（2023-09-15，标题 "close log"）删除过 `assets/rime/opencc/` 下**全部 29 个文件**
（`git show --stat 92c7aa5 -- app/src/main/assets/rime` → `29 files changed, 59772 deletions(-)`），直接后果是：

- 简繁转换、台湾正体、香港字形等所有 OpenCC 功能失效；
- `Rime.openccConvert()` 找不到配置；
- `simplifier` 滤镜没有 ocd2 可用。

`2b50c1f`（2026-11-08）从 `92c7aa5^` **恢复了 29 个**（15 txt + 14 json）；`726a209` 又补了
`ywz.{json,txt}`、`lenth.{json,txt}`、`JPVariantsRev.txt` 共 5 个，达到今天的 34 个。恢复后真机实测 4 组转换全部正确（见 §5）。

> 这也是「不要凭体积判断文件价值」的教训：这几十个文件只占几百 KB，却是整套简繁功能的前提。

### 3.3 16 个 json 一览

上游 14 个：`hk2s` `hk2t` `jp2t` `s2hk` `s2t` `s2tw` `s2twp` `t2hk` `t2jp` `t2s` `t2tw` `tw2s` `tw2sp` `tw2t`；
本仓库新增 2 个：**`ywz`（颜文字/表情）、`lenth`（网络/方言词联想）**。

命名规则：`源→目标`，`s`=简体、`t`=繁体、`tw`=台湾正体、`hk`=香港字形、`jp`=日本新字体；带 `p` 的（`s2twp`、`tw2sp`）表示**含词汇替换**（phrase）。

### 3.4 18 个 txt 一览

上游 15 个：`STCharacters` `STPhrases` `TSCharacters` `TSPhrases` `TWVariants` `TWVariantsRev` `TWVariantsRevPhrases` `TWPhrases` `TWPhrasesRev` `HKVariants` `HKVariantsRev` `HKVariantsRevPhrases` `JPVariants` `JPShinjitaiCharacters` `JPShinjitaiPhrases`；
新增 3 个：**`JPVariantsRev`**（366 行，`jp2t.json` 的反向依赖）、**`ywz.txt`**（141 行 / 141 个多值词条，如 `不屑 → [不屑, 😒]`）、**`lenth.txt`**（31 行 / 31 个多值词条，如 `qq → [qq, QQ会员, QQ飞车, QQ音乐, …]`、`客家话 → [我问渠, 冇, 係, 该, …]`）。

`ywz.json` / `lenth.json` 的写法（规范 OpenCC 格式）：

```json
{
  "name": "ywz",
  "segmentation": { "type": "mmseg", "dict": { "type": "ocd2", "file": "ywz.ocd2" } },
  "conversion_chain": [ { "dict": { "type": "group",
      "dicts": [ { "type": "text", "file": "ywz.txt" } ] } } ]
}
```

> 这两份 json 是从设备上的原版**重写**而来：原 `ywz.json` 有重复 `"type"` 键，原 `lenth.json` 引用了不存在的
> `lenth.txt`。现在 segmentation 用 `ocd2`（部署时由同名 txt 生成），conversion_chain 用 `text` 词典，自洽且可离线校验。

---

## 4. 管线细节：谁在什么时候做什么

### 4.1 复制 assets → sharedDataDir：`Config.prepareRime()`

位置：`app/src/main/java/com/hazukieq/hkrime/data/Config.java:178-212`。

- 判断 sharedDataDir 是否已存在（`isExist`）、版本是否不同（`isOverwrite = AppVersionUtils.isDifferentVersion(pref)`）。
- 首次安装：整目录复制 assets/rime；升级且版本变化：覆盖复制；仅缺失：只补 `trime.yaml`。
- 复制后轮询等待 `trime.yaml` 出现，再补一个空的 `default.custom.yaml`（缺它会导致方案列表为空）。
- 末尾调用 `Rime.get(context, !isExist)`：**首次安装才强制部署**。

### 4.2 txt → ocd2：`OpenCCDictManager.internalDeploy()`

位置：`app/src/main/java/com/hazukieq/hkrime/data/opencc/OpenCCDictManager.kt:49-58`；
native 声明 `external fun openccDictConv` 在同文件 **`:71`**（`:15` 是 `System.loadLibrary("rime_jni")`）。

`Rime.get(ctx, true)` 时先执行 `OpenCCDictManager.internalDeploy()`：遍历 sharedDataDir/opencc 下的文本词典，
逐个调用 native 的 `openccDictConv(src, dest, false)` 生成同名 `.ocd2`。fresh 部署实测生成 **18 个**。

### 4.3 librime 部署：生成 build/*.bin

`Rime.get(ctx, true)` → `initialize()` → `deploy()`，librime 读取 sharedDataDir 下的 `*.schema.yaml` / `*.dict.yaml`，
在 userDataDir/build 生成 `prism.bin`（音节索引）、`table.bin`（码表）、`reverse.bin`（反查）。

fresh 部署实测 **10 个**：

| 来源 | 产物 |
| --- | --- |
| `hakka.dict.yaml`（script_translator） | `hakka.table.bin`、`hakka.reverse.bin`、`hakka.prism.bin` |
| `hakka_qwerty.schema.yaml` | `hakka_qwerty.prism.bin` |
| `luna_pinyin.dict.yaml` | `luna_pinyin.table.bin`、`luna_pinyin.reverse.bin`、`luna_pinyin.prism.bin` |
| `ipa_yunlong`（table_translator） | `ipa_yunlong.table.bin`、`ipa_yunlong.reverse.bin`、`ipa_yunlong.prism.bin` |

### 4.4 配置优先级

- `default.yaml` 自带 `schema_list: bopomofo_tw / luna_pinyin_simp / luna_pinyin`；
- `default.custom.yaml`（12 行）用 `patch:` 覆盖为 `hakka_jiugong / hakka_qwerty / ipa_yunlong`，并设 `"menu/page_size": 5`。

**custom 优先**，所以用户实际看到的方案列表来自 `default.custom.yaml`。`726a209` 之后，这三项都有实体文件（§8）。

---

## 5. 简繁与地区转换：s2t ≠ s2tw ≠ s2twp

真机仪器测试（`RimeDataTest`）断言的四组转换：

| 调用 | 输入 | 输出 | 含义 |
| --- | --- | --- | --- |
| `s2t.json` | 汉字 | 漢字 | 简体→繁体，**只做字形** |
| `t2s.json` | 漢字 | 汉字 | 繁体→简体 |
| `s2tw.json` | 软件 | 軟件 | 转台湾**字形**，不做词汇替换 |
| `s2twp.json` | 软件 | 軟體 | 转台湾**用词**（software→軟體） |

`s2tw` 与 `s2twp` 的差别是最容易被误判的：前者只换字形（软件→軟件），后者的 `p`＝phrase 会做词汇级替换（软件→軟體）。写文档、写测试断言时不要混用。

---

## 6. 客家话数据清单

### 6.1 来源与措辞（重要）

2026-11-08 从用户 `rime.zip` 导入了 **17 个文件**，其中：

- `trime.yaml`：**保留仓库已改名的版本，未被覆盖**（zip 里那份只作对照）；
- **11 个客家话方案/词典**（见 6.2）；
- **4 个通用配置**：`default.yaml`、`default.custom.yaml`、`custom_phrase.txt`、`symbols.yaml`。

另有 1 个 `user.yaml` 是旧机状态残留，**已在 `726a209` 删除**（`schema_access_time` 指向本仓库不存在的方案）。

> 因此不要再写「16 个客家话文件」：准确说法是「rime.zip 共 17 个文件 = 1 主题 + 11 客家话 + 4 通用 + 1 已删除的 user.yaml」。

### 6.2 客家话方案/词典（11 个，来自 zip）

| 文件 | 行数 | 作用 |
| --- | --- | --- |
| `hakka.dict.yaml` | 10,545 | 主字典，`name: hakka`、`version: "20.20.20"`、`sort: by_weight`、`use_preset_vocabulary: true` |
| `hakka_zon.dict.yaml` | 3,538 | 扩展词典（`import_tables` 之一） |
| `hakka_website.dict.yaml` | 5,252 | 网络用语 |
| `hakka_sougou_words.dict.yaml` | 157,336 | 搜狗词库（体量最大） |
| `hakka_poems.dict.yaml` | 17,871 | 诗词 |
| `hakka_places.dict.yaml` | 4,762 | 地名 |
| `hakka_oftenwords.dict.yaml` | 1,206 | 常用词 |
| `hakka_mobilewords.dict.yaml` | 2,949 | 手机词 |
| `hakka_cn_en.dict.yaml` | 1,681 | 中英混合 |
| `hakka_jiugong.schema.yaml` | 175 | 12 键方案（见 §7） |
| `hakka_theme.trime.yaml` | 3,923 | 主题「客家同文风」，`config_version: "3.0"` |

`hakka.dict.yaml` 的 `import_tables` 明确列出 8 张扩展词典：`hakka_places`、`hakka_sougou_words`、`hakka_poems`、`hakka_zon`、`hakka_mobilewords`、`hakka_oftenwords`、`hakka_website`、`hakka_cn_en`（`hakka.dict.yaml:28-36`）。

数据来源：字典文件头注释说明为**广西玉林兴业县高峰乡客家话**，作者 hazuki erii / 叶月绘梨依，发音取自高峰镇龙文村龙源坪，兼顾老中青三代读音与古韵书反切（`hakka.dict.yaml:1-26`）。

### 6.3 方案与依赖（`726a209` 新增 5 个文件）

| 文件 | 大小 | 角色 |
| --- | --- | --- |
| `hakka_qwerty.schema.yaml` | 3,840 B / 134 行 | **客语26键**（作者 叶月绘梨依，`version: "2021.1.22"`）；`translator.dictionary: hakka`、`prism: hakka_qwerty`、`reverse_lookup.dictionary: luna_pinyin` |
| `ipa_yunlong.schema.yaml` | 906 B / 42 行 | **雲龍國際音標**（`version: '5.2'`；編碼設計 潘悟雲、李龍，整理 采采卷耳，方案設計 佛振）；`table_translator` + `dictionary: ipa_yunlong` |
| `ipa_yunlong.dict.yaml` | 6,317 B / 660 行 | IPA 码表 |
| `luna_pinyin.schema.yaml` | 1,901 B / 95 行 | **朙月拼音**（`version: "0.23"`，佛振）；`script_translator`、`dictionary: luna_pinyin` |
| `luna_pinyin.dict.yaml` | **889,946 B** / 70,728 行 | 自包含词典，供 `hakka_jiugong`/`hakka_qwerty` 的 `dependencies` 与 `reverse_lookup` 使用 |

> APK 从 11.4 MB 长到 13.4 MB 的主因就是这 890 KB 词典 + 方案资源，**不是废数据**。

### 6.4 主题 yaml 的包名引用

已改为新包名：`trime.yaml:940-941`（`run com.hazukieq.hkrime` / `com.hazukieq.hkrime/.Pref`）、`:953-954`（`broadcast …deploy` / `…sync`）；`tongwenfeng.trime.yaml:3966-3967`、`hakka_theme.trime.yaml:3767-3768` 同理。assets 内已无 `com.osfans` / `com.tumuyan` 引用（实测 0 处）。

---

## 7. 方案结构解读

### 7.1 `hakka_jiugong`（客语12键）

`schema_id: hakka_jiugong`，作者佛振 + Uncle光，描述「漢語拼音，以符號按鍵 - / < \ 輸入四聲；拼音碼表根據 CC-CEDICT 改編」。

| 段 | 内容 | 说明 |
| --- | --- | --- |
| `dependencies` | `luna_pinyin` | ✅ 已随 `726a209` 提供 |
| `switches` | `simplification`（简/繁）、`ywz`（情）、`lenth`（联）、`_key_Hide` | ✅ 两个滤镜配置已落地 |
| `engine.processors` | ascii_composer / recognizer / key_binder / speller / punctuator / selector / navigator / express_editor | 8 个标准处理器 |
| `engine.segmentors` | ascii_segmentor / matcher / abc_segmentor / punct_segmentor / fallback_segmentor | 5 个 |
| `engine.translators` | punct_translator、table_translator@custom_phrase、reverse_lookup_translator、script_translator | 主翻译器用 `script_translator`（拼音式） |
| `engine.filters` | simplifier、uniquifier、simplifier@ywz、simplifier@lenth | 两个 `@` 滤镜分别指向 `ywz.json` / `lenth.json` |
| `speller.alphabet` | `zwxyvutsrqponmlkjihgfedcba1234567890;` | 12 键布局的基础字母表 |
| `speller.algebra` | `xform`/`xlit` 处理 `nz/th/ng` 声母与 `- / < \` 四声 | 四声被映射成数字（a3/a4…），再由 `preedit_format` 还原显示 |
| `translator` | `dictionary: hakka`、`prism: hakka`、`enable_user_dict`/`enable_sentence: true` | 指向 `hakka.dict.yaml` |
| `reverse_lookup` | `dictionary: luna_pinyin`、prefix `` ` ``、suffix `'` | ✅ 普通话拼音反查可用 |
| `custom_phrase` | `user_dict: custom_phrase`、`db_class: tabledb` | 短语表 |

`preedit_format` 是一长串 `xform`（约 35 条），把内部数字声调还原为 `an/ang/at/ap/ak/in/it/ip/...` 等拼音韵母，用于候选窗上屏前的拼音提示。

### 7.2 `hakka_qwerty`（客语26键）

与 12 键同源（同一份 `hakka` 词典），差别在键盘与拼写代数：`prism: hakka_qwerty` 会另建 `hakka_qwerty.prism.bin`；
`reverse_lookup` 同样指向 `luna_pinyin`。作者 叶月绘梨依，描述提到「参考 luna 拼音方案，词汇依据李城宗硕士论文『高峰客家话方言研究』…」。

### 7.3 `ipa_yunlong`（雲龍國際音標）

独立方案：`table_translator` + `dictionary: ipa_yunlong`，码表由 `ipa_yunlong.dict.yaml` 提供；用于输入 IPA 音标，与客家话拼音互补。

---

## 8. 原缺口：已全部修复（`726a209`）

上一版文档列出的 5 条缺口，现状态如下（均可在仓库内直接核对）：

| # | 原缺口 | 现状 | 证据 |
| --- | --- | --- | --- |
| 1 | `schema_list` 的 `hakka_qwerty`、`ipa_yunlong` 无对应 `.schema.yaml` | ✅ 已补齐，且新增 `luna_pinyin` | `assets/rime/hakka_qwerty.schema.yaml`（134 行）、`ipa_yunlong.schema.yaml`（42 行）、`luna_pinyin.schema.yaml`（95 行） |
| 2 | 缺 `luna_pinyin`（`dependencies` / 反查目标） | ✅ 已随包提供 | `luna_pinyin.schema.yaml` + `luna_pinyin.dict.yaml`（889,946 B，自包含） |
| 3 | 缺 `ywz.json`、`lenth.json`（「情」「联」开关） | ✅ 已落地并重写为规范 OpenCC 格式 | `opencc/ywz.{json,txt}`（141 词条）、`opencc/lenth.{json,txt}`（31 词条） |
| 4 | 未预置用户词库 `*.userdb` | ⏸ **仍是设计取舍**（见下） | 仓库无 `*.userdb`；librime 首次部署自建 |
| 5 | `user.yaml` 是旧机状态残留 | ✅ 已删除 | `726a209` 删除 `assets/rime/user.yaml`（24 行） |

附带修复：`jp2t.json` 缺反向依赖 → 新增 `opencc/JPVariantsRev.txt`（366 行）。

**静态自检已固化**：`script/verify-rime-assets.py`（183 行，需 PyYAML）检查
`schema_list → schema 文件`、`schema.dictionary → dict.yaml`、`opencc_config → json`、
`json 引用的文件存在（`xxx.ocd2` 允许由 `xxx.txt` 生成）`、`ywz/lenth 多值抽查`；
实跑 **53 passed, 0 failed**。建议每次改 assets 后先跑它（见 §10.3）。

### 8.1 仍然存在的限制（不是 bug）

| 项 | 说明 |
| --- | --- |
| 不预置 `.userdb` | 用户词频属于个人数据，librime 首次部署会自建；预置会导致「别人的词频」进新装环境 |
| 多值候选未经界面验证 | `ywz`/`lenth` 的多值语义依赖 librime simplifier（§3.1）；仪器测试只验证配置可加载，**候选是否真的展示多个值需人工冒烟** |
| `default.yaml` 里仍有 `bopomofo_tw` / `luna_pinyin_simp` | 这两个方案本仓库不提供，但被 `default.custom.yaml` 覆盖，用户不可见；保留是为了与上游 `default.yaml` 一致 |

---

## 9. 扩充与自定义指引

### 9.1 加/换一个 RIME 方案

1. 把 `<id>.schema.yaml` 与 `<name>.dict.yaml` 放进 `app/src/main/assets/rime/`；
2. 若要在方案菜单显示，确认 `default.custom.yaml` 的 `schema_list` 含该 `schema_id`；
3. 跑 `python3 script/verify-rime-assets.py`（应 0 failed）；
4. 重装 APK（或清应用数据）触发 `prepareRime` 重新复制，再「部署」，检查 `userDataDir/build/` 是否生成对应 `*.bin`。

### 9.2 加自己的词典

- 新词典文件命名 `<name>.dict.yaml`，头部 `name: <name>` 与文件名一致；
- 在主字典 `import_tables` 里加一行 `<name>`（参考 `hakka.dict.yaml:28-36`）；
- 或独立成表，在 schema 的 `translator.dictionary` 指向它。

### 9.3 加 OpenCC 转换（含多值词典）

- 把需要的 `.txt` + `.json` 放进 `assets/rime/opencc/`；
- json 的 `segmentation` 指向 `xxx.ocd2`、`conversion_chain` 指向 `xxx.txt`（部署时 txt→ocd2 自动生成）；
- 多值写法：`词条<TAB>值1 值2 值3`（OpenCC text 词典用空格分隔）；
- 在 schema 的 `simplifier` 滤镜里写 `opencc_config: xxx.json`；
- 重新部署后确认同名 `.ocd2` 已生成（18 → 更多）。

### 9.4 换主题

`hakka_theme.trime.yaml` 是完整主题（3,923 行）。新增主题后需在「外观」里选中；主题里的 `run`/`broadcast` 目标包名必须与 `applicationId` 一致，否则「部署/同步/打开设置」按钮失效——这是改名后最容易漏的一层（见 [05](05-branches-release.md)）。

---

## 10. 怎么验证

### 10.1 仪器测试（推荐，可复现）

```bash
# 1) 构建 app + 测试 APK
JAVA_HOME=$HOME/opt/jdk17/jdk-17.0.9 ./gradlew assembleDebug assembleDebugAndroidTest

# 2) 安装
adb install -r app/build/outputs/apk/debug/trime-3.2.7-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/trime-3.2.7-debug-androidTest.apk

# 3) 授予「所有文件访问」——重装后会被重置，必须重设
adb shell appops set com.hazukieq.hkrime MANAGE_EXTERNAL_STORAGE allow

# 4) 跑测试（必须套 timeout，见 06 §4.2）
timeout 300 adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
# 期望：OK (1 test)
```

测试会**先清空 sharedDataDir 与 `userDataDir/build`**，再强制 fresh 部署（否则同版本覆盖安装不会重拷 assets），断言覆盖：
3 个方案名（客语12键 / 客语26键 / 雲龍）、10 个 `*.bin`（含 `hakka_qwerty.prism.bin`、`ipa_yunlong.*`、`luna_pinyin.*`）、
18 个 `*.ocd2`、`ywz`/`lenth` 可加载、4 组 OpenCC 转换。详见 `03-build.md` §5。

### 10.2 手工检查

```bash
adb shell ls /storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime | head
adb shell ls /storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime/opencc | wc -l        # 52
adb shell ls /storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime/opencc | grep -c ocd2 # 18
adb shell ls /storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime/build | wc -l          # 10
adb shell ls /storage/emulated/0/rime/build | grep hakka    # userDataDir 视配置而定
```

> 若 `userDataDir` 指到 `/sdcard/rime`（用户改过设置），`build/` 就在那里；**测试/清理前先确认那是用户真实数据并备份**（真机测试时就发生过）。

### 10.3 静态自检（无需设备）

```bash
python3 script/verify-rime-assets.py        # 期望：结果: 53 passed, 0 failed
```

相关：[02 JNI 与 .so](02-jni-so.md)、[03 构建与测试](03-build.md)、[04 客家话定制](04-hakka.md)、[06 排障与路线图](06-troubleshooting-roadmap.md)。
