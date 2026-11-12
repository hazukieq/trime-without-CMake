# 16 · librime 12 键覆盖测试（PC 端，真实数据）

> **本文回答两个问题**：① 我们对 librime 的本地补丁，在**真实词库 + 真实全量音节 + 全编码组合**下
> 到底覆盖多少、漏多少；② 哪些缺陷应该改在 patch 里（而不是在 App 端再加补丁）。
>
> 测试全部在 **PC 上跑**（同一份 librime 源码 + 同一份真机数据，Release `-O2`）。
> 真机只用来做性能确认：**PC 上慢的，真机一定更慢**；`-O` 级别的差异不足以改变结论。

## 目录

1. [为什么在 PC 上跑](#1-为什么在-pc-上跑)
2. [目录与用法](#2-目录与用法)
3. [测什么（用例生成）](#3-测什么用例生成)
4. [结果](#4-结果)
5. [性能](#5-性能)
6. [发现与结论（该改 patch 的）](#6-发现与结论该改-patch-的)
7. [已知缺口 / 下一步](#7-已知缺口--下一步)

---

## 1. 为什么在 PC 上跑

- 覆盖测试要跑**上万条**用例（真实词库 11962 条），真机点键盘不可能；
- `rime-jni/host-test` 用 CMake 把**同一份 `librime/` 源码**（含本地补丁 0001/0003）编成 PC 静态库，
  依赖走系统包（boost_regex / glog / yaml-cpp / leveldb / marisa / opencc），一条命令起测试；
- 数据用**真机快照**（`/sdcard/hkrime/*.yaml|*.txt`，即使用者当前真正在用的方案、主词典、
  `import_tables` 里的全部扩展词表、自定义短语），不是随包 assets 的旧副本。

## 2. 目录与用法

```
rime-jni/host-test/
├── CMakeLists.txt          # 编 librime(PC) + 两个可执行：coverage_test / coverage_test_nofallback(对照)
├── coverage_test.cc        # 测试主程序（见 §3）
├── run-host-test.sh        # 准备 user_data_dir（可写副本）→ 部署 → 跑测试
└── pull-real-data.sh       # 从真机拉真实数据快照（默认 /tmp/hkrime-real-data）
```

```bash
cd rime-jni/host-test
./pull-real-data.sh                    # 拉真机数据（只读，不入 git）
./run-host-test.sh                     # 全量覆盖测试（约 10s，含部署）
./run-host-test.sh --bench             # 性能：每个不同 code 的 feed+全量枚举耗时
./run-host-test.sh --probe 5i4a57i     # 单个码串的候选前 12 条
./run-host-test.sh --probe 5i4ii8i9e34i54iei9 --select 1
                                        # 选中第 1 条后：commit / 剩余 input / preedit / 分段（复刻「部分上屏」）
./run-host-test.sh --dump 547          # 单个码串的全部候选
./run-host-test.sh --queryd "li nak si" # 直接问表：这条音節序列在表里有哪些词条
./run-host-test.sh --syll 5i4a57i      # 音節图：edges / Dictionary::Lookup 的 chunk 统计
./run-host-test.sh --prism w           # prism 里这个键映射到哪些音節（简拼诊断）
```

> `--select` 是「部分上屏」的复刻入口：`express_editor` 的 `_auto_commit` 会让
> **候选 end == 输入长度**的条目直接 commit 整串（实测 `5i4ii8i9934i54iei9` 选 `灵机一动`
> 后 input 清空）；候选 end 小于输入时才会切成 `已选中段 + 剩余段`。App 侧据此**不走**
> `select_candidate`（`holdPartialPick` 把词留在 preedit 的 pendingPick、剩余码重喂组合），
> PC 复刻见 `hkrime/tools/panel_probe.py preedit <code> --pick "<reading>"`。
>
> 测试程序内部用 `RimeTraits` + `RimeInitialize` + 维护线程部署，等价于真机启动路径；
> `coverage_test_nofallback` 是编译期关掉「全码路径兜底」的对照组（`-DHKRIME_NO_FULLPATH_FALLBACK`）。

## 3. 测什么（用例生成）

全部从 **真实数据** 自动生成，共 **11962** 条：

| 类别 | 生成方式 | 条数 |
|---|---|---|
| `single` | 主词典每条「字<TAB>音節」→ 音節转 12 键码 → 打码，断言该字出现 | 9134 |
| `word2` / `word3` / `word4+` | 主词典 + `import_tables` 里全部词条（按音節数分档） | 926 / 358 / 78 |
| `ascii` | 含非汉字的词条（如 `Linux`、`gemini`、`chatgpt`） | 52 |
| `abbrev` | 多音節词条，每个音節只打**首键**（12 键简拼：`li nak si` → `547`） | 835 |
| `abbrev-shadowed` | 简拼码串**同时也能读成完整音節**（rime 的 syllabifier 会按设计剪掉简拼边，必然出不来） | 579 |
| `custom` | `custom_phrase.txt` 的「词<TAB>码」 | 0（用户当前为空） |

关键点：

- **全量音節**：主词典里出现的音節（714 个）都覆盖到，不是挑几个样例；
- **全编码组合**：每个音節用自己的码，多音節词条按「音節码串接」；
- **缩写组合**：每个多音節词条再生成一份「全部只打首键」的用例，并区分「会被完整音節读法遮蔽」的；
- 音節→12 键码的映射与 App 的 `TwelveKeyPreedit.codeOf()` **同一套规则**（`host-test/coverage_test.cc`
  顶部的 `CodeOf` 是它的镜像；两边漂移会立刻大量失败，等于自带一致性校验）。

## 4. 结果

### 4.1 当前补丁（`0003` 升级版：终点桶放宽 + 简拼预算按需放大 + 全码路径兜底）

| 类别 | 通过 | 通过率 |
|---|---|---|
| `single` | 9134 / 9134 | **100%** |
| `word2` | 926 / 926 | **100%** |
| `word3` | 358 / 358 | **100%** |
| `word4+` | 78 / 78 | **100%** |
| `ascii`（含 `Linux`/`gemini`） | 52 / 52 | **100%** |
| `abbrev`（纯简拼） | 657 / 835 | **78.7%** |
| `abbrev-shadowed`（被完整音節读法遮蔽，设计如此） | 0 / 579 | 0%（符合预期） |

### 4.2 对照：旧版 `0003`（只在「终点桶为空」时兜底）

| 类别 | 旧版 | 现在 |
|---|---|---|
| `word3` | 93.9% | 100% |
| `word4+` | 73.1% | 100% |
| `ascii` | 98.1% | 100% |
| 简拼（未分类口径） | 20.4% | 78.7%（纯简拼口径） |

→ 说明**根因一直是同一个**：`Table::Query` 的索引 BFS 在 12 键的多音節歧义（`5i`=ki|li、`4a5`=mak|nak）
+ 简拼分支爆炸下，走不到/走不全「整串」那一层；旧兜底只补了「终点桶完全为空」，补不到「有但被挤掉」的情形。

## 5. 性能

`--bench`：每个不同 code 一次「喂键 + 全量候选枚举」，PC `-O2`（真机会更慢，但同一量级）：

| 类别 | p50 | p90 | max |
|---|---|---|---|
| `single` | 0.021ms | 0.099ms | 0.59ms |
| `word2` | 0.067ms | 0.181ms | 0.61ms |
| `word3` | 0.059ms | 0.170ms | 0.56ms |
| `word4+` | 0.070ms | 0.367ms | 0.58ms |
| `abbrev` | **5.6ms** | **10.4ms** | 31ms |

- 全拼路径（1~4 音節）都在 **0.1ms 量级**，补丁的额外开销可忽略（对照关闭兜底几乎相同）；
- `abbrev` 慢是**简拼本身的分支爆炸**（一个键 ≈100 个音節，`547` 能出 1078 条候选），
  与兜底无关（关掉兜底同样 5.66ms）。App 侧只枚举前 N 条快照，实际每键成本低于此表。

## 6. 发现与结论（该改 patch 的）

1. **终点桶配额太小**（沿用 0001 的 `kMaxChunksPerEnd=48`）：整串词条被高频短路徑挤出候选。
   → 改成「终点桶 ×4」，全拼各类从 73%~94% 直接到 100%。
2. **简拼预算不足**：`kMaxExpansions=12000` 在简拼时不够走到整串。
   → 检测到 `kAbbreviation` 边时预算 ×8、桶 ×8、DFS 每前缀 512 条；纯全拼仍是紧预算（不拖慢）。
3. **兜底策略要升级**：只在「终点桶为空」时补是不够的（桶里有别的词就不补了）。
   → 改成**总是**按全码路径补一次、按词面去重；配合上面两项，`word3/4+` 补齐。
4. 结论：这三处都**改在 librime patch（`patches/0003`）**，App 端零改动 —— 符合「能在 patch 修就不在 App 打补丁」。

## 7. 已知缺口 / 下一步

- **纯简拼 78.7%**：剩余 21% 是首键组合能命中、但候选被 `Table::Query` 的中间桶/路径上限截断；
  可以在 patch 里继续放宽（代价是简拼每键耗时上升），或改用「按 trie 节点预算」而不是「按路径数」。
  当前取舍：宁可留 21% 缺口，也不让简拼每键超过 10ms（p90）。
- `abbrev-shadowed` 0% 是 rime 的设计（完整音節读法优先），**不是**缺陷；这类不需要修。
- `custom_phrase.txt` 当前为空，用例为 0；用户一旦加词，测试会自动覆盖。
- 真机侧只需在补丁更新后重编 `.so` + 装机；性能门禁（`docs/13`）可在真机上复核简拼的按键延迟。

## 5. 用例来源补充：用戶詞庫（hakka.userdb）

用戶 2026-11-11 要求「hakka.userdb 也加入測試集，裡面有最真實的音節組合」。
`pull-real-data.sh` 現在會順便拉 `/sdcard/hkrime/sync/*/*.userdb.txt`（librime sync 的導出），
覆蓋測試新增 `userdb` 類：**66236 條**（70258 行裡濾掉 `c<=0`，再按「碼串|文字」去重）。

```
userdb   total=  66236 found= 27750 ( 41.90%)
```

**這 41.9% 不是缺陷指標，不要當門檻**：userdb 的 key 是**音節拼寫**（`ba ba ma ma`），
而 12 鍵輸入是數字串（`aaaa4a4a`）——`table_translator` 查 userdb 用的是原始輸入串，
拼寫 key 命中不了；12 鍵的「記住」實際走 App 側 `PhraseMemory` 合成候選 / `custom_phrase` 編碼表。
實測：`--dump aaaa4a4a` 有 51 條候選、`爸爸` 在列，但 `爸爸妈妈`（userdb 裡 c=1）不在。

所以 userdb 的價值在**preedit / 面板的真實語料**（見 `docs/10` §15.67.4：
`--corpus-userdb` 生成 30000 幀，全部通過），不是覆蓋率分母。
