# 07 · OpenCC 与 RIME 数据：从 assets 到能用的输入法

> 本篇讲「数据」而不是「代码」：`app/src/main/assets/rime/` 里的文件怎么变成用户手机上能用的词典、方案与简繁转换。
> 读完应能回答三个问题：**这些文件为什么必需？它们被复制到哪里、又被谁加工？少一个会怎样？**

## 目录

1. [速查](#1-速查)
2. [数据分层与部署管线](#2-数据分层与部署管线)
3. [OpenCC：txt / json / ocd2 三种文件的关系](#3-opencctxt--json--ocd2-三种文件的关系)
4. [管线细节：谁在什么时候做什么](#4-管线细节谁在什么时候做什么)
5. [简繁与地区转换：s2t ≠ s2tw ≠ s2twp](#5-简繁与地区转换s2t--s2tw--s2twp)
6. [客家话数据清单](#6-客家话数据清单)
7. [`hakka_jiugong` 方案结构解读](#7-hakka_jiugong-方案结构解读)
8. [已知缺口（务必先读）](#8-已知缺口务必先读)
9. [扩充与自定义指引](#9-扩充与自定义指引)
10. [怎么验证](#10-怎么验证)

---

## 1. 速查

| 项 | 值 |
| --- | --- |
| assets 内 rime 数据 | `app/src/main/assets/rime/`，共 **19 项**：16 个客家话文件 + `trime.yaml` + `tongwenfeng.trime.yaml` + `opencc/` |
| OpenCC 资源 | `assets/rime/opencc/` 下 **29 个**：**15 个 `.txt` 词典 + 14 个 `.json` 转换配置** |
| 运行期 sharedDataDir | 真机实测 `/storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime` |
| 运行期 userDataDir | 默认同 sharedDataDir；真机实测该机为 `/storage/emulated/0/rime`（用户既有偏好） |
| ocd2 产物 | 部署时由 `.txt` 生成，实测 **15 个**，与 15 个 `.txt` 一一对应 |
| opencc 目录条目 | 运行期 **44** = 29 个 assets 文件 + 15 个 `.ocd2` |
| OpenCC 版本 | `ver.1.1.4`（`Rime.get_opencc_version()`） |
| librime 构建产物 | `userDataDir/build/` 下 `hakka.table.bin`、`hakka.reverse.bin`、`hakka.prism.bin` |

> 44 / 15 / 19 这三个数字常被混淆：**19 是 assets，29 是 opencc 子目录，44 是运行期 opencc 目录**。

---

## 2. 数据分层与部署管线

```
┌─────────────────────────── APK（只读）───────────────────────────┐
│ app/src/main/assets/rime/                                        │
│   trime.yaml  tongwenfeng.trime.yaml                              │
│   hakka*.dict.yaml  hakka_jiugong.schema.yaml  hakka_theme...    │
│   default.yaml  default.custom.yaml  symbols.yaml  user.yaml     │
│   opencc/  15×*.txt（词典源） + 14×*.json（转换配置）              │
└───────────────────────────────┬──────────────────────────────────┘
                                │ Config.prepareRime() 首次/换版本时整目录复制
                                ▼
┌──────────────── sharedDataDir（可写，assets 的镜像）─────────────┐
│ .../files/rime/  ← 与 assets 同名同结构                           │
│   opencc/  29 个源文件  ──OpenCCDictManager.internalDeploy()──┐  │
│                                                              │  │
│                    每个 *.txt → 同名 *.ocd2（15 个）  ◀───────┘  │
└───────────────────────────────┬──────────────────────────────────┘
                                │ Rime.get(ctx,true) → setup/initialize/deploy
                                ▼
┌──────────────── userDataDir（用户数据，可跨版本保留）────────────┐
│ build/  hakka.table.bin  hakka.reverse.bin  hakka.prism.bin      │
│ *.userdb（用户词频，本仓库不预置）                                │
└──────────────────────────────────────────────────────────────────┘
```

要点：

- **assets 是只读源**，不是运行时目录；改 assets 必须重新装 APK 才会生效。
- sharedDataDir 是 assets 的**整目录副本**（首次安装或版本变化时复制，见 `Config.java:178-212`）。
- userDataDir 里是 librime 部署的**二进制产物**与用户词库；`build/` 只在 userDataDir，不在 sharedDataDir。

---

## 3. OpenCC：txt / json / ocd2 三种文件的关系

| 文件 | 角色 | 谁读它 |
| --- | --- | --- |
| `*.txt`（15 个） | 词典**源文本**（如 `STCharacters.txt` 简体→繁体字表） | 部署时由 `OpenCCDictManager` 读，转成 `.ocd2` |
| `*.json`（14 个） | 一次转换任务的**配方**：选哪些词典、做几段转换、是否带词汇替换 | librime 的 simplifier / `openccConvert()` |
| `*.ocd2`（运行期 15 个） | 已编译的二进制词典（OpenCC 的 marisa-trie 格式） | librime / OpenCC 运行时 |

**简记：json 是「怎么转」，txt 是「依据什么转」，ocd2 是把 txt 加工后的运行时形态。**
librime 侧的 simplifier 只认 `.ocd2`，所以**只有 txt 没有部署＝转换静默失效**；反过来只留 ocd2 也能跑，但换 OpenCC 版本/改词典后无法再生成。

### 3.1 历史事故：OpenCC 资源曾被删光

`92c7aa5`（2023-09-15，标题 "close log"）删除过 `assets/rime/opencc/` 下全部 **31 个**文件（15 txt + 14 json + 2 个多余项），直接后果是：

- 简繁转换、台湾正体、香港字形等所有 OpenCC 功能失效；
- `Rime.openccConvert()` 找不到配置；
- `simplifier` 滤镜没有 ocd2 可用。

`2b50c1f`（本轮）从 `92c7aa5^` **恢复了 29 个**（15 txt + 14 json），并从用户 rime 目录并入客家话数据。恢复后真机实测 4 组转换全部正确（见 §5）。

> 这也是「不要凭体积判断文件价值」的教训：29 个文件只占几百 KB，却是整套简繁功能的前提。

### 3.2 14 个 json 一览

`hk2s` `hk2t` `jp2t` `s2hk` `s2t` `s2tw` `s2twp` `t2hk` `t2jp` `t2s` `t2tw` `tw2s` `tw2sp` `tw2t`

命名规则：`源→目标`，`s`=简体、`t`=繁体、`tw`=台湾正体、`hk`=香港字形、`jp`=日本新字体；带 `p` 的（`s2twp`、`tw2sp`）表示**含词汇替换**（phrase）。

### 3.3 15 个 txt 一览

字形/词汇表：`STCharacters` `STPhrases` `TSCharacters` `TSPhrases` `TWVariants` `TWVariantsRev` `TWVariantsRevPhrases` `TWPhrases` `TWPhrasesRev` `HKVariants` `HKVariantsRev` `HKVariantsRevPhrases` `JPVariants` `JPShinjitaiCharacters` `JPShinjitaiPhrases`。

---

## 4. 管线细节：谁在什么时候做什么

### 4.1 复制 assets → sharedDataDir：`Config.prepareRime()`

位置：`app/src/main/java/com/hazukieq/hkrime/data/Config.java:178-212`。

- 判断 sharedDataDir 是否已存在（`isExist`）、版本是否不同（`isOverwrite = AppVersionUtils.isDifferentVersion(pref)`）。
- 首次安装：整目录复制 assets/rime；升级且版本变化：覆盖复制；仅缺失：只补 `trime.yaml`。
- 复制后轮询等待 `trime.yaml` 出现，再补一个空的 `default.custom.yaml`（缺它会导致方案列表为空）。
- 末尾调用 `Rime.get(context, !isExist)`：**首次安装才强制部署**。

### 4.2 txt → ocd2：`OpenCCDictManager.internalDeploy()`

位置：`app/src/main/java/com/hazukieq/hkrime/data/opencc/OpenCCDictManager.kt:49-58`。

`Rime.get(ctx, true)` 时先执行 `OpenCCDictManager.internalDeploy()`：遍历 sharedDataDir/opencc 下的文本词典，逐个调用 native 的 `openccDictConv(src, dest, false)` 生成同名 `.ocd2`。真机实测生成 15 个，`:15` 的 `System.loadLibrary("rime_jni")` 就是这一步与 OpenCC 的唯一 JNI 依赖。

### 4.3 librime 部署：生成 build/*.bin

`Rime.get(ctx, true)` → `initialize()` → `deploy()`，librime 读取 sharedDataDir 下的 `*.schema.yaml` / `*.dict.yaml`，在 userDataDir/build 生成 `prism.bin`（音节索引）、`table.bin`（码表）、`reverse.bin`（反查）。实测客家话方案生成 `hakka.prism.bin` / `hakka.table.bin` / `hakka.reverse.bin`。

### 4.4 配置优先级

- `default.yaml` 自带 `schema_list: bopomofo_tw / luna_pinyin_simp / luna_pinyin`；
- `default.custom.yaml` 用 `patch:` 覆盖为 `hakka_jiugong / hakka_qwerty / ipa_yunlong`，并设 `"menu/page_size": 5`。

**custom 优先**，所以用户实际看到的方案列表来自 `default.custom.yaml`（这也解释了 §8 的缺口为什么用户可见）。

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

`assets/rime/` 下 16 个客家话相关文件：

| 文件 | 大小/行数 | 作用 |
| --- | --- | --- |
| `hakka.dict.yaml` | 10,545 行 | 主字典，`name: hakka`、`version: "20.20.20"`、`sort: by_weight`、`use_preset_vocabulary: true` |
| `hakka_zon.dict.yaml` | 3,538 | 扩展词典（`import_tables` 之一） |
| `hakka_website.dict.yaml` | 5,252 | 网络用语 |
| `hakka_sougou_words.dict.yaml` | 157,336 | 搜狗词库（体量最大） |
| `hakka_poems.dict.yaml` | 17,871 | 诗词 |
| `hakka_places.dict.yaml` | 4,762 | 地名 |
| `hakka_oftenwords.dict.yaml` | 1,206 | 常用词 |
| `hakka_mobilewords.dict.yaml` | 2,949 | 手机词 |
| `hakka_cn_en.dict.yaml` | 1,681 | 中英混合 |
| `hakka_jiugong.schema.yaml` | 175 | 方案定义（见 §7） |
| `hakka_theme.trime.yaml` | 3,923 | 主题「客家同文风」，`config_version: "3.0"` |
| `symbols.yaml` | 233 | 标点/符号表 |
| `default.yaml` | 151 | RIME 默认配置（方案列表被 custom 覆盖） |
| `default.custom.yaml` | 12 | 用户级覆盖：方案列表 + 每页 5 候选 |
| `user.yaml` | 24 | 用户状态（`last_build_time`、方案访问时间等） |
| `custom_phrase.txt` | 5 | 自定义短语表头 |

`hakka.dict.yaml` 的 `import_tables` 明确列出 8 张扩展词典：`hakka_places`、`hakka_sougou_words`、`hakka_poems`、`hakka_zon`、`hakka_mobilewords`、`hakka_oftenwords`、`hakka_website`、`hakka_cn_en`（`hakka.dict.yaml:28-36`）。

数据来源：字典文件头注释说明为**广西玉林兴业县高峰乡客家话**，作者 hazuki erii / 叶月绘梨依，发音取自高峰镇龙文村龙源坪，兼顾老中青三代读音与古韵书反切（`hakka.dict.yaml:1-26`）。

主题 yaml 中的进程/广播引用已改为新包名：`trime.yaml:940-941`（`run com.hazukieq.hkrime` / `com.hazukieq.hkrime/.Pref`）、`:953-954`（`broadcast …deploy` / `…sync`）；`tongwenfeng.trime.yaml:3966-3967`、`hakka_theme.trime.yaml:3767-3768` 同理。assets 内已无 `com.osfans` / `com.tumuyan` 引用（实测 0 处）。

---

## 7. `hakka_jiugong` 方案结构解读

`schema_id: hakka_jiugong`，显示名「客语12键」，作者佛振 + Uncle光，描述「漢語拼音，以符號按鍵 - / < \ 輸入四聲；拼音碼表根據 CC-CEDICT 改編」。

| 段 | 内容 | 说明 |
| --- | --- | --- |
| `dependencies` | `luna_pinyin` | 反查依赖，**仓库未带** → 见 §8 |
| `switches` | `simplification`（简/繁）、`ywz`（关/情）、`lenth`（关/联）、`_key_Hide` | 后两者依赖缺失的 opencc 配置 |
| `engine.processors` | ascii_composer / recognizer / key_binder / speller / punctuator / selector / navigator / express_editor | 8 个标准处理器 |
| `engine.segmentors` | ascii_segmentor / matcher / abc_segmentor / punct_segmentor / fallback_segmentor | 5 个 |
| `engine.translators` | punct_translator、table_translator@custom_phrase、reverse_lookup_translator、script_translator | 主翻译器用 `script_translator`（拼音式） |
| `engine.filters` | simplifier、uniquifier、simplifier@ywz、simplifier@lenth | 两个 `@` 滤镜对应缺失的 json |
| `speller.alphabet` | `zwxyvutsrqponmlkjihgfedcba1234567890;` | 12 键布局的基础字母表 |
| `speller.algebra` | `xform`/`xlit` 处理 `nz/th/ng` 声母与 `- / < \` 四声 | 四声被映射成数字（a3/a4…），再由 `preedit_format` 还原显示 |
| `translator` | `dictionary: hakka`、`prism: hakka`、`enable_user_dict`/`enable_sentence: true` | 指向 `hakka.dict.yaml` |
| `reverse_lookup` | `dictionary: luna_pinyin`、prefix `` ` ``、suffix `'` | 普通话拼音反查（缺词典则不生效） |
| `custom_phrase` | `user_dict: custom_phrase`、`db_class: tabledb` | 短语表 |

`preedit_format` 是一长串 `xform`（约 35 条），把内部数字声调还原为 `an/ang/at/ap/ak/in/it/ip/...` 等拼音韵母，用于候选窗上屏前的拼音提示。

---

## 8. 已知缺口（务必先读）

| 缺口 | 证据 | 用户可见后果 |
| --- | --- | --- |
| `schema_list` 列了 `hakka_qwerty`、`ipa_yunlong`，但**没有对应 `.schema.yaml`** | `default.custom.yaml` 的 `schema_list` vs assets 实际文件 | 方案菜单里出现两项，选中后无法部署/无候选 |
| 缺 `luna_pinyin` 词典 | `hakka_jiugong.schema.yaml` 的 `dependencies` 与 `reverse_lookup.dictionary` | `` ` `` 反查无结果；`dependencies` 警告 |
| 缺 `ywz.json`、`lenth.json` | schema 的 `ywz`/`lenth` 滤镜配置，opencc 目录只有 14 个 json（无这两个） | 「情」「联」两个开关无效或部署告警 |
| 未预置用户词库 | 仓库无 `*.userdb` | 首次使用无个人词频（设计取舍，不是 bug） |
| `user.yaml` 保留旧机状态 | 含 `hakka_giugiung`、`kakka_*`、`ipa_*` 等本仓库不存在方案的访问时间 | 无功能影响，属数据残留 |

部署**仍能成功**：实测可生成 `hakka.table.bin` / `reverse.bin` / `prism.bin`，简繁转换可用。上述缺口的正确表述是「**方案与词典主路径可用，扩展开关与反查待补料**」，不要写成「客家话不可用」。

---

## 9. 扩充与自定义指引

### 9.1 补一个 RIME 方案（例如补 `luna_pinyin`）

1. 把 `luna_pinyin.schema.yaml`、`luna_pinyin.dict.yaml`（及 `default.yaml` 里被引用的预设）放进 `app/src/main/assets/rime/`；
2. 若要在方案菜单显示，确认 `default.custom.yaml` 的 `schema_list` 含该 `schema_id`；
3. 重装 APK（或清应用数据）触发 `prepareRime` 重新复制；
4. 在「部署」后检查 `userDataDir/build/` 是否生成对应 `*.table.bin`。

### 9.2 加自己的词典

- 新词典文件命名 `<name>.dict.yaml`，头部 `name: <name>` 与文件名一致；
- 在主字典 `import_tables` 里加一行 `<name>`（参考 `hakka.dict.yaml:28-36`）；
- 或独立成表，在 schema 的 `translator.dictionary` 指向它。

### 9.3 加 OpenCC 转换

- 把需要的 `.txt` + `.json` 放进 `assets/rime/opencc/`；
- 在 schema 的 `simplifier` 滤镜里写 `opencc_config: xxx.json`；
- 重新部署后确认同名 `.ocd2` 已生成（15→更多）。

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

# 4) 跑测试（必须套 timeout，见 06）
timeout 300 adb shell am instrument -w -r \
  -e class com.hazukieq.hkrime.RimeDataTest \
  com.hazukieq.hkrime.test/androidx.test.runner.AndroidJUnitRunner
# 期望：OK (1 test)
```

断言覆盖：assets 部署（5 个关键文件）、opencc ocd2 ≥15、`build/` 下 `hakka.table.bin`、OpenCC 版本、4 组转换、schema 列表非空。详见 `03-build.md`。

### 10.2 手工检查

```bash
adb shell ls /storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime | head
adb shell ls /storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime/opencc | wc -l   # 44
adb shell ls /storage/emulated/0/Android/data/com.hazukieq.hkrime/files/rime/opencc | grep -c ocd2  # 15
adb shell ls /storage/emulated/0/rime/build | grep hakka   # userDataDir 视配置而定
```

> 若 `userDataDir` 指到 `/sdcard/rime`（用户改过设置），`build/` 就在那里；**测试/清理前先确认那是用户真实数据并备份**（本轮实测机上就发生过）。

相关：[02 JNI 与 .so](02-jni-so.md)、[03 构建与测试](03-build.md)、[04 客家话定制](04-hakka.md)、[06 排障与路线图](06-troubleshooting-roadmap.md)。
